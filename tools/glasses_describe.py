"""Describe-now on the glasses: swipe the touch strip, hear a short description.

Usage: glasses_describe.py [minutes]   (default 5; also describes once at start)

Same prompt and model as describe.py. Speech plays through the glasses' own speakers.
"""
import asyncio
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import glasses as G
from describe import PROMPT
from orclient import MODEL, describe

SIZE = 4                     # 512x288 preview, about 3 s over BLE
OUT = "/tmp/glasses-describe"
SPEAKER = "M08T_0A56"        # the glasses as a Bluetooth audio device (blueutil --connect ff-58-cb-c5-0a-56)


async def describe_now(g, n, how, pressed_at=None):
    t0 = pressed_at or time.monotonic()
    jpeg, _ = await G.preview(g, SIZE)
    t1 = time.monotonic()
    if not jpeg:
        print("no photo")
        return
    open(f"{OUT}/{n}.jpg", "wb").write(jpeg)
    text, secs, cost = await asyncio.to_thread(describe, jpeg, PROMPT)
    t2 = time.monotonic()
    text = text.strip()
    print(f"[{n}] {how}: photo {t1 - t0:.1f}s + model {t2 - t1:.1f}s = {t2 - t0:.1f}s  "
          f"${cost or 0:.5f}  {text!r}")
    if text and text.upper() != "SILENT":
        subprocess.Popen(["say", "-a", SPEAKER, text])


async def main(minutes):
    os.makedirs(OUT, exist_ok=True)
    g = await G.connect()
    await g.handshake()
    beat = asyncio.create_task(g.heartbeat())
    print(f"model {MODEL}; swipe the touch strip (one shutter) or press the photo button (two)")
    n = 1
    await describe_now(g, n, "start")
    end = time.monotonic() + minutes * 60
    try:
        while (left := end - time.monotonic()) > 0:
            try:
                t, c, p = await asyncio.wait_for(g.queue.get(), left)
            except asyncio.TimeoutError:
                break
            # 0x12 = volume changed (a swipe; no photo taken). 0x01 = media count changed (the
            # photo button: the glasses shoot their own photo, whose preview is not available).
            if c == G.EVENT and p and p[0] in (0x12, 0x01):
                n += 1
                await describe_now(g, n, "swipe" if p[0] == 0x12 else "photo button", pressed_at=t)
                while not g.queue.empty():  # drop presses made while busy
                    g.queue.get_nowait()
    finally:
        beat.cancel()
        await g.client.disconnect()


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 5))
