"""Desk test for the XIAO ESP32S3 Sense running firmware/xiao: camera, photo speed over USB, mic.
Usage: xiao_check.py [outdir]   (saves one photo per size and mic.wav there)"""
import os
import statistics
import sys
import time

import serial

from mic import find_port, levels

SIZES = {"s": "320x240", "v": "640x480", "x": "800x600", "g": "1024x768", "u": "1600x1200"}


def read_reply(s, tag):
    line = s.readline()
    while line and not line.startswith(tag):
        line = s.readline()
    if not line:
        raise RuntimeError(f"no {tag.decode().strip()} reply from board (is firmware/xiao flashed?)")
    return s.read(int(line.split()[1]))


def photo(s):
    s.write(b"p")
    data = read_reply(s, b"JPG ")
    if not (data.startswith(b"\xff\xd8") and data.rstrip(b"\x00").endswith(b"\xff\xd9")):
        raise RuntimeError(f"corrupt JPEG ({len(data)} bytes)")
    return data


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "xiao_check"
    os.makedirs(out, exist_ok=True)
    s = serial.Serial(find_port(), 115200, timeout=10)
    time.sleep(0.5)
    s.reset_input_buffer()
    s.write(b"i")
    time.sleep(0.3)
    print(s.read(s.in_waiting).decode(errors="replace").strip())

    for cmd, name in SIZES.items():
        s.write(cmd.encode())
        time.sleep(0.5)  # the sensor needs a few frames to settle after a size change
        photo(s)
        times, sizes = [], []
        for _ in range(5):
            t = time.perf_counter()
            data = photo(s)
            times.append(time.perf_counter() - t)
            sizes.append(len(data))
        with open(os.path.join(out, f"{name}.jpg"), "wb") as f:
            f.write(data)
        kb = statistics.mean(sizes) / 1024
        ms = statistics.median(times) * 1000
        print(f"{name:>9}: {kb:6.1f} KB  median {ms:5.0f} ms  (min {min(times)*1000:.0f}, max {max(times)*1000:.0f})")

    s.write(b"v")
    print("recording 3 s, talk now…")
    s.timeout = 15
    s.write(b"3")
    wav = read_reply(s, b"WAV ")
    path = os.path.join(out, "mic.wav")
    with open(path, "wb") as f:
        f.write(wav)
    mean, peak, rms = levels(wav)
    print(f"saved {path}  DC offset {mean:.0f}  peak {peak:.1f} dBFS  RMS {rms:.1f} dBFS")
