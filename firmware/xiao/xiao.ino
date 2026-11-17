// XIAO ESP32S3 Sense: camera and mic over native USB, photos over Bluetooth LE.
// 'p' replies "JPG <length>\n" followed by one JPEG.
// 's' 320x240, 'v' 640x480 (default), 'x' 800x600, 'g' 1024x768, 'u' 1600x1200
// (same letters as firmware/snap and firmware/snapwifi).
// '1'-'9' records that many seconds from the PDM mic, replies "WAV <length>\n" and a
// 16 kHz, 16-bit mono WAV (the same cleaned-up audio the phone gets: DC removed, gain applied).
// 'i' prints "IP <addr> ..." (or "IP none ...") first, then the camera, memory and BLE link.
// Bluetooth LE ("sense-xiao"): write the same letters to CMD_UUID; DATA_UUID notifies
// "JPG <length>" and then the JPEG in chunks of (MTU - 3) bytes.
// Mic over Bluetooth: 'a' starts and 'q' stops a stream on AUDIO_UUID, one notification per
// 20 ms: [seq][predictor lo][predictor hi][step index] + 160 bytes of IMA ADPCM (320 samples,
// 16 kHz, low nibble first). Each packet carries its decoder state, so it decodes on its own.
// The stream starts with the 100 ms before 'a' and ends with the single byte 0xEE.
// Wi-Fi as in firmware/snapwifi (same secrets.h, same pages and 'o'/'n'/'w' commands), so the
// phone app treats both boards alike. The phone turns Wi-Fi off ('o') over USB and Bluetooth:
// the radio is shared, and Wi-Fi left on slows Bluetooth down.
// Build with PSRAM=opi; the camera buffers live there.

#include "esp_camera.h"
#include "ESP_I2S.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <WebServer.h>
#include "secrets.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "os/os_mbuf.h"

const int MIC_CLK = 42, MIC_DATA = 41;
#define SERVICE_UUID "5e0e0001-7a2b-4c1e-9a8f-2b6d3c4e5f60"
#define CMD_UUID     "5e0e0002-7a2b-4c1e-9a8f-2b6d3c4e5f60"
#define DATA_UUID    "5e0e0003-7a2b-4c1e-9a8f-2b6d3c4e5f60"
#define AUDIO_UUID   "5e0e0004-7a2b-4c1e-9a8f-2b6d3c4e5f60"

const int FRAME = 320;     // 20 ms at 16 kHz
const int PREROLL = 5;     // frames kept from before the press, so the first word isn't clipped
const int MIC_GAIN = 8;    // the raw PDM signal peaks around -35 dBFS for speech at arm's length

bool camReady = false;
framesize_t size = FRAMESIZE_VGA;
I2SClass mic;
BLEServer *server;
BLECharacteristic *dataChar, *audioChar;
QueueHandle_t bleCmds = xQueueCreate(16, 1);  // the phone sends several letters back to back
volatile bool bleConnected = false;
uint16_t connId = 0;
WebServer web(80);
bool wifiWanted = true;
bool tryHotspot = true;  // alternate between the two networks while neither is joined
unsigned long lastJoin = 0;

unsigned long lastSendMs = 0, lastRetries = 0, lastBytes = 0;

uint16_t dataHandle, audioHandle;
volatile bool audioOn = false;
struct AudioPacket { uint8_t len; uint8_t data[4 + FRAME / 2]; };
struct Adpcm { int32_t pred = 0; int index = 0; };
QueueHandle_t audioPackets = xQueueCreate(100, sizeof(AudioPacket));  // 2 s of slack
unsigned long audioSent = 0, audioDropped = 0, audioRetries = 0;
int16_t *usbRec = nullptr;                 // a USB recording taps the same cleaned-up stream
volatile size_t usbRecLen = 0, usbRecWant = 0;

