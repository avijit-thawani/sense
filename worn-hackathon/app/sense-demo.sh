#!/bin/sh
# Minimal sense on the M6 glasses (plugged in over USB, already on Wi-Fi):
# builds and installs the app, pushes the OpenRouter and ElevenLabs keys, starts it.
# Then TAP the bar and speak (a question, 'read this', 'find my keys') or stay quiet for a description.
#   ./sense-demo.sh          build + install + keys + start
#   ./sense-demo.sh start    just start it again
#   ./sense-demo.sh record   same as start (every run now records video and sound, for demo clips);
#                            RECORD=off ./sense-demo.sh start runs without recording
#   ./sense-demo.sh stop     leave the app cleanly (same as a double tap); closes the video file
#   ./sense-demo.sh pull     copy the session logs and photos to ../notes/sessions/
set -e
cd "$(dirname "$0")"
# Which pair: GLASSES=<adb serial> to choose; otherwise the one M6 pair on the cable.
GLASSES=${GLASSES:-$(adb devices | awk '/^ANDESAIR.*device$/ {print $1}' | head -1)}
[ -n "$GLASSES" ] || { echo "Glasses not on adb. See 'Connecting' in ../README.md"; exit 1; }
G="adb -s $GLASSES"
PKG=com.sense.glasstest
DIR=/sdcard/Android/data/$PKG/files
ENV=../../../.env   # learn/.env, shared credentials

$G get-state >/dev/null 2>&1 || { echo "Glasses not on adb. See 'Connecting' in ../README.md"; exit 1; }

if [ "$1" = "pull" ]; then
  mkdir -p ../notes/sessions
  $G pull $DIR/sessions/. ../notes/sessions/
  exit 0
fi

# The keys go into the app's private storage and are never printed. If this build of Android
# refuses run-as, they go to the app's external folder and the app moves them inside on start.
push_key() {   # push_key NAME_IN_ENV file_on_glasses
  KEY=$(grep "^$1=" "$ENV" | cut -d= -f2- | tr -d "\"' \n")
  [ -n "$KEY" ] || { echo "No $1 in $ENV"; return 1; }
  if printf %s "$KEY" | $G shell "run-as $PKG sh -c 'mkdir -p files && cat > files/$2'" 2>/dev/null \
     && [ "$($G shell "run-as $PKG sh -c 'wc -c < files/$2'" | tr -d ' \r\n')" = "${#KEY}" ]; then
    echo "$1 stored (private)."
  else
    $G shell "mkdir -p $DIR"
    printf %s "$KEY" | $G shell "cat > $DIR/$2"
    echo "$1 pushed (the app moves it to private storage on start)."
  fi
}

if [ "$1" = "stop" ]; then
  $G shell input keyevent KEYCODE_BACK; $G shell input keyevent KEYCODE_BACK   # two double taps = exit
  exit 0
fi

if [ "$1" != "start" ] && [ "$1" != "record" ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  (cd glasstest && ./gradlew -q :app:assembleDebug)
  $G install -r -g glasstest/app/build/outputs/apk/debug/app-debug.apk
  push_key OPENROUTER_API_KEY openrouter_key.txt
  push_key ELEVENLABS_API_KEY elevenlabs_key.txt || echo "(answers will be shown, not spoken)"
fi

$G shell svc wifi enable   # the glasses came back with Wi-Fi switched off once; the venue network is saved
# SCREEN=off blanks the display: the app is fully usable by sound, taps and swipes alone.
EXTRA=""; [ "$SCREEN" = "off" ] && EXTRA="--ez screen false"
# LIVE_MIN=20 lets live describe run 20 minutes before it ends itself (default 5): for battery runs.
[ -n "$LIVE_MIN" ] && EXTRA="$EXTRA --ei live_min $LIVE_MIN"
[ "$RECORD" = "off" ] && [ "$1" != "record" ] && EXTRA="$EXTRA --ez record false"
$G shell input keyevent KEYCODE_BACK; $G shell input keyevent KEYCODE_BACK; sleep 1   # let a running copy close its video file first
$G shell am start -S -n $PKG/.Sense $EXTRA
[ "$RECORD" = "off" ] && [ "$1" != "record" ] || echo "RECORDING video and sound. End with two double taps or ./sense-demo.sh stop, then ./sense-demo.sh pull"
# No-laptop start (Untethered.kt): switch on our accessibility service, which Android restarts at every
# boot and which then opens Sense; keep any service that was already on. Exempt the app from battery sleep.
# Done after every start, because the forced stop above ("am start -S") switches the service off again.
SVC=$PKG/$PKG.OpenService
CUR=$($G shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$CUR:" in
  *":$SVC:"*) ;;
  ":null:"|"::") $G shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) $G shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
$G shell settings put secure accessibility_enabled 1
$G shell dumpsys deviceidle whitelist +$PKG >/dev/null
echo "Started. TAP then speak (or stay quiet to describe). SWIPE: volume. Double tap: stop (twice: exit). Triple tap: instructions."
echo "Logs: $G logcat -s Sense"
