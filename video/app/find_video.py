"""Replays a find-mode run from the glasses as a video, to see and hear the delay.

  ../../.venv-tools/bin/python find_video.py ../notes/sessions/<session> [N]

One mp4 per find run in the session (or only run N, counting from 0), written next to session.jsonl.
The picture is the newest photo the glasses had taken at that moment. On it:
  yellow ring  where the beep was pointing: the newest answer that had come back by then
  green ring   where the thing was in the photo on screen (that answer came back later)
The gap between the rings is the delay as the wearer feels it. The sound is the beep rebuilt from
the log with the app's own formula (Sense.kt beeper); the spoken phrase is shown, not voiced.
"""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Helvetica.ttc"
W, H, BAR = 960, 720, 150
FPS = 10
RATE = 48000
YELLOW, GREEN, GREY, WHITE = (255, 204, 0), (90, 220, 120), (150, 150, 150), (255, 255, 255)


def font(size, bold=False):
    return ImageFont.truetype(FONT, size, index=1 if bold else 0)


def ts(s):
    return dt.datetime.fromisoformat(s).timestamp()


def runs(folder):
    """Each find run: target, start, stop, its photo rows and the times the voice was speaking."""
    out, cur = [], None
    for line in open(os.path.join(folder, "session.jsonl")):
        r = json.loads(line)
        if r.get("event") == "find_start":
            cur = {"target": r["target"], "start": ts(r["at"]), "stop": None, "rows": [], "voice": []}
            out.append(cur)
        elif cur is None:
            continue
        elif r.get("event") == "find_stop":
            cur["stop"] = ts(r["at"])
        elif r.get("mode") == "find" and "error" not in r:
            cur["rows"].append({"taken": ts(r["press_at"]), "in_hand": ts(r["captured_at"]), "answered": ts(r["answered_at"]),
                                "photo": r["photo"], "x": r["x"], "y": r.get("y") or 50, "size": r.get("size") or 20,
                                "where": r["where"]})
        elif r.get("event") == "spoke":
            cur["voice"].append([ts(r["audio_at"]), None])
        elif r.get("event") == "spoke_done" and cur["voice"] and cur["voice"][-1][1] is None:
            cur["voice"][-1][1] = ts(r["done_at"])
    return [r for r in out if r["rows"]]


def cue_at(rows, t):
    """The answer the beep was following at time t: of those back by then, the one from the newest photo."""
    back = [r for r in rows if r["answered"] <= t]
    return max(back, key=lambda r: r["taken"]) if back else None


def beeps(run, length):
    """The beep track, pulse by pulse, exactly as Sense.kt builds it."""
    out = np.zeros((int(length * RATE) + RATE, 2), dtype=np.float64)
    t = 0.0
    while t < length:
        now = run["start"] + t
        if any(a <= now < (b or a + 2) for a, b in run["voice"]):
            t += 0.2
            continue
        c = cue_at(run["rows"], now)
        x = c["x"] if c and c["x"] is not None else -1
        off = 0.0 if x < 0 else abs(x - 50) / 50.0
        pps = 1.2 if x < 0 else 9.0 if off <= 0.2 else 2.5 + 5.0 * (1 - off)
        freq = 330.0 if x < 0 else 660.0 * 2 ** ((50 - c["y"]) / 50.0 * 0.75)
        level = 0.3 if x < 0 else 0.45 + 0.55 * min(1.0, c["size"] / 40.0)
        angle = 0.0 if x < 0 else (x - 50) / 50.0 * 80.0
        pan = (angle + 90) / 180 * (np.pi / 2)
        delay = int(0.00065 * abs(np.sin(np.radians(angle))) * RATE)
        period = int(RATE / pps)
        on = min(period // 2, RATE * 8 // 100)
        i = np.arange(on)
        env = np.minimum(1.0, np.minimum(i / 200.0, (on - i) / 200.0))
        v = env * (np.sin(2 * np.pi * freq * i / RATE) + 0.4 * np.sin(4 * np.pi * freq * i / RATE)) / 1.4 * level
        at = int(t * RATE)
        out[at + (delay if angle > 0 else 0):][:on, 0] += v * np.cos(pan)
        out[at + (delay if angle < 0 else 0):][:on, 1] += v * np.sin(pan)
        t += period / RATE
    return (out[: int(length * RATE)] * 30000).astype(np.int16)


def ring(d, r, color, width):
    if r["x"] is None:
        return
    cx, cy = r["x"] / 100 * W, r["y"] / 100 * H
    rad = max(22, r["size"] / 100 * W / 2)
    d.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=color, width=width)


