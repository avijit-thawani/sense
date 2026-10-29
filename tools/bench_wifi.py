"""Photo transfer over Wi-Fi vs USB at each size (firmware/snapwifi).  Free: no model calls.

Usage: bench_wifi.py BOARD_IP [N]
"""
import statistics
import sys
import time

import requests

import snap

ip = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 10
web = requests.Session()  # keep-alive, as a live loop would
SIZES = [("qvga", "320x240", b"s"), ("vga", "640x480", b"v"), ("svga", "800x600", b"x"),
         ("xga", "1024x768", None), ("hd", "1280x720", None), ("sxga", "1280x1024", None),
         ("uxga", "1600x1200", b"u")]
usb = snap.connect()  # opening the port resets the board, so wait for it to rejoin Wi-Fi
for _ in range(30):
    usb.reset_input_buffer()
    usb.write(b"i")
    line = usb.readline().decode(errors="replace")
    if line.startswith("IP ") and "none" not in line:
        ip = line.split()[1]
        print(line.strip())
        break
    time.sleep(1)
print(f"{'size':10} {'bytes':>7} {'wifi med':>9} {'wifi max':>9} {'usb med':>8}  fails")
for name, label, usb_cmd in SIZES:
    web.get(f"http://{ip}/size", params={"f": name}, timeout=5)
    time.sleep(0.5)
    web.get(f"http://{ip}/capture", timeout=10)  # first frame after a size change can be odd
    times, sizes, fails = [], [], 0
    for _ in range(n):
        t = time.time()
        try:
            r = web.get(f"http://{ip}/capture", timeout=10)
            r.raise_for_status()
            assert r.content[:2] == b"\xff\xd8"
            times.append(time.time() - t)
            sizes.append(len(r.content))
        except Exception:
            fails += 1
    usb_t = []
    if usb_cmd:
        usb.write(usb_cmd)
        time.sleep(0.5)
        usb.reset_input_buffer()
        for _ in range(min(n, 5)):
            t = time.time()
            try:
                snap.grab(usb)
                usb_t.append(time.time() - t)
            except RuntimeError:
                usb.reset_input_buffer()
    usb_med = f"{statistics.median(usb_t):.2f}s" if usb_t else "-"
    if times:
        print(f"{label:10} {int(statistics.median(sizes)):7} {statistics.median(times):8.2f}s "
              f"{max(times):8.2f}s {usb_med:>8}  {fails}")
    else:
        print(f"{label:10} all {n} failed")
    with open(f"../.trash/wifi-{label}.jpg", "wb") as f:
        f.write(r.content)
web.get(f"http://{ip}/size", params={"f": "vga"}, timeout=5)
