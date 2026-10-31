"""Ask and describe on the glasses, answers spoken through the glasses' speakers.

  talk button  ask: a photo is taken right away while you speak your question; it stops
               listening ~0.8 s after you stop talking and answers (your voice goes to the
               model as audio, no STT)
  swipe        describe now (one photo, short description)

Usage: glasses_ask.py [minutes]    (default 10)
Needs the glasses connected to the Mac as Bluetooth audio: blueutil --connect ff-58-cb-c5-0a-56
"""
import asyncio
import base64
import ctypes.util
import io
import math
import os
import struct
import subprocess
import sys
import time
import wave

sys.path.insert(0, os.path.dirname(__file__))
_find = ctypes.util.find_library
ctypes.util.find_library = lambda n: "/opt/homebrew/lib/libopus.dylib" if n == "opus" else _find(n)
import opuslib  # noqa: E402

import glasses as G  # noqa: E402
from describe import PROMPT  # noqa: E402
from orclient import MODEL, call, describe  # noqa: E402

SIZE = 4                     # 512x288 preview
SPEAKER = "M08T_0A56"
OUT = "/tmp/glasses-ask"
ASK_PROMPT = open(os.path.join(os.path.dirname(__file__), "prompts", "ask_app_v1.txt")).read().strip()
QUESTION = "(their question is in the attached audio)"
SILENCE_END_S = 0.8          # stop listening after this much quiet following speech
MAX_TALK_S = 10


def say(text):
    if text and text.upper() != "SILENT":
        subprocess.Popen(["say", "-a", SPEAKER, text])


def wav_bytes(pcm):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm)
    return buf.getvalue()


def rms(pcm):
    n = len(pcm) // 2
    return math.sqrt(sum(s * s for s in struct.unpack(f"<{n}h", pcm)) / n) if n else 0


async def record(g, started):
    """Collect mic packets until silence after speech, the glasses' own end event, or the cap."""
    dec = opuslib.Decoder(16000, 1)
    pcm, levels, spoke_at, quiet_since = bytearray(), [], None, None
    while time.monotonic() - started < MAX_TALK_S:
        if g.talk_ended:
            return bytes(pcm), "glasses end"
        try:
            _, c, p = await asyncio.wait_for(g.audio.get(), 0.2)
        except asyncio.TimeoutError:
            continue
        frame = p.rstrip(b"\x00")
        try:
            chunk = dec.decode(frame, 320)
        except opuslib.OpusError:
            continue
        pcm += chunk
        level = rms(chunk)
        levels.append(level)
        floor = sorted(levels[:15])[len(levels[:15]) // 2] if levels else 0
        now = time.monotonic()
        if len(levels) > 15 and level > max(3 * floor, 300):
            spoke_at, quiet_since = spoke_at or now, None
        elif spoke_at and len(levels) > 15:
            quiet_since = quiet_since or now
            if now - quiet_since > SILENCE_END_S:
                return bytes(pcm), "silence"
    return bytes(pcm), "max length"


async def ask(g, n, started):
    """Photo fired at the press, while the mic records (as in the sense app); if the glasses
    refuse the camera during mic mode, shoot again after the question."""
    g.talk_ended = None
    while not g.audio.empty():
        g.audio.get_nowait()
    photo = asyncio.create_task(G.preview(g, SIZE))
    pcm, why = await record(g, started)
    t_end = time.monotonic()
    await g.send(G.CONTROL, b"\x02\x01\x0b")  # stop the mic stream now rather than at its own timeout
    jpeg, _ = await photo
    when = f"during talk, ready {time.monotonic() - started:.1f}s after press"
    if not jpeg:
        await asyncio.sleep(0.2)
        jpeg, _ = await G.preview(g, SIZE)
        when = "after talk (camera refused during mic)"
    t_photo = time.monotonic()
    audio = wav_bytes(pcm)
    open(f"{OUT}/{n}.wav", "wb").write(audio)
    if not jpeg:
        print(f"[{n}] ask: no photo")
        return
    open(f"{OUT}/{n}.jpg", "wb").write(jpeg)
    content = [{"type": "text", "text": ASK_PROMPT.replace("{q}", QUESTION)},
               {"type": "input_audio", "input_audio": {"data": base64.b64encode(audio).decode(), "format": "wav"}},
               {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}}]
    text, _, cost = await asyncio.to_thread(
        call, content, MODEL, {"max_tokens": 120, "provider": {"sort": "latency"}, "reasoning": {"effort": "none"}},
        {"source": "glasses_ask"})
    t_done = time.monotonic()
    say(text)
    print(f"[{n}] ask: talked {t_end - started:.1f}s (ended by {why}); photo {when}; "
          f"answer {t_done - t_end:.1f}s after you stopped (model {t_done - t_photo:.1f}s)  "
          f"${cost or 0:.5f}  {text!r}")


async def describe_now(g, n, pressed_at):
    jpeg, _ = await G.preview(g, SIZE)
    t1 = time.monotonic()
    if not jpeg:
        print(f"[{n}] describe: no photo")
        return
    open(f"{OUT}/{n}.jpg", "wb").write(jpeg)
    text, _, cost = await asyncio.to_thread(describe, jpeg, PROMPT)
    t2 = time.monotonic()
    say(text.strip())
    print(f"[{n}] describe: photo {t1 - pressed_at:.1f}s + model {t2 - t1:.1f}s = {t2 - pressed_at:.1f}s  "
          f"${cost or 0:.5f}  {text.strip()!r}")


async def main(minutes):
    os.makedirs(OUT, exist_ok=True)
    g = await G.connect()
    await g.handshake()
    beat = asyncio.create_task(g.heartbeat())
    print(f"model {MODEL}. Talk button = ask, swipe = describe.")
    say("Ready.")
    n, end = 0, time.monotonic() + minutes * 60
    try:
        while (left := end - time.monotonic()) > 0:
            try:
                t, c, p = await asyncio.wait_for(g.queue.get(), left)
            except asyncio.TimeoutError:
                break
            if c != G.EVENT or not p:
                continue
            if p[0] == 0x03:
                n += 1
                await ask(g, n, t)
            elif p[0] == 0x12:
                n += 1
                await describe_now(g, n, t)
            else:
                continue
            for q in (g.queue, g.audio):
                while not q.empty():
                    q.get_nowait()
    finally:
        beat.cancel()
        await g.client.disconnect()


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 10))
