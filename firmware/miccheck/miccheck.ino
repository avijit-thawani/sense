// XIAO ESP32S3 Sense: records from the built-in PDM mic (on the camera board) over USB.
// Send a digit '1'-'9' to record that many seconds, or 'r' for 3 s.
// Replies "WAV <length>\n" followed by a 16 kHz, 16-bit mono WAV file.

#include "ESP_I2S.h"

const int MIC_CLK = 42, MIC_DATA = 41;
const int RATE = 16000;

I2SClass mic;

void setup() {
  Serial.begin(115200);  // native USB: the baud rate is ignored
  mic.setPinsPdmRx(MIC_CLK, MIC_DATA);
  if (!mic.begin(I2S_MODE_PDM_RX, RATE, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO))
    Serial.println("MIC NOT FOUND");
  else Serial.println("READY");
}

void loop() {
  if (!Serial.available()) return;
  char cmd = Serial.read();
  int seconds = cmd == 'r' ? 3 : (cmd >= '1' && cmd <= '9') ? cmd - '0' : 0;
  if (!seconds) return;
  // The PDM filter pops for the first ~100 ms after idling; throw that away.
  char junk[3200];
  mic.readBytes(junk, sizeof(junk));
  size_t len = 0;
  uint8_t *wav = mic.recordWAV(seconds, &len);
  if (!wav) { Serial.println("ERR record failed"); return; }
  Serial.printf("WAV %u\n", len);
  Serial.write(wav, len);
  free(wav);
}
