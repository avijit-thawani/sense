"""Rebuilds a live session as a video: photos when taken, captions when answered,
the same `say` voice when it actually spoke, so viewers feel the real lag.

Usage: session_video.py SESSION_DIR OUT.mp4 ["Title line"] [--voice-startup=SECONDS]
  --voice-startup simulates a different speech engine (0.10 = persistent NSSpeechSynthesizer,
  measured Oct 28); the default is `say` per phrase.
Uses spoken_at when logged; otherwise schedules speech like the live speaker did.
Free: no model calls, the voice is the local macOS `say`.
"""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
DAY = "2026-10-28"
W, H, FPS = 1280, 720, 10
SAY_WPM = 230
# Measured Oct 28: `say` runs ~0.95 s longer than its audio (process launch + voice load).
# Treated as all before the audio starts, the worst case for the listener.
SAY_STARTUP_S = 0.95
FONT = "/System/Library/Fonts/Helvetica.ttc"
INTRO_S = 3.0


def font(size, bold=False):
    return ImageFont.truetype(FONT, size, index=1 if bold else 0)


def secs(t):
    return (t - dt.datetime(2026, 10, 28)).total_seconds()


def load(folder):
    rows = [json.loads(l) for l in open(os.path.join(folder, "session.jsonl"))]
    spoken_at = {r["photo"]: secs(dt.datetime.fromisoformat(r["spoken_at"]))
                 for r in rows if r.get("event") == "spoke"}
    audio_at = {r["photo"]: secs(dt.datetime.fromisoformat(r["audio_at"]))
                for r in rows if r.get("event") == "spoke" and r.get("audio_at")}
    events = []
    for r in rows:
        if r.get("event") or r["status"] not in ("spoken", "unknown"):
            continue
        events.append({"photo": os.path.join(folder, r["photo"]),
                       "captured": secs(dt.datetime.fromisoformat(r["captured_at"])),
                       "answered": secs(dt.datetime.fromisoformat(r["answered_at"])),
                       "camera_s": r.get("camera_s") or 0, "model_s": r.get("model_s") or 0,
                       "spoke_logged": spoken_at.get(r["photo"]),
                       "audio_logged": audio_at.get(r["photo"]),
                       "text": r.get("spoken_text") or r["text"]})
    return events


def tts(events, tmp):
    """Renders each phrase with `say`, schedules it like the live speaker did.
    e["launched"] is when `say` was started; e["spoke"] is when audio is heard."""
    for i, e in enumerate(events):
        aiff = os.path.join(tmp, f"say{i}.aiff")
        subprocess.run(["say", "-r", str(SAY_WPM), "-o", aiff, e["text"]], check=True)
        e["audio"] = aiff
        e["dur"] = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                         "-of", "csv=p=0", aiff], capture_output=True, text=True).stdout)
    if all(e["spoke_logged"] for e in events):
        for e in events:
            e["launched"] = e["spoke_logged"]
            e["spoke"] = e["audio_logged"] or e["launched"] + SAY_STARTUP_S
        return events
    # Like the live speaker: when it frees up, only the newest waiting answer is spoken;
    # older ones are dropped, so speech never falls behind by more than one phrase.
    spoken, free_at, waiting = [], 0.0, sorted(events, key=lambda e: e["answered"])
    while waiting:
        ready = [e for e in waiting if e["answered"] <= free_at] or [waiting[0]]
        e = ready[-1]
        waiting = [w for w in waiting if w["answered"] > e["answered"]]
        e["launched"] = max(e["answered"], free_at)
        e["spoke"] = e["launched"] + SAY_STARTUP_S
        free_at = e["spoke"] + e["dur"]
        spoken.append(e)
    return spoken


def wrap(draw, text, f, width):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=f) > width and line:
            lines.append(line)
            line = word
        else:
            line = trial
    return lines + [line]


