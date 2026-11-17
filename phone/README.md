# phone: Android app

Board on the phone's USB-C port (power + photos over USB serial, CH340 at 460800 baud), Gemini over
the phone's internet, Android text-to-speech to whatever audio is active (Bluetooth earphones).
Same pipeline as `tools/describe.py`: 640×480, `auto` brightening, items mode, speech-paced photos.

Screen: one big **Describe** button (or the **volume-down** key): beep + buzz, a fresh photo at
1024×768, the hill-climbed prompt (`tools/prompts/moment_v4.txt`, bundled at build time), and the
whole answer spoken at once (~1.5 s on the hotspot). **Hold** the button or volume-down to ask:
a low tone, the photo is taken at once, on-device speech-to-text listens while you hold; on release
the question + photo go to the model with `tools/prompts/ask_app_v1.txt` (`ask_prompt` in config) and
the answer is spoken. Logged as `mode: "ask"` with the `question`. Debug: `"record_question_audio": true`
(Android 13+) records the mic itself, pipes the same audio to speech-to-text, and saves `<photo>.wav`
(16 kHz mono) next to the photo, logged as `question_audio` + `question_audio_start_at`. Small **Live** (the old continuous items mode,
640×480) and **Reconnect** buttons; last answer, photo and log below. Plugging the board in opens
the app and grants USB access. Needs a USB-C to micro-USB *data* cable (or OTG adapter), or the
board on the phone's hotspot (`firmware/snapwifi` prefers the hotspot over home Wi-Fi).

**Two boards, three links** (Settings → Camera → Photos over):

| | ESP32-CAM (`firmware/snapwifi`) | XIAO ESP32S3 Sense (`firmware/xiao`) |
|---|---|---|
| USB cable | CH340, checked 1 KB blocks | native USB, one piece; told apart by USB vendor (Espressif 303a) |
| Hotspot | `/capture`, `/size` | same pages, same `secrets.h` |
| Bluetooth | not built (no board here to flash) | BLE, "sense-xiao"; Wi-Fi switched off so it doesn't slow the radio |

A fourth choice, **Glasses**, uses the camera glasses' own camera over Bluetooth (`GlassesCamera.kt`,
no board at all): 1.8 s per photo at 160×90, 2.5 s at 320×180 ([docs/glasses.md](../docs/glasses.md)).
Its size is the `glasses_size` setting (0–6, default 1), not the boards' size letters; set it with
`adb shell am start -n com.sense.app/.MainActivity --ei glasses_size 0` (also `--es link glasses`).

**Microphone for questions** (Settings → Camera): *Camera* (default) streams the XIAO's mic over
Bluetooth into the same on-device speech-to-text (Android 13+); *Phone* uses the phone's mic. The
camera mic quietly falls back to the phone's on USB, hotspot, or older firmware (logged as a
`mic` event); each ask row in `session.jsonl` says which mic it used (`"mic"`). With
"record_question_audio" on, the camera-mic audio is saved as `<photo>.wav` too.

Free test of the whole ask path (photo, mic, speech-to-text; no model call):
`adb shell am start -n com.sense.app/.MainActivity --ei ask_dry 4 --es mic camera`, then read the
`dry_ask` rows in the event log.

**Test photos** (Settings → Camera) times 5 photos at each size over the current link with no
model calls, and saves them to `files/bench/<link>/`. Same from the Mac:
`adb shell am start -n com.sense.app/.MainActivity --ei bench 5`, results in `adb logcat -s sense`.

## Build and install (Mac)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd phone && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: `brew install openjdk@17 gradle`, Android cmdline-tools in `~/Library/Android/sdk`
(`platforms;android-35`, `build-tools;35.0.0`). `local.properties` points at the SDK (gitignored).

## Config (never in the APK)

Pushed into the app's private storage with `run-as` (works for debug builds):

```bash
grep '^OPENROUTER_API_KEY=' ../../.env | cut -d= -f2- | tr -d '"'"'"' \n' |
  adb shell "run-as com.sense.app sh -c 'cat > files/openrouter_key.txt'"
echo '{"model": "google/gemini-3.1-flash-lite", "budget_usd": 1.0, "size": "v", "once_size": "g",
  "moment_prompt": "moment_v4.txt", "board_ip": "10.75.38.175"}' |
  adb shell "run-as com.sense.app sh -c 'cat > files/config.json'"
```

`size` (Live) and `once_size` (Describe): `s` 320×240, `v` 640×480, `g` 1024×768, `u` 1600×1200.
`moment_prompt`: any file in `tools/prompts/`. `board_ip`: the board's address on the hotspot (ask it
over USB with `i`). Spend is tracked on the phone. Over home Wi-Fi the camera step took 7-13 s or
timed out; on the hotspot it is ~0.4 s at 1024×768.

## Debugging while the board uses the USB port

The home router isolates clients, so adb over Wi-Fi only works with the Mac on the phone's
hotspot: `adb tcpip 5555` (once, over USB), then `adb connect <phone hotspot IP>:5555` (find it with `adb shell ip -4 addr`).

## Sessions

Same `session.jsonl` schema as the Mac (plus `"device": "android"`, `voice: android-tts`):

```bash
adb pull /sdcard/Android/data/com.sense.app/files/sessions/ ../sessions-phone/
```

Then `tools/session_video.py` and `tools/judge_video.py` work unchanged.

Everything else (presses, status changes, spoken errors like "Camera not connected", camera
failures with their reason, Bluetooth connects and drops) goes to a day file that survives
`adb logcat -c` and app restarts:

```bash
adb shell cat /sdcard/Android/data/com.sense.app/files/events/$(date +%Y%m%d).jsonl
```
