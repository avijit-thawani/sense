// Same USB protocol as firmware/snap, plus Wi-Fi: joins WIFI_SSID and serves
//   GET /capture            one current JPEG
//   GET /size?f=<name>      qvga | vga | svga | xga | hd | sxga | uxga
// Over USB: 'p' photo, 's'/'v'/'x' size, 'g' 1024x768, 'u' 1600x1200, 'i' prints "IP <addr>",
// 'w' lists visible networks.

#include "esp_camera.h"
#include <WiFi.h>
#include <WiFiMulti.h>
#include <WebServer.h>
#include "secrets.h"

const long BAUD = 460800;
WebServer server(80);
WiFiMulti wifi;  // joins whichever known network is strongest

void setSize(framesize_t f) { esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), f); }

camera_fb_t *freshFrame() {
  // The buffered frame may be from long ago; drop it so the photo is current.
  esp_camera_fb_return(esp_camera_fb_get());
  return esp_camera_fb_get();
}

void handleCapture() {
  camera_fb_t *fb = freshFrame();
  if (!fb) { server.send(500, "text/plain", "no frame"); return; }
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

void setup() {
  Serial.begin(BAUD);
  camera_config_t c = {};
  c.pin_pwdn = 32;  c.pin_reset = -1;  c.pin_xclk = 0;
  c.pin_sccb_sda = 26;  c.pin_sccb_scl = 27;
  c.pin_d7 = 35;  c.pin_d6 = 34;  c.pin_d5 = 39;  c.pin_d4 = 36;
  c.pin_d3 = 21;  c.pin_d2 = 19;  c.pin_d1 = 18;  c.pin_d0 = 5;
  c.pin_vsync = 25;  c.pin_href = 23;  c.pin_pclk = 22;
  c.xclk_freq_hz = 20000000;
  c.ledc_timer = LEDC_TIMER_0;  c.ledc_channel = LEDC_CHANNEL_0;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_UXGA;  // largest size we switch to; buffers are sized here
  c.jpeg_quality = 10;
  c.fb_count = 1;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) { Serial.println("CAMERA NOT FOUND"); return; }
  setSize(FRAMESIZE_VGA);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);  // power save adds 100+ ms latency per request
  wifi.addAP(WIFI_SSID, WIFI_PASS);
  wifi.addAP(WIFI_SSID2, WIFI_PASS2);
  for (int i = 0; i < 20 && wifi.run(500) != WL_CONNECTED; i++) {}
  server.on("/capture", handleCapture);
  server.on("/size", handleSize);
  server.begin();
  Serial.println("READY");
}

void loop() {
  static unsigned long lastCheck = 0;
  if (millis() - lastCheck > 3000) {  // rejoin, or switch networks, if the link dropped
    lastCheck = millis();
    if (WiFi.status() != WL_CONNECTED) wifi.run(500);
  }
  server.handleClient();
  if (!Serial.available()) return;
  char cmd = Serial.read();
  if (cmd == 's') setSize(FRAMESIZE_QVGA);
  if (cmd == 'v') setSize(FRAMESIZE_VGA);
  if (cmd == 'x') setSize(FRAMESIZE_SVGA);
  if (cmd == 'g') setSize(FRAMESIZE_XGA);
  if (cmd == 'u') setSize(FRAMESIZE_UXGA);
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
    wifi.run(500);
  }
  if (cmd == 'p') {
    camera_fb_t *fb = freshFrame();
    if (!fb) { Serial.println("ERR no frame"); return; }
    Serial.printf("JPG %u\n", fb->len);
    Serial.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
}
