"""Press p or Space to take a photo (saved to photos/ and opened), q to quit."""
import os
import subprocess
import sys
import termios
import time
import tty

from snap import connect, grab

PHOTOS = os.path.join(os.path.dirname(__file__), "..", "photos")
os.makedirs(PHOTOS, exist_ok=True)

s = connect()
print("Ready. p or Space = photo, q = quit.")

fd = sys.stdin.fileno()
saved = termios.tcgetattr(fd)
try:
    tty.setcbreak(fd)
    while True:
        key = sys.stdin.read(1)
        if key in ("q", "\x03"):
            break
        if key not in ("p", " "):
            continue
        path = os.path.join(PHOTOS, time.strftime("%Y%m%d-%H%M%S.jpg"))
        try:
            data = grab(s)
        except RuntimeError as e:
            print(e)
            continue
        with open(path, "wb") as f:
            f.write(data)
        print(f"saved {os.path.relpath(path)} ({len(data)} bytes)")
        subprocess.run(["open", path])
finally:
    termios.tcsetattr(fd, termios.TCSADRAIN, saved)
