"""Rebuilds a live session as a video: photos when taken, captions when answered,
the same `say` voice when it actually spoke, so viewers feel the real lag.

Usage: session_video.py SESSION_DIR OUT.mp4 ["Title line"] [--voice-startup=SECONDS] [--subtitle=TEXT]
  --voice-startup simulates a different speech engine (0.10 = persistent NSSpeechSynthesizer,
  measured Oct 28); the default is `say` per phrase.
Uses spoken_at when logged; otherwise schedules speech like the live speaker did.
Hold-to-ask rows (mode "ask") show "You asked" from the press until the answer; the question is
re-voiced in a second voice (the user's own audio isn't recorded).
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
SUBTITLE = "ESP32-CAM over USB  >  Gemini 3.1 Flash Lite  >  macOS voice"
QUESTION_VOICE = "Samantha"   # re-voices the user's question, distinct from the device voice


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
                       "text": r.get("spoken_text") or r["text"],
                       "question": r.get("question") if r.get("mode") == "ask" else None,
                       # hold-to-ask timing (phone builds after Oct 30 10:55): press, release, transcript
                       **{k: secs(dt.datetime.fromisoformat(r[k + "_at"])) if r.get(k + "_at") else None
                          for k in ("press", "release", "stt")}})
    return events


def question_audio(events, tmp):
    """The user's question in a second voice, starting at the press. With press and release logged,
    the speaking rate is set so the re-voiced question lasts as long as the real hold."""
    for i, e in enumerate(events):
        if not e["question"]:
            continue
        rate = 200
        if e["press"] is not None and e["release"] is not None and e["release"] > e["press"]:
            words = len(e["question"].split())
            rate = int(min(330, max(110, words * 60 / max(e["release"] - e["press"] - 0.4, 0.5))))
        aiff = os.path.join(tmp, f"q{i}.aiff")
        subprocess.run(["say", "-v", QUESTION_VOICE, "-r", str(rate), "-o", aiff, e["question"]], check=True)
        e["q_audio"] = aiff
        e["q_start"] = (e["press"] if e["press"] is not None else e["captured"]) + 0.3


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


GAP_MAX_S = 6.0     # idle stretches longer than this (no photo, no speech) are cut
GAP_SHOW_S = 1.5    # ... down to this, with a "skipped" note


def cut_idle(events):
    """Shortens long idle gaps (e.g. one test photo, then the real run a minute later).
    Timing inside each stretch is untouched. Returns [(video time, seconds skipped)]."""
    skips, shift, prev_end = [], 0.0, None
    for e in sorted(events, key=lambda e: e["captured"]):
        keys = [k for k in ("captured", "answered", "launched", "spoke", "press", "release", "stt", "q_start")
                if e.get(k) is not None]
        for k in keys:
            e[k] -= shift
        if prev_end is not None and e["captured"] - prev_end > GAP_MAX_S:
            cut = e["captured"] - prev_end - GAP_SHOW_S
            for k in keys:
                e[k] -= cut
            shift += cut
            skips.append((prev_end, cut))
        prev_end = max(prev_end or 0, e["captured"], e["spoke"] + e["dur"])
    return skips


def frame(t, events, t0, photos, title, skips=()):
    img = Image.new("RGB", (W, H), (16, 16, 20))
    d = ImageDraw.Draw(img)
    for start, cut in skips:
        if start <= t < start + GAP_SHOW_S:
            d.rectangle((0, 0, W, H), fill=(16, 16, 20))
            d.text((80, 320), f"... {cut:.0f} s with no photos skipped ...", font=font(40, True),
                   fill=(200, 200, 200))
            return img
    shown = [e for e in events if e["captured"] <= t]
    if shown:
        img.paste(photos[shown[-1]["photo"]], (0, 0))
        d.rectangle((12, 12, 220, 46), fill=(0, 0, 0))
        # restarts at every new photo (in hold-to-ask, every press)
        d.text((22, 18), f"photo taken  {t - shown[-1]['captured']:4.1f}s", font=font(20), fill=(255, 255, 255))
    # side panel: what the user is hearing, and which photo it describes
    x = 980
    d.text((x, 20), "Now speaking", font=font(22, True), fill=(255, 210, 90))
    speaking = [e for e in events if e["spoke"] <= t < e["spoke"] + e["dur"]]
    if speaking:
        e = speaking[-1]
        d.text((x, 52), f"photo taken {t - e['captured']:.1f}s ago",
               font=font(16), fill=(200, 200, 200))
        img.paste(photos[e["photo"]].resize((280, 210)), (x, 80))
        if e["question"] and e["release"] is not None and e["press"] is not None:
            stt = (e["stt"] or e["release"])
            steps = [("you speaking", e["release"] - e["press"]),
                     ("speech-to-text", stt - e["release"]),
                     ("model", e["model_s"]),
                     ("voice startup", e["spoke"] - e["answered"])]
            total = ("release to voice", e["spoke"] - e["release"])
        else:
            other = e["answered"] - e["captured"] - e["camera_s"] - e["model_s"]
            steps = [("camera", e["camera_s"]), ("model", e["model_s"]),
                     ("queued behind speech", e["launched"] - e["answered"]),
                     ("voice startup", e["spoke"] - e["launched"])]
            if other > 0.05:
                steps.insert(1, ("you speaking (est.)" if e["question"] else "other", other))
            total = ("photo to voice", e["spoke"] - e["captured"])
        for i, (label, s) in enumerate(steps):
            d.text((x, 298 + 20 * i), f"{label:<22}", font=font(15), fill=(170, 170, 170))
            d.text((x + 200, 298 + 20 * i), f"{s:4.1f}s", font=font(15), fill=(170, 170, 170))
        d.text((x, 302 + 20 * len(steps)), total[0], font=font(15, True), fill=(230, 230, 230))
        d.text((x + 200, 302 + 20 * len(steps)), f"{total[1]:4.1f}s",
               font=font(15, True), fill=(230, 230, 230))
        cap = font(34, True)
        lines = wrap(d, e["text"], cap, W - 80)
        top = H - 30 - 44 * len(lines)
        d.rectangle((0, top - 16, W, H), fill=(0, 0, 0))
        for i, line in enumerate(lines):
            d.text((40, top + 44 * i), line, font=cap, fill=(255, 255, 255))
    else:
        d.text((x, 52), "(silence)", font=font(16), fill=(140, 140, 140))
    asking = [e for e in events if e["question"] and e["captured"] <= t < e["spoke"] + e["dur"]]
    if asking:
        q = asking[-1]
        f = font(30, True)
        lines = wrap(d, f"You asked: \u201c{q['question']}\u201d", f, 900)
        d.rectangle((0, 56, 960, 66 + 40 * len(lines)), fill=(255, 204, 0))
        for i, line in enumerate(lines):
            d.text((22, 60 + 40 * i), line, font=f, fill=(0, 0, 0))
    if t - t0 < INTRO_S:
        d.rectangle((0, 0, W, H), fill=(16, 16, 20))
        d.text((80, 250), title, font=font(52, True), fill=(255, 255, 255))
        d.text((80, 330), SUBTITLE, font=font(28), fill=(200, 200, 200))
        d.text((80, 380), "Real timing: photos as taken, speech when it was spoken.",
               font=font(28), fill=(200, 200, 200))
        if any(e["question"] for e in events):
            d.text((80, 430), "Questions are re-voiced; the phone records no audio.",
                   font=font(28), fill=(200, 200, 200))
    return img


def main():
    global SAY_STARTUP_S, SUBTITLE
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for a in sys.argv[1:]:
        if a.startswith("--voice-startup="):
            SAY_STARTUP_S = float(a.split("=", 1)[1])
        if a.startswith("--subtitle="):
            SUBTITLE = a.split("=", 1)[1]
    folder, out = args[:2]
    title = args[2] if len(args) > 2 else "sense: live session replay"
    events = load(folder)
    tmp = tempfile.mkdtemp()
    n_answers = len(events)
    events = tts(events, tmp)
    if len(events) < n_answers:
        print(f"{n_answers - len(events)} answers dropped while the speaker was busy (as live)")
    question_audio(events, tmp)
    skips = cut_idle(events)
    for start, cut in skips:
        print(f"cut {cut:.0f} s of idle time")
    t0 = min(e["captured"] for e in events) - INTRO_S - 0.5
    t_end = max(e["spoke"] + e["dur"] for e in events) + 1.5
    photos = {e["photo"]: Image.open(e["photo"]).convert("RGB").resize((960, 720)) for e in events}

    video = os.path.join(tmp, "video.mp4")
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264",
                           "-pix_fmt", "yuv420p", video], stdin=subprocess.PIPE)
    n = int((t_end - t0) * FPS)
    for i in range(n):
        ff.stdin.write(frame(t0 + i / FPS, events, t0, photos, title, skips).tobytes())
    ff.stdin.close()
    ff.wait()

    inputs, filters, tracks = [], [], []
    for e in events:
        tracks.append((e["audio"], e["spoke"]))
        if e.get("q_audio"):
            tracks.append((e["q_audio"], e["q_start"]))
    for i, (path, start) in enumerate(tracks):
        inputs += ["-i", path]
        ms = int((start - t0) * 1000)
        filters.append(f"[{i + 1}:a]adelay={ms}|{ms}[a{i}]")
    mix = "".join(f"[a{i}]" for i in range(len(tracks)))
    filters.append(f"{mix}amix=inputs={len(tracks)}:normalize=0[aout]")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", video, *inputs,
                    "-filter_complex", ";".join(filters), "-map", "0:v", "-map", "[aout]",
                    "-c:v", "copy", "-c:a", "aac", "-shortest", out], check=True)
    lags = [e["spoke"] - e["captured"] for e in events]
    print(f"{out}: {len(events)} phrases, {n / FPS:.0f}s, photo-to-speech "
          f"{min(lags):.1f}-{max(lags):.1f}s (median {sorted(lags)[len(lags) // 2]:.1f}s)")


if __name__ == "__main__":
    main()
