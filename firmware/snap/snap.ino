// Waits for the letter 'p' over USB, then replies "JPG <length>\n" followed by one JPEG.
// 'P' is the same photo in 1 KB blocks, each followed by its CRC-16/MODBUS (2 bytes, little
// endian); the host answers 'k' for the next block or 'r' to resend. The phone uses it: its
// USB reads leave gaps in which the CH340 drops a few bytes per photo.
// 's' switches to 320x240, 'v' to 640x480 (default), 'x' to 800x600, 'g' to 1024x768,
// 'u' to 1600x1200 (same letters as firmware/snapwifi, which the phone app sends).
// The camera sometimes isn't detected at power-up (loose ribbon, weak USB power), so the
// board keeps retrying instead of giving up; until then a photo gets "ERR camera not found".

#include "esp_camera.h"

const long BAUD = 460800;
const int PWDN = 32;

bool ready = false;
framesize_t size = FRAMESIZE_VGA;

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

void setup() {
  Serial.begin(BAUD);
  tryStart(5);
}

void loop() {
  static unsigned long lastTry = 0;
  if (!ready && millis() - lastTry > 2000) {
    lastTry = millis();
    tryStart(1);
  }
  if (!Serial.available()) return;
  char cmd = Serial.read();
  if (cmd == 's') setSize(FRAMESIZE_QVGA);
  if (cmd == 'v') setSize(FRAMESIZE_VGA);
  if (cmd == 'x') setSize(FRAMESIZE_SVGA);
  if (cmd == 'g') setSize(FRAMESIZE_XGA);
  if (cmd == 'u') setSize(FRAMESIZE_UXGA);
  if (cmd == 'p' || cmd == 'P') {
    tryStart(2);
    if (!ready) { Serial.println("ERR camera not found"); return; }
    // The buffered frame may be from long ago; drop it so the photo is current.
    esp_camera_fb_return(esp_camera_fb_get());
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) { Serial.println("ERR no frame"); return; }
    Serial.printf("JPG %u\n", fb->len);
    if (cmd == 'P') sendBlocks(fb->buf, fb->len);
    else Serial.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
}
