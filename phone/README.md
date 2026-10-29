# phone: Android app

Board on the phone's USB-C port (power + photos over USB serial, CH340 at 460800 baud), Gemini over
the phone's internet, Android text-to-speech to whatever audio is active (Bluetooth earphones).
Same pipeline as `tools/describe.py`: 640×480, `auto` brightening, items mode, speech-paced photos.

Screen: **Live** (start/stop), **Once**, **Connect**; last photo and a log. Plugging the board in
opens the app and grants USB access. Needs a USB-C to micro-USB *data* cable (or OTG adapter).

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
echo '{"model": "google/gemini-3.1-flash-lite", "budget_usd": 1.0, "size": "v"}' |
  adb shell "run-as com.sense.app sh -c 'cat > files/config.json'"
```

`size`: `s` 320×240, `v` 640×480, `g` 1024×768, `u` 1600×1200. Spend is tracked on the phone.

## Debugging while the board uses the USB port

The home router isolates clients, so adb over Wi-Fi only works with the Mac on the phone's
hotspot: `adb tcpip 5555` (once, over USB), then `adb connect <phone hotspot IP>:5555` (find it with `adb shell ip -4 addr`).

## Sessions

Same `session.jsonl` schema as the Mac (plus `"device": "android"`, `voice: android-tts`):

```bash
adb pull /sdcard/Android/data/com.sense.app/files/sessions/ ../sessions-phone/
```

Then `tools/session_video.py` and `tools/judge_video.py` work unchanged.