def frame(t, events, t0, photos, title):
    img = Image.new("RGB", (W, H), (16, 16, 20))
    d = ImageDraw.Draw(img)
    shown = [e for e in events if e["captured"] <= t]
    if shown:
        img.paste(photos[shown[-1]["photo"]], (0, 0))
        d.rectangle((12, 12, 190, 46), fill=(0, 0, 0))
        d.text((22, 18), f"camera  {t - t0:5.1f}s", font=font(20), fill=(255, 255, 255))
    # side panel: what the user is hearing, and which photo it describes
    x = 980
    d.text((x, 20), "Now speaking", font=font(22, True), fill=(255, 210, 90))
    speaking = [e for e in events if e["spoke"] <= t < e["spoke"] + e["dur"]]
    if speaking:
        e = speaking[-1]
        d.text((x, 52), f"photo taken {t - e['captured']:.1f}s ago",
               font=font(16), fill=(200, 200, 200))
        img.paste(photos[e["photo"]].resize((280, 210)), (x, 80))
        other = e["answered"] - e["captured"] - e["camera_s"] - e["model_s"]
        steps = [("camera + USB", e["camera_s"]), ("model", e["model_s"]),
                 ("queued behind speech", e["launched"] - e["answered"]),
                 ("voice startup", e["spoke"] - e["launched"])]
        if other > 0.05:
            steps.insert(2, ("other", other))
        for i, (label, s) in enumerate(steps):
            d.text((x, 298 + 20 * i), f"{label:<22}", font=font(15), fill=(170, 170, 170))
            d.text((x + 200, 298 + 20 * i), f"{s:4.1f}s", font=font(15), fill=(170, 170, 170))
        d.text((x, 302 + 20 * len(steps)), "photo to voice", font=font(15, True), fill=(230, 230, 230))
        d.text((x + 200, 302 + 20 * len(steps)), f"{e['spoke'] - e['captured']:4.1f}s",
               font=font(15, True), fill=(230, 230, 230))
        cap = font(34, True)
        lines = wrap(d, e["text"], cap, W - 80)
        top = H - 30 - 44 * len(lines)
        d.rectangle((0, top - 16, W, H), fill=(0, 0, 0))
        for i, line in enumerate(lines):
            d.text((40, top + 44 * i), line, font=cap, fill=(255, 255, 255))
    else:
        d.text((x, 52), "(silence)", font=font(16), fill=(140, 140, 140))
    if t - t0 < INTRO_S:
        d.rectangle((0, 0, W, H), fill=(16, 16, 20))
        d.text((80, 250), title, font=font(52, True), fill=(255, 255, 255))
        d.text((80, 330), "ESP32-CAM over USB  >  Gemini 3.1 Flash Lite  >  macOS voice",
               font=font(28), fill=(200, 200, 200))
        d.text((80, 380), "Real timing: photos as taken, speech when it was spoken.",
               font=font(28), fill=(200, 200, 200))
    return img


def main():
    global SAY_STARTUP_S
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for a in sys.argv[1:]:
        if a.startswith("--voice-startup="):
            SAY_STARTUP_S = float(a.split("=", 1)[1])
    folder, out = args[:2]
    title = args[2] if len(args) > 2 else "sense: live session replay"
    events = load(folder)
    tmp = tempfile.mkdtemp()
    n_answers = len(events)
    events = tts(events, tmp)
    if len(events) < n_answers:
        print(f"{n_answers - len(events)} answers dropped while the speaker was busy (as live)")
    t0 = events[0]["captured"] - INTRO_S - 0.5
    t_end = max(e["spoke"] + e["dur"] for e in events) + 1.5
    photos = {e["photo"]: Image.open(e["photo"]).convert("RGB").resize((960, 720)) for e in events}

    video = os.path.join(tmp, "video.mp4")
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264",
                           "-pix_fmt", "yuv420p", video], stdin=subprocess.PIPE)
    n = int((t_end - t0) * FPS)
    for i in range(n):
        ff.stdin.write(frame(t0 + i / FPS, events, t0, photos, title).tobytes())
    ff.stdin.close()
    ff.wait()

    inputs, filters = [], []
    for i, e in enumerate(events):
        inputs += ["-i", e["audio"]]
        ms = int((e["spoke"] - t0) * 1000)
        filters.append(f"[{i + 1}:a]adelay={ms}|{ms}[a{i}]")
    mix = "".join(f"[a{i}]" for i in range(len(events)))
    filters.append(f"{mix}amix=inputs={len(events)}:normalize=0[aout]")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", video, *inputs,
                    "-filter_complex", ";".join(filters), "-map", "0:v", "-map", "[aout]",
                    "-c:v", "copy", "-c:a", "aac", "-shortest", out], check=True)
    lags = [e["spoke"] - e["captured"] for e in events]
    print(f"{out}: {len(events)} phrases, {n / FPS:.0f}s, photo-to-speech "
          f"{min(lags):.1f}-{max(lags):.1f}s (median {sorted(lags)[len(lags) // 2]:.1f}s)")


if __name__ == "__main__":
    main()
