#!/bin/sh
# Replays the sound-direction demo on the M6 glasses (plugged in over USB). Tap the bar to replay.
adb shell am start -S -n com.sense.glasstest/.SoundDemo
