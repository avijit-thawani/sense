"""Ask the board for one photo over USB and save it.  Usage: snap.py [out.jpg]"""
import sys
import time

import serial
from serial.tools import list_ports

BAUD = 460800  # must match firmware/snap/snap.ino; 921600 fails on this CH340


def find_port():
    """The board's USB chip (CH340, vendor 0x1A86) on any OS: /dev/cu.usbserial-*, COM3, …"""
    for p in list_ports.comports():
        if p.vid == 0x1A86:
            return p.device
    raise RuntimeError("board not found: is the micro-USB cable plugged in and a data cable?")


PORT = None  # set to override, e.g. "COM4"


def connect():
    s = serial.Serial(PORT or find_port(), BAUD, timeout=5)
    s.dtr = False
    s.rts = False
    time.sleep(0.5)
    s.reset_input_buffer()
    try:  # opening the port can reset the board; the first request may go unanswered
        grab(s)
    except RuntimeError:
        s.reset_input_buffer()
    return s


def grab(s):
    """Returns one current JPEG (the board discards its stale frame itself)."""
    s.write(b"p")
    line = s.readline()
    while line and not line.startswith(b"JPG "):
        line = s.readline()
    if not line:
        raise RuntimeError("no reply from board")
    data = s.read(int(line.split()[1]))
    if not (data.startswith(b"\xff\xd8") and data.rstrip(b"\x00").endswith(b"\xff\xd9")):
        raise RuntimeError(f"corrupt JPEG ({len(data)} bytes)")
    return data


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "photo.jpg"
    data = grab(connect())
    with open(out, "wb") as f:
        f.write(data)
    print(f"saved {out} ({len(data)} bytes)")
