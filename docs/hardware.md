# Hardware reference

Moved out of the README: buying, board notes, form factor and power, flashing.

## Boards

| Board | Role | Why |
|---|---|---|
| **ESP32-CAM + MB adapter** (AI-Thinker, OV2640 2 MP) | Desk prototype, shared with India | Cheap, no soldering, arrives fast, huge example base |
| **XIAO ESP32S3 Sense** | Wearable version | Thumbnail-sized, 8 MB PSRAM, native USB, LiPo charger |

Neither runs a real vision model. They're smart webcams; the **phone (or a cloud
model called from it) is the brain**. This keeps the head-worn part light and cool.

### ESP32-CAM-MB kit (what both of us use)

- **[Aideepen 2PCS ESP32-CAM-MB](https://www.amazon.com/dp/B0948ZFTQZ)** (US, ~$27): board + MB adapter + OV2640, x2.
- **India:** [OLE Electronics](https://olelectronics.in/product/esp32-cam-mb-wifi-bluetooth-board/) (~₹620, same generic kit) or [Aideepen on Amazon.in](https://www.amazon.in/dp/B08P2578LV) (₹2,449, identical but marked up). Robu sells the parts separately: MB adapter ₹119 + board + OV2640.
- Search terms: `ESP32-CAM-MB OV2640`, `ESP32 CAM with MB programmer`. Must include **board + MB adapter + OV2640** (not OV7670/OV3660).
- **Also needed: a micro-USB *data* cable** (not charge-only; the #1 "board not detected" cause).
- Arrives assembled: camera ribbon latched, board on the MB adapter. Plug in.

Gotchas:

- Arduino IDE: board **"AI Thinker ESP32-CAM"**, sketch `#define CAMERA_MODEL_AI_THINKER`.
- USB chip is **CH340**; recent macOS has the driver built in (port shows as `/dev/cu.usbserial-*` or `/dev/cu.wchusbserial-*`). Else install the WCH CH34x driver.
- Upload fails? Hold **IO0** on the adapter, tap **RST**, release. Tap RST again to run.
- Brownouts on weak USB ports; runs hot while streaming. Usable PSRAM is 4 MB.
- microSD ≤16 GB FAT32. Onboard antenna is fine. Optional 160° OV2640 lens: same code.

### XIAO ESP32S3 Sense notes

- Camera board **clicks on** (board-to-board connector); no solder. Attach the U.FL antenna.
- Pins ship **un-soldered**. Only needed for extras (button, buzzer). Options: solder 14 pins
  in ~10 min (USB iron like Pinecil), press-fit into a breadboard temporarily, or clip-on hooks.
  For the smallest wearable, skip headers and solder wires only to pads you use.
- Arduino: board "XIAO_ESP32S3", **PSRAM: OPI PSRAM**, `CAMERA_MODEL_XIAO_ESP32S3`.

### Alternatives considered

| Option | Verdict |
|---|---|
| Phone camera alone | Proves the software with zero hardware. |
| OV7670 / Arducam SPI camera + separate MCU | Camera-only; 8–18 jumper wires, slow. Skip. |
| Raspberry Pi Zero 2 W + camera | On-device models, but bigger and power-hungry. Phase-2 option. |
| Ray-Ban Meta, Brilliant Labs Frame | Polished, closed(ish) pipeline; Frame is open source. Reference designs. |

## Form factor, heat and power

**Wi-Fi is the enemy.** Streaming over Wi-Fi is ~1 W on a tiny board: uncomfortably
hot (~50–60 °C) against a temple, and it drains batteries. Mitigations: send frames
over **USB** instead (ESP32S3 can act as a USB webcam / serial), capture at low res
**every 0.5–2 s or on demand**, and keep an air gap between board and skin.

How Meta does it: ~150 mAh battery in the temples, custom low-power chip, and the
camera is **almost always off** (photo/clip on request, sent over Bluetooth). Their
continuous "Live AI" mode drains in under an hour. The charging case is the real battery.

| Form factor | Power | Link | Verdict |
|---|---|---|---|
| **Glasses clip + one USB-C cable to phone** | Phone (OTG) | USB | **v1 target.** No battery on head, coolest, simplest |
| Glasses clip + power bank | Power bank | Wi-Fi | Phone port free, but Wi-Fi heat |
| Fully wireless (Meta-style) | LiPo in temple | Wi-Fi/BLE | Hardest; needs duty cycling |
| Chest pendant | Any | Any | No face heat; aims with body, not head |

Phone port taken? Use a **USB-C hub with PD passthrough**: board + USB-C audio adapter
(wired earphones) + power bank charging the phone. Cheap Y-splitters are charge-only.
Or just Bluetooth/bone-conduction earphones (~0.05 W, keeps ears open).

Phone battery (~18 Wh), screen off, rough estimates:

| Mode | Total draw | Battery life |
|---|---|---|
| Board on USB, capture on demand | ~1 W | 15+ h |
| 1 fps, on-device model | ~2.5 W | ~7 h |
| Frame every 2 s to cloud model | ~3.5 W | ~5 h |
| Same but board on Wi-Fi + hotspot | ~4.5 W | ~4 h |

The board costs ~4%/h; **how often you analyze frames** dominates. Measure with an
inline USB-C power meter (~$12).

## Flashing from the terminal

Mac has USB-C only: use a **USB-C to micro-USB data cable** into the MB adapter.
Board shows up as `/dev/cu.usbserial-10` (CH340, vendor 0x1A86, built-in macOS driver).

```bash
brew install arduino-cli
arduino-cli config add board_manager.additional_urls \
  https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core update-index && arduino-cli core install esp32:esp32

cd firmware/snap
arduino-cli compile -b esp32:esp32:esp32cam .
arduino-cli upload  -b esp32:esp32:esp32cam -p /dev/cu.usbserial-10 \
  --upload-property upload.speed=115200 .      # ~45 s
arduino-cli monitor -p /dev/cu.usbserial-10 -c baudrate=460800
```

- **Upload at 115200.** Reading flash at 460800/921600 corrupts on this CH340. The
  photo firmware itself talks at 460800 (921600 gets no reply).
- `python3 -m venv .venv-tools && .venv-tools/bin/pip install esptool pyserial pillow requests pyobjc-framework-Cocoa`;
  low-level check: `esptool --port /dev/cu.usbserial-10 chip-id`.
- Our NYC board #1: ESP32-D0WD-V3 rev 3.1, 4 MB flash, MAC `38:3e:51:c2:82:a4`.
  Shipped with Espressif AT firmware (its command port is on other pins, not USB).

**You can't brick it by uploading.** The bootloader lives in ROM; only the 4 MB
flash is rewritten. If an upload hangs: hold IO0, tap RST, release. Real risks are
wrong voltage, shorts, hot-plugging the camera ribbon, and `burn-efuse` (never run).
Factory image is in `backups/` (gitignored); restore with
`esptool --port /dev/cu.usbserial-10 -b 115200 write-flash 0 backups/<file>.bin`.
