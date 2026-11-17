// firmware/snap (same USB commands, block transfer and camera retries) plus Wi-Fi: joins the
// phone hotspot (WIFI_SSID2) or else WIFI_SSID, and serves
//   GET /capture            one current JPEG
//   GET /size?f=<name>      qvga | vga | svga | xga | hd | sxga | uxga
// Extra USB commands: 'i' prints "IP <addr> ..." (or "IP none ..."), 'w' lists visible networks,
// 'o' turns Wi-Fi off (the phone does this when the cable carries the photos), 'n' back on.
// Joining never blocks: photos over USB keep working while no network is in range.

#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include "secrets.h"

const long BAUD = 460800;
const int PWDN = 32;
WebServer server(80);

bool ready = false;
framesize_t size = FRAMESIZE_VGA;
bool wifiWanted = true;
bool tryHotspot = true;  // alternate between the two networks while neither is joined
unsigned long lastJoin = 0;

bool startCamera() {
  esp_camera_deinit();
  pinMode(PWDN, OUTPUT);  // power-cycle the sensor before each try
  digitalWrite(PWDN, HIGH);
  delay(50);
  digitalWrite(PWDN, LOW);
  delay(50);
  camera_config_t c = {};
  c.pin_pwdn = PWDN;  c.pin_reset = -1;  c.pin_xclk = 0;
  c.pin_sccb_sda = 26;  c.pin_sccb_scl = 27;
  c.pin_d7 = 35;  c.pin_d6 = 34;  c.pin_d5 = 39;  c.pin_d4 = 36;
  c.pin_d3 = 21;  c.pin_d2 = 19;  c.pin_d1 = 18;  c.pin_d0 = 5;
  c.pin_vsync = 25;  c.pin_href = 23;  c.pin_pclk = 22;
  c.xclk_freq_hz = 20000000;
  c.ledc_timer = LEDC_TIMER_0;  c.ledc_channel = LEDC_CHANNEL_0;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_UXGA;  // largest size we switch to; buffers are sized here
  c.jpeg_quality = 10;            // lower = better; 10 is near the useful limit for OV2640
  c.fb_count = 1;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) return false;
  esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), size);
  return true;
}

void tryStart(int attempts) {
  for (int i = 0; i < attempts && !ready; i++) {
    ready = startCamera();
    Serial.println(ready ? "READY" : "CAMERA NOT FOUND");
  }
}

void setSize(framesize_t f) {
  size = f;
  if (ready) esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), f);
}

camera_fb_t *freshFrame() {
  tryStart(2);
  if (!ready) return nullptr;
  // The buffered frame may be from long ago; drop it so the photo is current.
  esp_camera_fb_return(esp_camera_fb_get());
  return esp_camera_fb_get();
}

uint16_t crc16(const uint8_t *p, size_t n) {
  uint16_t c = 0xFFFF;
  while (n--) {
    c ^= *p++;
    for (int i = 0; i < 8; i++) c = c & 1 ? (c >> 1) ^ 0xA001 : c >> 1;
  }
  return c;
}

void sendBlocks(const uint8_t *buf, size_t len) {
  size_t off = 0;
  int resends = 0;
  while (off < len) {
    size_t n = min((size_t)1024, len - off);
    uint16_t c = crc16(buf + off, n);
    Serial.write(buf + off, n);
    Serial.write((uint8_t *)&c, 2);
    int r = -1;
    for (unsigned long t = millis(); millis() - t < 1000 && r < 0;) r = Serial.read();
    if (r == 'k') { off += n; resends = 0; }
    else if (r != 'r' || ++resends > 8) return;  // host gave up or went away
  }
}

void handleCapture() {
  camera_fb_t *fb = freshFrame();
  if (!fb) { server.send(500, "text/plain", ready ? "no frame" : "camera not found"); return; }
  server.sendHeader("Cache-Control", "no-store");
  server.send_P(200, "image/jpeg", (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void handleSize() {
  String f = server.arg("f");
  framesize_t s = f == "qvga" ? FRAMESIZE_QVGA : f == "vga" ? FRAMESIZE_VGA :
                  f == "svga" ? FRAMESIZE_SVGA : f == "xga" ? FRAMESIZE_XGA :
                  f == "hd" ? FRAMESIZE_HD : f == "sxga" ? FRAMESIZE_SXGA :
                  f == "uxga" ? FRAMESIZE_UXGA : FRAMESIZE_INVALID;
  if (s == FRAMESIZE_INVALID) { server.send(400, "text/plain", "bad size"); return; }
  setSize(s);
  server.send(200, "text/plain", "ok " + f);
}

// The phone's hotspot is preferred: photos reach the phone in ~0.3 s there, while through the
// home router they stall for seconds (docs/benchmarks.md). Each attempt gets 8 s, then the other.
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

void setup() {
  Serial.begin(BAUD);
  tryStart(5);
  wifiOn();
  server.on("/capture", handleCapture);
  server.on("/size", handleSize);
  server.begin();
}

void loop() {
  static unsigned long lastTry = 0;
  if (!ready && millis() - lastTry > 2000) {
    lastTry = millis();
    tryStart(1);
  }
  keepJoining();
  if (wifiWanted) server.handleClient();
  if (!Serial.available()) return;
  char cmd = Serial.read();
  if (cmd == 's') setSize(FRAMESIZE_QVGA);
  if (cmd == 'v') setSize(FRAMESIZE_VGA);
  if (cmd == 'x') setSize(FRAMESIZE_SVGA);
  if (cmd == 'g') setSize(FRAMESIZE_XGA);
  if (cmd == 'u') setSize(FRAMESIZE_UXGA);
  if (cmd == 'o') { wifiOff(); Serial.println("WIFI off"); }
  if (cmd == 'n') { wifiOn(); Serial.println("WIFI on"); }
  if (cmd == 'i') {
    if (WiFi.status() == WL_CONNECTED)
      Serial.printf("IP %s RSSI %d SSID %s\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(), WiFi.SSID().c_str());
    else Serial.printf("IP none status %d\n", WiFi.status());
  }
  if (cmd == 'w') {  // list visible 2.4 GHz networks
    WiFi.disconnect();  // a scan fails (-2) while a connection attempt is running
    delay(100);
    int n = WiFi.scanNetworks();
    Serial.printf("NET count %d\n", n);
    for (int i = 0; i < n; i++)
      Serial.printf("NET %s ch%d %ddBm\n", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i));
    Serial.println("NET end");
    lastJoin = millis() - 8000;
  }
  if (cmd == 'p' || cmd == 'P') {
    camera_fb_t *fb = freshFrame();
    if (!fb) { Serial.println(ready ? "ERR no frame" : "ERR camera not found"); return; }
    Serial.printf("JPG %u\n", fb->len);
    if (cmd == 'P') sendBlocks(fb->buf, fb->len);
    else Serial.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
}