// Link changes the phone makes on its own, printed by 'i'.
String linkLog;
ble_gap_event_listener linkListener;
int onGapEvent(ble_gap_event *e, void *) {
  char line[64] = "";
  if (e->type == BLE_GAP_EVENT_DATA_LEN_CHG)
    snprintf(line, sizeof(line), "%lus datalen tx %u rx %u; ", millis() / 1000,
             e->data_len_chg.max_tx_octets, e->data_len_chg.max_rx_octets);
  if (e->type == BLE_GAP_EVENT_CONN_UPDATE) {
    ble_gap_conn_desc d;
    if (ble_gap_conn_find(e->conn_update.conn_handle, &d) == 0)
      snprintf(line, sizeof(line), "%lus interval %.2f; ", millis() / 1000, d.conn_itvl * 1.25);
  }
  if (e->type == BLE_GAP_EVENT_PHY_UPDATE_COMPLETE)
    snprintf(line, sizeof(line), "%lus phy %u; ", millis() / 1000, e->phy_updated.tx_phy);
  linkLog += line;
  if (linkLog.length() > 600) linkLog = linkLog.substring(linkLog.length() - 400);
  return 0;
}

class ServerEvents : public BLEServerCallbacks {
  void onConnect(BLEServer *s, ble_gap_conn_desc *desc) override {
    connId = desc->conn_handle;
    // Handles are only assigned once the GATT server has started, after svc->start().
    dataHandle = dataChar->getHandle();
    audioHandle = audioChar->getHandle();
    bleConnected = true;
    // Ask for the fastest link the phone allows: 7.5 ms interval, 251-byte packets, 2M PHY.
    s->updateConnParams(connId, 6, 6, 0, 400);
    ble_gap_set_data_len(connId, 251, 2120);
    ble_gap_set_prefered_le_phy(connId, BLE_GAP_LE_PHY_1M_MASK, BLE_GAP_LE_PHY_1M_MASK, 0);
  }
  void onDisconnect(BLEServer *s) override {
    bleConnected = false;
    audioOn = false;
    BLEDevice::startAdvertising();
  }
};

class CmdEvents : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    for (char ch : v) {
      // Mic start/stop act at once: queued, they could wait behind a photo being sent.
      if (ch == 'a') audioOn = true;
      else if (ch == 'q') audioOn = false;
      else xQueueSend(bleCmds, &ch, 0);
    }
  }
};

