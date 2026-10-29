#!/system/bin/sh
# Runs on the Android phone (adb push + adb shell): times photos from the board over Wi-Fi.
# Usage: sh phone_bench.sh BOARD_IP [N]
B=$1
N=${2:-6}
get() { printf "GET %s HTTP/1.0\r\nHost: board\r\n\r\n" "$1" | nc -w 10 "$B" 80; }
for f in qvga vga xga uxga; do
  get "/size?f=$f" > /dev/null
  sleep 1
  get /capture > /dev/null
  i=0
  while [ $i -lt "$N" ]; do
    s=$(date +%s%N)
    get /capture > /data/local/tmp/cap.raw
    e=$(date +%s%N)
    echo "$f $(( (e - s) / 1000000 )) $(wc -c < /data/local/tmp/cap.raw)"
    i=$((i + 1))
  done
done
get "/size?f=vga" > /dev/null
