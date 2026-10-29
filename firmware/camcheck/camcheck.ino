// Tries to start the camera once a second and reports whether it is attached.

#include "esp_camera.h"

bool startCamera() {
  camera_config_t c = {};
  c.pin_pwdn = 32;  c.pin_reset = -1;  c.pin_xclk = 0;
  c.pin_sccb_sda = 26;  c.pin_sccb_scl = 27;
  c.pin_d7 = 35;  c.pin_d6 = 34;  c.pin_d5 = 39;  c.pin_d4 = 36;
  c.pin_d3 = 21;  c.pin_d2 = 19;  c.pin_d1 = 18;  c.pin_d0 = 5;
  c.pin_vsync = 25;  c.pin_href = 23;  c.pin_pclk = 22;
  c.xclk_freq_hz = 20000000;
  c.ledc_timer = LEDC_TIMER_0;  c.ledc_channel = LEDC_CHANNEL_0;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_QVGA;
  c.jpeg_quality = 12;
  c.fb_count = 1;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  return esp_camera_init(&c) == ESP_OK;
}

void setup() {
  Serial.begin(115200);
}

void loop() {
  if (!startCamera()) {
    Serial.println("CAMERA NOT FOUND");
  } else {
    sensor_t *s = esp_camera_sensor_get();
    camera_fb_t *fb = esp_camera_fb_get();
    Serial.printf("CAMERA OK: sensor id 0x%x, test photo %u bytes\n",
                  s->id.PID, fb ? fb->len : 0);
    if (fb) esp_camera_fb_return(fb);
    esp_camera_deinit();
  }
  delay(2000);
}
