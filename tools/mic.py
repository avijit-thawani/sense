"""Record from the XIAO's mic over USB, report levels, play it back.  Usage: mic.py [seconds] [out.wav]"""
import array
import math
import subprocess
import sys
import time

import serial
from serial.tools import list_ports


def find_port():
    """The XIAO ESP32S3's native USB (Espressif, vendor 0x303A)."""
    for p in list_ports.comports():
        if p.vid == 0x303A:
            return p.device
    raise RuntimeError("XIAO not found: is the USB-C cable plugged in and a data cable?")


def record(seconds):
    s = serial.Serial(find_port(), 115200, timeout=seconds + 5)
    time.sleep(0.3)
    s.reset_input_buffer()
    s.write(str(seconds).encode())
    line = s.readline()
    while line and not line.startswith(b"WAV "):
        line = s.readline()
    if not line:
        raise RuntimeError("no reply from board (is firmware/miccheck flashed?)")
    data = s.read(int(line.split()[1]))
    if not data.startswith(b"RIFF"):
        raise RuntimeError(f"corrupt WAV ({len(data)} bytes)")
    return data


def levels(wav):
    """Peak and RMS in dBFS, after removing the DC offset PDM mics often have."""
    x = array.array("h", wav[44:])
    mean = sum(x) / len(x)
    peak = max(abs(v - mean) for v in x)
    rms = (sum((v - mean) ** 2 for v in x) / len(x)) ** 0.5
    db = lambda v: 20 * math.log10(max(v, 1) / 32768)
    return mean, db(peak), db(rms)


if __name__ == "__main__":
    seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    out = sys.argv[2] if len(sys.argv) > 2 else "mic.wav"
    print(f"recording {seconds} s, talk now…")
    data = record(seconds)
    with open(out, "wb") as f:
        f.write(data)
    mean, peak, rms = levels(data)
    print(f"saved {out} ({len(data)} bytes)  DC offset {mean:.0f}  peak {peak:.1f} dBFS  RMS {rms:.1f} dBFS")
    subprocess.run(["afplay", out])
