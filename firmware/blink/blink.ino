// Blinks the small red LED on the back of the ESP32-CAM and prints over USB.
// Red LED is on GPIO 33 and is active-low: LOW turns it on.

const int RED_LED = 33;
int count = 0;

void setup() {
  Serial.begin(115200);
  pinMode(RED_LED, OUTPUT);
}

void loop() {
  digitalWrite(RED_LED, LOW);
  Serial.printf("hello from ESP32-CAM, blink %d\n", ++count);
  delay(500);
  digitalWrite(RED_LED, HIGH);
  delay(500);
}