void startBle() {
  BLEDevice::init("sense-xiao");
  BLEDevice::setMTU(517);
  ble_gap_event_listener_register(&linkListener, onGapEvent, nullptr);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerEvents());
  BLEService *svc = server->createService(SERVICE_UUID);
  BLECharacteristic *cmd = svc->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CmdEvents());
  dataChar = svc->createCharacteristic(DATA_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  dataChar->addDescriptor(new BLE2902());
  BLECharacteristic *audioChar = svc->createCharacteristic(AUDIO_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  audioChar->addDescriptor(new BLE2902());
  svc->start();
  ::audioChar = audioChar;
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// NimBLE refuses a notification when its buffers are full; wait for the radio and resend.
// Called from the photo loop and the mic sender at the same time, so no shared state.
bool bleNotify(uint16_t handle, const uint8_t *p, size_t n, unsigned long &retries) {
  for (unsigned long t = millis(); bleConnected && millis() - t < 2000;) {
    os_mbuf *om = ble_hs_mbuf_from_flat(p, n);
    if (om && ble_gatts_notify_custom(connId, handle, om) == 0) return true;  // om consumed either way
    retries++;
    delay(1);
  }
  return false;
}

// --- mic -----------------------------------------------------------------------------------

const int8_t ADPCM_INDEX[16] = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
const int16_t ADPCM_STEP[89] = {
  7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
  73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
  449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
  2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
  9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};

uint8_t adpcmEncode(Adpcm &s, int16_t x) {
  int step = ADPCM_STEP[s.index];
  int diff = x - s.pred;
  uint8_t code = 0;
  if (diff < 0) { code = 8; diff = -diff; }
  int delta = step >> 3;
  if (diff >= step) { code |= 4; diff -= step; delta += step; }
  step >>= 1;
  if (diff >= step) { code |= 2; diff -= step; delta += step; }
  step >>= 1;
  if (diff >= step) { code |= 1; delta += step; }
  s.pred = constrain(s.pred + ((code & 8) ? -delta : delta), -32768, 32767);
  s.index = constrain(s.index + ADPCM_INDEX[code], 0, 88);
  return code;
}

void queueFrame(const int16_t *pcm, uint8_t seq, Adpcm &s) {
  AudioPacket p;
  p.len = sizeof(p.data);
  p.data[0] = seq;
  p.data[1] = s.pred & 0xFF;
  p.data[2] = (s.pred >> 8) & 0xFF;
  p.data[3] = s.index;
  for (int i = 0; i < FRAME; i += 2)
    p.data[4 + i / 2] = adpcmEncode(s, pcm[i]) | (adpcmEncode(s, pcm[i + 1]) << 4);
  if (xQueueSend(audioPackets, &p, 0) != pdTRUE) audioDropped++;
}

// Reads the mic all the time (so a stream starts with fresh audio and the 100 ms before it),
// cleans it up, and queues ADPCM packets while the phone has the stream on.
void micTask(void *) {
  static int16_t ring[PREROLL][FRAME];
  int16_t buf[FRAME];
  int ringPos = 0;
  bool wasOn = false;
  uint8_t seq = 0;
  Adpcm st;
  int32_t dc = 0;  // DC offset in 1/256 units; the PDM mic sits ~140 above zero
  for (;;) {
    if (mic.readBytes((char *)buf, sizeof(buf)) != sizeof(buf)) continue;
    for (int i = 0; i < FRAME; i++) {
      dc += ((int32_t(buf[i]) << 8) - dc) >> 10;
      buf[i] = constrain((buf[i] - (dc >> 8)) * MIC_GAIN, -32768, 32767);
    }
    bool on = audioOn;
    if (on && !wasOn) {
      seq = 0;
      st = Adpcm();
      for (int k = 0; k < PREROLL; k++) queueFrame(ring[(ringPos + k) % PREROLL], seq++, st);
    }
    // One frame past the stop too: it holds what was said while the stop was on its way.
    if (on || wasOn) queueFrame(buf, seq++, st);
    if (!on && wasOn) {
      AudioPacket end;
      end.len = 1;
      end.data[0] = 0xEE;
      xQueueSend(audioPackets, &end, 0);
    }
    wasOn = on;
    memcpy(ring[ringPos], buf, sizeof(buf));
    ringPos = (ringPos + 1) % PREROLL;
    if (usbRecLen < usbRecWant) {
      size_t n = min((size_t)FRAME, usbRecWant - usbRecLen);
      memcpy(usbRec + usbRecLen, buf, n * 2);
      usbRecLen += n;
    }
  }
}

void audioSendTask(void *) {
  AudioPacket p;
  for (;;) {
    xQueueReceive(audioPackets, &p, portMAX_DELAY);
    if (bleConnected && bleNotify(audioHandle, p.data, p.len, audioRetries)) audioSent++;
  }
}

bool startCamera() {
  esp_camera_deinit();
  camera_config_t c = {};
  c.pin_pwdn = -1;  c.pin_reset = -1;  c.pin_xclk = 10;
  c.pin_sccb_sda = 40;  c.pin_sccb_scl = 39;
  c.pin_d7 = 48;  c.pin_d6 = 11;  c.pin_d5 = 12;  c.pin_d4 = 14;
  c.pin_d3 = 16;  c.pin_d2 = 18;  c.pin_d1 = 17;  c.pin_d0 = 15;
  c.pin_vsync = 38;  c.pin_href = 47;  c.pin_pclk = 13;
  c.xclk_freq_hz = 10000000;
  c.ledc_timer = LEDC_TIMER_0;  c.ledc_channel = LEDC_CHANNEL_0;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_UXGA;  // largest size we switch to; buffers are sized here
  c.jpeg_quality = 10;
  c.fb_count = 2;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) return false;
  sensor_t *s = esp_camera_sensor_get();
  s->set_framesize(s, size);
  // One step under auto exposure: the OV3660 blows out bright windows (and turns them pink),
  // which can't be recovered, while the phone already brightens dark photos.
  s->set_ae_level(s, -1);
  return true;
}

void setSize(framesize_t f) {
  size = f;
  if (!camReady) return;
  esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), f);
  // Frames captured across the switch come out oversized and garbled; flush them.
  for (int i = 0; i < 3; i++) esp_camera_fb_return(esp_camera_fb_get());
}

