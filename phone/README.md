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
