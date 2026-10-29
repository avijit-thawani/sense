// Waits for the letter 'p' over USB, then replies "JPG <length>\n" followed by one JPEG.
// 's' switches to 320x240, 'v' to 640x480 (default), 'x' to 800x600.

#include "esp_camera.h"

const long BAUD = 460800;

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
  c.frame_size = FRAMESIZE_SVGA;  // largest size we switch to; buffers are sized here
  c.jpeg_quality = 10;            // lower = better; 10 is near the useful limit for OV2640
  c.fb_count = 1;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) Serial.println("CAMERA NOT FOUND");
  else {
    esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), FRAMESIZE_VGA);
    Serial.println("READY");
  }
}

void loop() {
  if (!Serial.available()) return;
  char cmd = Serial.read();
  if (cmd == 's') esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), FRAMESIZE_QVGA);
  if (cmd == 'v') esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), FRAMESIZE_VGA);
  if (cmd == 'x') esp_camera_sensor_get()->set_framesize(esp_camera_sensor_get(), FRAMESIZE_SVGA);
  if (cmd == 'p') {
    // The buffered frame may be from long ago; drop it so the photo is current.
    esp_camera_fb_return(esp_camera_fb_get());
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) { Serial.println("ERR no frame"); return; }
    Serial.printf("JPG %u\n", fb->len);
    Serial.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
}