void info() {
  if (WiFi.status() == WL_CONNECTED)
    Serial.printf("IP %s RSSI %d SSID %s\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(), WiFi.SSID().c_str());
  else Serial.printf("IP none status %d\n", WiFi.status());
  if (camReady) {
    sensor_t *s = esp_camera_sensor_get();
    Serial.printf("CAMERA 0x%x\n", s->id.PID);
  } else Serial.println("CAMERA NOT FOUND");
  Serial.printf("PSRAM %u free of %u, heap %u free\n",
                ESP.getFreePsram(), ESP.getPsramSize(), ESP.getFreeHeap());
  ble_gap_conn_desc d;
  uint8_t tx, rx;
  if (bleConnected && ble_gap_conn_find(connId, &d) == 0 && ble_gap_read_le_phy(connId, &tx, &rx) == 0)
    Serial.printf("BLE interval %.2f ms, MTU %u, PHY tx %u rx %u (2 = 2M)\n",
                  d.conn_itvl * 1.25, server->getPeerMTU(connId), tx, rx);
  Serial.printf("BLE %u connected; last photo %lu bytes in %lu ms, %lu retries\n",
                server->getConnectedCount(), lastBytes, lastSendMs, lastRetries);
  int8_t rssi = 0;
  if (bleConnected) ble_gap_conn_rssi(connId, &rssi);
  Serial.printf("BLE signal %d dBm; buffers %d free of %d; chip %.1f C; up %lu s\n",
                rssi, os_msys_num_free(), os_msys_count(), temperatureRead(), millis() / 1000);
  Serial.printf("BLE link log: %s\n", linkLog.c_str());
  Serial.printf("MIC packets sent %lu, dropped %lu, retries %lu, streaming %d; handles data %u audio %u\n",
                audioSent, audioDropped, audioRetries, audioOn, dataHandle, audioHandle);
}

camera_fb_t *grab() {
  if (!camReady) camReady = startCamera();
  if (!camReady) return nullptr;
  // The camera runs continuously into two buffers and GRAB_LATEST hands over the newest,
  // so a photo is at most one frame (~70 ms) old; no need to throw one away.
  camera_fb_t *fb = esp_camera_fb_get();
  // At 640x480 the driver sometimes misses the end of a frame and packs several whole JPEGs
  // into one buffer (200+ KB instead of 12). Keep the first: it ends at the first EOI marker.
  if (fb) {
    for (size_t i = 2; i + 1 < fb->len; i++) {
      if (fb->buf[i] == 0xFF && fb->buf[i + 1] == 0xD9) { fb->len = i + 2; break; }
    }
  }
  return fb;
}

void photo() {
  camera_fb_t *fb = grab();
  if (!fb) { Serial.println("ERR no photo"); return; }
  Serial.printf("JPG %u\n", fb->len);
  Serial.write(fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void blePhoto() {
  camera_fb_t *fb = grab();
  char head[24];
  int n = snprintf(head, sizeof(head), fb ? "JPG %u" : "ERR no photo", fb ? fb->len : 0);
  lastRetries = 0;
  bleNotify(dataHandle, (uint8_t *)head, n, lastRetries);
  if (!fb) return;
  size_t chunk = min(server->getPeerMTU(connId) - 3, 512);
  unsigned long t = millis();
  for (size_t off = 0; off < fb->len; off += chunk) {
    if (!bleNotify(dataHandle, fb->buf + off, min(chunk, fb->len - off), lastRetries)) break;
  }
  lastSendMs = millis() - t;
  lastBytes = fb->len;
  esp_camera_fb_return(fb);
}

void handleCapture() {
  camera_fb_t *fb = grab();
  if (!fb) { web.send(500, "text/plain", camReady ? "no frame" : "camera not found"); return; }
  web.sendHeader("Cache-Control", "no-store");
  web.send_P(200, "image/jpeg", (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void handleSize() {
  String f = web.arg("f");
  framesize_t s = f == "qvga" ? FRAMESIZE_QVGA : f == "vga" ? FRAMESIZE_VGA :
                  f == "svga" ? FRAMESIZE_SVGA : f == "xga" ? FRAMESIZE_XGA :
                  f == "hd" ? FRAMESIZE_HD : f == "sxga" ? FRAMESIZE_SXGA :
                  f == "uxga" ? FRAMESIZE_UXGA : FRAMESIZE_INVALID;
  if (s == FRAMESIZE_INVALID) { web.send(400, "text/plain", "bad size"); return; }
  setSize(s);
  web.send(200, "text/plain", "ok " + f);
}

// The phone's hotspot first (much faster than the home router); 8 s per attempt, then the other.
void keepJoining() {
  if (!wifiWanted || WiFi.status() == WL_CONNECTED || millis() - lastJoin < 8000) return;
  lastJoin = millis();
  WiFi.disconnect();
  if (tryHotspot) WiFi.begin(WIFI_SSID2, WIFI_PASS2);
  else WiFi.begin(WIFI_SSID, WIFI_PASS);
  tryHotspot = !tryHotspot;
}

void wifiOn() {
  wifiWanted = true;
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);  // power save adds 100+ ms latency per request
  tryHotspot = true;
  lastJoin = millis() - 8000;
}

void wifiOff() {
  wifiWanted = false;
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}

void scanWifi() {
  WiFi.disconnect();  // a scan fails (-2) while a connection attempt is running
  delay(100);
  int n = WiFi.scanNetworks();
  Serial.printf("NET count %d\n", n);
  for (int i = 0; i < n; i++)
    Serial.printf("NET %s ch%d %ddBm\n", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i));
  Serial.println("NET end");
  lastJoin = millis() - 8000;
}

void record(int seconds) {
  size_t want = seconds * 16000;
  int16_t *pcm = (int16_t *)ps_malloc(want * 2);
  if (!pcm) { Serial.println("ERR record failed"); return; }
  usbRecLen = 0;
  usbRec = pcm;
  usbRecWant = want;
  while (usbRecLen < want) delay(10);
  usbRecWant = 0;
  uint32_t bytes = want * 2, rate = 16000, byteRate = 32000;
  uint8_t h[44] = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ',
                   16, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 16, 0,
                   'd', 'a', 't', 'a', 0, 0, 0, 0};
  uint32_t riff = 36 + bytes;
  memcpy(h + 4, &riff, 4);
  memcpy(h + 24, &rate, 4);
  memcpy(h + 28, &byteRate, 4);
  memcpy(h + 40, &bytes, 4);
  Serial.printf("WAV %u\n", 44 + bytes);
  Serial.write(h, 44);
  Serial.write((uint8_t *)pcm, bytes);
  free(pcm);
}

void setup() {
  Serial.begin(115200);  // native USB: the baud rate is ignored
  camReady = startCamera();
  mic.setPinsPdmRx(MIC_CLK, MIC_DATA);
  bool micReady = mic.begin(I2S_MODE_PDM_RX, 16000, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO);
  Serial.println(camReady ? "CAMERA OK" : "CAMERA NOT FOUND");
  Serial.println(micReady ? "MIC OK" : "MIC NOT FOUND");
  startBle();
  // Above the Arduino loop (priority 1), so a photo being sent can't starve the mic.
  if (micReady) xTaskCreatePinnedToCore(micTask, "mic", 8192, nullptr, 3, nullptr, 1);
  xTaskCreatePinnedToCore(audioSendTask, "mic-send", 4096, nullptr, 2, nullptr, 1);
  wifiOn();
  web.on("/capture", handleCapture);
  web.on("/size", handleSize);
  web.begin();
}

void loop() {
  keepJoining();
  if (wifiWanted) web.handleClient();
  char cmd;
  if (xQueueReceive(bleCmds, &cmd, 0)) {
    if (cmd == 'p') blePhoto();
    else command(cmd);
  }
  if (Serial.available()) {
    cmd = Serial.read();
    if (cmd == 'p') photo();
    else if (cmd == 'A' || cmd == 'S') tune(cmd);
    else command(cmd);
  }
}

// USB only, for tuning: 'A' or 'S' then '0'-'4' sets exposure target or saturation to -2..+2.
void tune(char what) {
  int v = -1;
  for (unsigned long t = millis(); v < 0 && millis() - t < 500;) v = Serial.read();
  if (v < '0' || v > '4' || !camReady) return;
  sensor_t *s = esp_camera_sensor_get();
  if (what == 'A') s->set_ae_level(s, v - '2');
  else s->set_saturation(s, v - '2');
  Serial.printf("TUNE %c %d\n", what, v - '2');
}

void command(char cmd) {
  if (cmd == 's') setSize(FRAMESIZE_QVGA);
  if (cmd == 'v') setSize(FRAMESIZE_VGA);
  if (cmd == 'x') setSize(FRAMESIZE_SVGA);
  if (cmd == 'g') setSize(FRAMESIZE_XGA);
  if (cmd == 'u') setSize(FRAMESIZE_UXGA);
  if (cmd == 'i') info();
  if (cmd == 'o') { wifiOff(); Serial.println("WIFI off"); }
  if (cmd == 'n') { wifiOn(); Serial.println("WIFI on"); }
  if (cmd == 'w') scanWifi();
  if (cmd >= '1' && cmd <= '9') record(cmd - '0');
}
