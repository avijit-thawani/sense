# Roadmap

Where `sense` is, what's next, and why. Last updated Nov 1 2026. What people actually said
lives in [feedback.md](feedback.md); parts and prices in [hardware.md](hardware.md#wearable-v2-what-to-buy).

## Where we are (Nov 1 2026)

| Rig | Photo to phone | Tap to first word | Status |
|---|---|---|---|
| ESP32-CAM on the phone's hotspot | 0.24–0.45 s | 1.58 s | works, used for most walks |
| ESP32-CAM on a USB cable to the phone | 1.0–1.4 s (block transfer, 1024×768) | 2.14 s | works since Oct 31, 6/6 taps |
| Bapick M08T glasses over Bluetooth | 3.9–6.4 s, at 512×288 | 4.8–8.4 s | works from the Mac only; demo, not a path |

Ask mode (hold, speak, let go) is ~1.45 s to the first word on both the cable and the hotspot,
because the photo is taken while you're still speaking.

XIAO ESP32S3 Sense arrived Nov 1; desk results under B0 below. Not yet tried: Bluetooth photos
to the phone, a battery on the head, any non-voice feedback, obstacle warnings, find mode,
testing with blind or low-vision users.

## Two tracks, run in parallel

Software and hardware are separate problems with different speeds. The current rig (ESP32-CAM
plus phone, cable or hotspot) is good enough for every software question; the wearable
is a separate project that shouldn't block it.

- **Track A, software, AI and UI:** build on today's rig. Each change gets a replay and a video
  before anyone walks with it. Fast: hours per iteration.
- **Track B, hardware UX:** the thing a blind person would actually wear. Slow: days per
  iteration, waits on shipping and soldering.

Both tracks answer to the same question: what do blind and low-vision users need? Neither has
been tested with one yet. That is the biggest gap ([feedback.md](feedback.md)).

## Track A: software on today's rig

Ordered by what's cheapest to learn from. All run on the current ESP32-CAM.

1. **Non-voice feedback.** Short sounds and vibrations for listening, thinking, answer
   coming, camera lost and error, so the user always knows what state the device is in. Phone
   vibration first. This is also the base for obstacle beeps and find mode.
2. **Find mode** (collaborator's idea). Type or say an object ("keys", "blue mug"), then the
   app takes photos continuously and beeps faster as the object gets closer to centre, then speaks
   "keys, on the table, slightly left" once. Two ways to build it:
   - cloud model on each frame: ~1 photo/s at ₹0.01–0.04 each, works for any object;
   - on-device detector on the phone: 10+ frames/s and free, but only for the classes it knows.
     Start with cloud; measure how often it hits.
3. **Obstacle warnings, camera only** (collaborator's idea). Live mode at ~1 frame/s, with a
   prompt that only answers "obstacle at head or chest height, how far, which side", as a beep
   or vibration rather than words. Honest limit: at ~1–2 s per photo-to-answer you've walked
   1–2 m before it beeps. The research says head-height obstacles are the real need, which the
   cane misses. If the camera is too slow, that's the case for a distance sensor (Track B2).
4. **Reading text mode.** The top request in every study we read (signs, labels, medicine,
   bus numbers). Mostly prompt work plus a "read everything" trigger.
5. **Hands-free trigger.** A ₹150 / $8 Bluetooth selfie remote as the button, screen off.
6. **Accessibility pass.** TalkBack, speech rate, Hindi. (Listed in README "Future directions".)
7. **Housekeeping from the cable work.** 640×480 over the cable (should bring tap-to-voice to
   ~1.5 s); Wi-Fi fallback when the cable link fails; fix "Too dark to see." on pale walls.

Collaborator in India runs the same rig in about a week ([GETTING_STARTED.md](../GETTING_STARTED.md)).
His first walks give the second set of real sessions.

## Track B: hardware, in small steps

The collaborator's point is right for the product: the cable can't stay, so it's Bluetooth
plus a battery. The order below tests the riskiest unknowns first and buys the least.

**B0. XIAO on the desk (when it arrives, no soldering).**
- Mic (the main option): `firmware/miccheck` + `tools/mic.py`. Is it good enough for
  speech-to-text, indoors and on a street? Then send it over Bluetooth alongside the photo.
- Photo over the XIAO's native USB: no CH340 chip, so it should be far faster than today's 1 s.
- **Photo over Bluetooth to the phone: the deciding test.** ESP32-S3 BLE does ~90 KB/s
  (Espressif's reference with 2M PHY is ~170 KB/s), so a 25 KB photo should take ~0.3 s, not
  the glasses' 4–6 s. Their slowness was their protocol (1 KB per request, ~170 ms each), not
  Bluetooth. Pass: 1024×768 in under 0.5 s on the Pixel.
- Current draw with the camera warm and idle, during a photo, and with the camera off (USB-C power
  meter). This sets the battery size.

Results so far (Nov 1, `firmware/xiao`, `tools/xiao_check.py`, `tools/ble_check.py`):
- Board: ESP32-S3, 8 MB flash, 8 MB PSRAM. Camera is an **OV3660** (3 MP), not the OV2640.
- Photo over native USB to the Mac: **31 ms** for 1024×768 (25 KB), 69 ms for 1600×1200, versus
  ~1 s on the ESP32-CAM's CH340. Two camera fixes got there: at 640×480 the driver sometimes packs
  several JPEGs into one 200 KB buffer (the firmware now cuts at the first end marker), and at a
  20 MHz camera clock 640×480 and 800×600 stalled up to 750 ms (10 MHz is steady; the newest of two
  buffered frames is used, at most ~70 ms old).
- Mic: the Mac's `say` reading "What is in front of me? Read the label on this bottle." from
  across the desk was transcribed word-for-word by local whisper (base.en), though the level was
  low (peak −37 dBFS). Still to do: a real voice, outdoors, and probably a software gain.
- Bluetooth to the **Mac**: photos arrive intact at ~25 KB/s (1024×768 in ~1.05 s). The link is
  2M PHY with a 517-byte MTU, but macOS keeps a 30 ms connection interval and ignores requests
  for 7.5–15 ms, which caps it.
- Bluetooth to the **Pixel 9a** (the app's new Bluetooth link): every photo intact, auto-reconnects
  after the board restarts. 15 ms interval, 2M PHY, 517 MTU. **1024×768 in 0.71 s on a freshly
  booted board, 1.1–2.4 s after it has run a while**; 640×480 in 0.3–0.4 s when fresh. Fails the
  0.5 s bar at 1024×768. The board is always waiting on the radio (~40 KB/s at best, about one
  512-byte notification per 15 ms connection event). Ruled out: Wi-Fi on either side, other
  Bluetooth devices, a second connection, 244-byte chunks (worse), leaked stack buffers (36/36
  free after every run).
- **Cause found: weak signal.** The board hears the phone at −81 to −88 dBm on the same desk
  (expected −40 to −60), so packets are lost and resent. On the slower but more robust 1M PHY,
  1024×768 is a steady 0.72–0.77 s; on 2M it swung 0.7–5.5 s with timeouts. Firmware uses 1M for
  now. Suspect: the XIAO's external antenna (it has none on the board) not clipped on. Espressif
  measures ~170 KB/s on 2M PHY at 7.5 ms with a good signal, which would put 1024×768 near 0.2 s.
- **XIAO mic over Bluetooth works (Nov 1)** and is the app's default for hold-to-ask. Board streams
  IMA ADPCM (4:1, 8 KB/s) from the press, with 100 ms from before it; phone decodes it into
  Android's on-device speech-to-text. Mac test: 0 gaps, word-perfect whisper transcript, peak
  −18 dBFS after ×8 gain. Pixel dry asks: first audio 96–143 ms after the press, transcript 188 ms
  after release (phone mic: 259 ms), photo unaffected (0.79 s), 0 packets dropped. Transcript
  quality not judged yet: tests ran with a podcast playing. Next: real voice, indoors and outdoors.
- The app no longer asks Android for "high priority": that moved the board's 7.5 ms request back
  to 15 ms. Chip runs at 62–65 °C with the camera always on; stop it between photos on battery.

**B1. Clip-on v1 (light soldering).** XIAO + small LiPo + one button in a small box that clips
on the arm of any glasses. Start from omiGlass's old clip-on (`openglass-old/`, ~41×24×36 mm)
printed by a shop. **No speaker on the device:** answers play from the phone over Bluetooth to
bone-conduction headphones (or the Bapick glasses' speakers for now). **Mic: the XIAO's**, sent
to the phone over Bluetooth with the photos. It sits by the mouth and works with any headphones.
Fallback if B0 shows it's too poor: the headset's mic (needs the app to switch it into call
mode, which lowers audio quality while asking).

**B2. Obstacle sensor and haptics (only if A3 shows the camera is too slow).** A tiny laser
distance sensor (VL53L1X, 4 m, ~20 mA) next to the camera, and a coin vibration motor. The
board itself beeps or buzzes at head-height obstacles with no phone round trip, in
milliseconds. This is what Torchit's Saarthi does with sonar on a cane, aimed at head height
instead.

**B3. Better pictures (only if image quality limits answers).** OV5640 autofocus camera ($12,
clicks onto the XIAO); then, if still short of the Bapick's Sony sensor, a board with a real
image processor (ESP32-P4 with a MIPI camera, or Raspberry Pi Zero 2 W). Bigger and hungrier;
research only for now.

**B4. Integrated frame (after B1 has been worn by real users).** 3D-printed glasses from omiGlass's
editable files, everything in one arm. Worth it for looks and weight, not needed for function.

### Why the speaker doesn't need custom glasses

The Bapick's speakers are loud to people nearby, and a cheap 15 mm hobby speaker in a clip-on
would be worse. Meta's quiet open-ear speakers are a custom acoustic design that cancels the
sound leaking outward. That's the recent tech, and a 3D-printed frame doesn't give you it.
Leakage is a property of the speaker, not the frame.

The off-the-shelf answer is **bone-conduction headphones** (Shokz OpenMove, ~$55). They leave the ears
open for traffic, leak little sound, have a mic, and connect to the phone like any Bluetooth
headset. They're also popular with blind travellers for exactly that reason. That takes audio off
our hardware entirely: the head unit is camera, button, battery and radio. A frame-integrated
speaker becomes a later product decision, not a prototype blocker.

## Decisions and open questions

| Question | Current answer | What settles it |
|---|---|---|
| Cable, hotspot or Bluetooth? | Cable and hotspot for Track A; Bluetooth for the product | B0 BLE photo time on the phone |
| Speaker on the device? | No: phone to any headphones the user has; Shokz to keep ears free | Testers' opinion of a separate headset |
| Which mic? | The XIAO's (decided Nov 1); headset mic as fallback | B0 mic test: speech-to-text accuracy, walking outdoors |
| Clip-on or full frame? | Clip-on first (also lets low-vision users keep their own lenses) | B1 worn by testers |
| Camera good enough? | Unknown: OV2640 is noisy in dim rooms | Same scenes, OV2640 vs OV5640 vs Bapick, scored by the evals |
| Obstacle: camera or sensor? | Try camera first (free) | A3 warning time vs walking speed |
| Who are the first testers? | Not set | Blind/low-vision interns, via the collaborator |
