"""Measures photo transfer over USB.  Usage: bench_usb.py BAUD [N]"""
import sys
import time

import snap

snap.BAUD = int(sys.argv[1])
n = int(sys.argv[2]) if len(sys.argv) > 2 else 15
s = snap.connect()
ok, sizes, times = 0, [], []
for _ in range(n):
    t0 = time.time()
    try:
        sizes.append(len(snap.grab(s)))
        times.append(time.time() - t0)
        ok += 1
    except RuntimeError as e:
        print(e)
        s.reset_input_buffer()
times.sort()
print(f"{snap.BAUD} baud: {ok}/{n} ok, median {times[len(times) // 2]:.2f}s, "
      f"~{sum(sizes) // max(ok, 1)} bytes" if times else f"{snap.BAUD}: all failed")