def side(r):
    if r is None:
        return "waiting for the first answer"
    if r["x"] is None:
        return "not in view (slow low tick)"
    s = "LEFT" if r["x"] < 38 else "RIGHT" if r["x"] > 62 else "AHEAD"
    return s + (", high" if r["y"] < 33 else ", low" if r["y"] > 67 else "")


def make(folder, n, run):
    rows = run["rows"]
    end = max(r["answered"] for r in rows) + 0.5
    if run["stop"]:
        end = min(end, run["stop"] + 0.5)
    length = end - run["start"]
    by_photo = sorted(rows, key=lambda r: r["in_hand"])
    ages = []
    out_path = os.path.join(folder, "find_%d_%s.mp4" % (n, run["target"].replace(" ", "_")))
    tmp = tempfile.mkdtemp()
    silent = os.path.join(tmp, "v.mp4")
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H + BAR),
                           "-r", str(FPS), "-i", "-", "-pix_fmt", "yuv420p", silent], stdin=subprocess.PIPE)
    cache = {}
    for f in range(int(length * FPS)):
        t = run["start"] + f / FPS
        shown = [r for r in by_photo if r["in_hand"] <= t]
        frame = Image.new("RGB", (W, H + BAR), (0, 0, 0))
        d = ImageDraw.Draw(frame)
        cue = cue_at(rows, t)
        if shown:
            p = shown[-1]
            if p["photo"] not in cache:
                cache.clear()
                cache[p["photo"]] = Image.open(os.path.join(folder, p["photo"])).convert("RGB").resize((W, H))
            frame.paste(cache[p["photo"]], (0, 0))
            ring(d, p, GREEN, 4)
        if cue:
            ring(d, cue, YELLOW, 6)
            age = t - cue["taken"]
            ages.append(age)
        d.rectangle((0, 0, W, 44), fill=(0, 0, 0))
        d.text((14, 8), "find: %s    %.1f s" % (run["target"], f / FPS), font=font(26, True), fill=WHITE)
        speaking = any(a <= t < (b or a + 2) for a, b in run["voice"])
        d.text((14, H + 10), "beep says: " + ("(voice speaking)" if speaking else side(cue)), font=font(30, True), fill=YELLOW)
        if cue:
            d.text((14, H + 52), "from a photo taken %.1f s ago" % (t - cue["taken"]), font=font(26), fill=YELLOW)
            if cue["where"] and cue["x"] is not None:
                d.text((480, H + 52), cue["where"][:34], font=font(24), fill=GREY)
        d.text((14, H + 96), "yellow ring: where the beep points      green ring: where it is in this photo (answer still on its way)",
               font=font(17), fill=GREY)
        ff.stdin.write(frame.tobytes())
    ff.stdin.close()
    ff.wait()
    wav = os.path.join(tmp, "a.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(beeps(run, length).tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", silent, "-i", wav, "-c:v", "copy", "-c:a", "aac", "-shortest", out_path], check=True)

    gaps = np.diff(sorted(r["answered"] for r in rows))
    lat = [r["answered"] - r["taken"] for r in rows]
    seen = sum(r["x"] is not None for r in rows)
    print("%s  target '%s'  %.0f s, %d photos, seen in %d" % (os.path.basename(out_path), run["target"], length, len(rows), seen))
    print("  photo taken -> answer back: median %.2f s (%.2f to %.2f)" % (np.median(lat), min(lat), max(lat)))
    print("  new answer every: median %.2f s, longest wait %.2f s" % (np.median(gaps), gaps.max()))
    print("  age of what the beep points at: average %.2f s, worst %.2f s" % (np.mean(ages), max(ages)))
    return out_path


if __name__ == "__main__":
    folder = sys.argv[1]
    all_runs = runs(folder)
    for n, run in enumerate(all_runs):
        if len(sys.argv) > 2 and int(sys.argv[2]) != n:
            continue
        make(folder, n, run)
