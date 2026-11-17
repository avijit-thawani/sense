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
| Glasses clip + one USB-C cable to phone | Phone (OTG) | USB | **Test rig** (works since Oct 31). Not a product: users won't accept the cable |
| Glasses clip + power bank | Power bank | Wi-Fi | Phone port free, but Wi-Fi heat and hotspot drain |
| **Clip-on, LiPo, Bluetooth** | 300–500 mAh LiPo | BLE | **Product direction** ([roadmap.md](roadmap.md)): XIAO, camera warm only while in use; audio via phone to a Bluetooth headset |
| Fully wireless frame (Meta-style) | LiPo in temple | Wi-Fi/BLE | Later: 3D-printed frame once the clip-on has been worn |
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

## Wearable v2: what to buy

Prices checked Nov 1 2026 (US unless marked; "~" means a typical price, not checked). Order
in stages; why each stage exists is in [roadmap.md](roadmap.md#track-b-hardware-in-small-steps).

**What the XIAO Sense box already has:** the board, the clip-on camera board (camera, mic, microSD
slot) and an antenna. **Not included:** battery (only a charger chip and two pads underneath),
speaker (none; audio goes phone → headset), a usable button (only tiny BOOT/RESET), a case.

**Stage 1, order now: three carts. ~$130 of parts (including headphones) plus ~$160 of
one-time tools.**

*Cart 1: Seeed (or Amazon, if you want it faster)*

| Item | Price | Notes |
|---|---|---|
| [XIAO ESP32S3 Sense](https://www.seeedstudio.com/XIAO-ESP32S3-Sense-p-5639.html), 1 more | $19.95 ([ameriDroid](https://ameridroid.com/products/xiao-esp32-s3-sense)), $23.99 [Amazon](https://www.amazon.com/dp/B0C69FFVHH) | One already ordered; the second goes in the clip-on so the first stays on the desk. India: ₹1,575 Robocraze, ₹2,099 Robu |
| [OV5640 autofocus camera for XIAO](https://www.seeedstudio.com/OV5640-Camera-for-XIAO-ESP32S3-Sense-With-Heat-Sink-p-5739.html) | $11.99 | Seeed part 114993115, also at DigiKey. Skip if your XIAO arrives with an OV5640 already (new stock ships OV3660 or OV5640) |

*Cart 2: Adafruit part numbers, but **order them from DigiKey***. Adafruit drops its cheap
USPS shipping as soon as a LiPo is in the cart (UPS only, ~$26 to NYC). DigiKey stocks the
same Adafruit parts, plus the XIAO and the OV5640 (Seeed 114993115), for one flat $8.49 ground
shipment. Without batteries, Adafruit itself ships USPS for ~$6. In NYC: Micro Center Brooklyn
(850 3rd Ave) carries some Adafruit/Seeed parts and soldering tools, but stock is patchy, so check
the store page first; NYC Resistor and Hack Manhattan open nights have parts walls and people who
will help with a first solder joint.

| Item | Qty | Price | Notes |
|---|---|---|---|
| [LiPo 3.7 V 400 mAh, JST-PH](https://www.adafruit.com/product/3898) | 2 | $6.95 each | 36×17×7.8 mm, 8 g, protection circuit built in. Alternative on Amazon: EEMB LP502030 (250 mAh, 32×20.5×5.3 mm, 5 g, JST-PH); check its polarity before use, since EEMB plugs are often reversed. The XIAO charges at a fixed 110 mA, so use cells of 110 mAh or more |
| [JST-PH 2-pin cable, male header, 200 mm](https://www.adafruit.com/product/3814) | 3 | $0.75 each | The battery comes with its plug; the XIAO has only bare pads. Solder this lead's red to **BAT+ (pad farther from USB)**, black to **BAT− (nearer USB)**, trim to length; the battery then clicks in and out. Not #261: that's the same half as the battery's plug |
| [Tactile buttons, 6 mm slim, 20-pack](https://www.adafruit.com/product/1489) | 1 | $4.95 | The trigger: one pin to a GPIO, one to GND |
| [Silicone wire, 30 AWG, 2 m](https://www.adafruit.com/product/2051) | red, black, blue, white | $0.75 each | Thin and floppy, good for wearables |
| [USB-C power meter](https://www.adafruit.com/product/6271) | 1 | $14.95 | Board's current draw; sets battery size |
| *Optional, saves a second shipment:* [VL53L1X distance sensor](https://www.adafruit.com/product/3967), [coin vibration motor](https://www.adafruit.com/product/1201) ×3 | | $14.95, $1.95 each | Stage 2 parts (below) |

*Cart 3: Amazon / Pine64 (tools, audio, remote)*

| Item | Price | Notes |
|---|---|---|
| [Pinecil V2 iron](https://pine64.com/product/pinecil-smart-mini-portable-soldering-iron/) | $25.99 Pine64, $39.99 [Amazon](https://www.amazon.com/dp/B096X6SG13) | Runs off the MacBook's USB-C charger. Add a stand (~$8, `Pinecil stand`) |
| [Shokz OpenMove](https://shokz.com/products/openmove) | $54.95–59.96 (refurb $44.96) | The speaker and mic for now |
| [CamKix Bluetooth shutter remote](https://www.amazon.com/dp/B00PJSIIES) | $5.79 | The hands-free button. Shows up on the phone as a volume key, which the app already uses for Describe |
| Kester 63/37 solder, 0.031" (0.8 mm) | ~$10 | Leaded flows easiest; wash hands after |
| MG Chemicals 8341 no-clean flux pen | ~$10 | |
| Hakko 599B brass tip cleaner | ~$9 | |
| Hakko CHP-170 flush cutters | ~$7 | |
| Wire stripper for 20–30 AWG | ~$12 | Must go down to 30 AWG; most stop at 22 |
| Fine-tip tweezers (ESD) | ~$8 | |
| Helping hands with magnifier | ~$20 | |
| Multimeter (AstroAI or KAIWEETS) | ~$14 | Continuity and polarity checks before plugging in a battery |
| Heat-shrink assortment + Kapton tape | ~$15 | |
| Digital caliper (e.g. Neiko 01407A) | ~$15 | Measure the glasses arm before printing a clip |
| Safety glasses | ~$5 | Clipped wire ends fly |

"~" prices are typical, not checked; search the name. The XIAO can't read its own battery
level; that needs two resistors from the battery to a pin, which can wait.

**Stage 2, after the Bluetooth test passes (~$30):**

| Item | Price | Why |
|---|---|---|
| [VL53L1X distance sensor](https://www.adafruit.com/product/3967) | $14.95 Adafruit ([Pololu](https://www.pololu.com/product/3415) $22.95) | 4 cm–4 m, ~20 mA, I²C. Head-height obstacle beeps without the cloud |
| [Coin vibration motor](https://www.adafruit.com/product/1201) ×3 | $1.95 each | 10 mm, ~60 mA at 3 V. Drive through a small transistor (2N2222 + diode), not straight from a pin |
| 3D print of the omiGlass clip-on (`openglass-old/`) | a few $ per part + shipping | Craftcloud, JLC3DP or a local shop; PLA/PETG for a first fit test |

**Not yet:** speaker + amplifier (MAX98357A): audio goes through the headset. ESP32-P4 or Pi
Zero 2 W camera boards: only if the OV5640 still limits answers.

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
