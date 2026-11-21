"""Makes the demo video from a screen recording of the glasses and the Sense session it shows.

The glasses have one camera and Sense holds it, so the glasses' own screen recorder loses its
camera view the moment Sense opens (it keeps one still frame and only the display changes on it).
Sense records the camera itself (video_N.mp4 in the session folder), so the picture is put back
together here: Sense's live camera video, with the display lifted out of the screen recording and
laid on top where the recorder had it (the middle 720 of the 1280-wide view). The recorder's own
status bar is left out. Output: SCREEN-stitched.mp4. A session with no video (--ez record false)
gets the screen recording's own picture, untouched, with the sound added: SCREEN-sound.mp4.

The recorder saves the picture only (Android 9 cannot record what an app plays), so the sound is
rebuilt from what the app logged and kept in the session folder, each piece at the moment it played:

  spoken lines   say_NNN.pcm, at the "spoke" row's audio_at; ended early where the wearer cut it off
  find beeps     beeps_NN.pcm (48 kHz stereo, so the side the beep came from survives)
  notes, ticks   made again here with the app's own formulas, from the "tone" and "tick_*" rows
  the wearer     audio.pcm for the whole run (./sense-demo.sh record); otherwise each recorded question

  ../../.venv-tools/bin/python screen_demo.py SCREEN.mp4 SESSION_DIR [SESSION_DIR ...] [--out=OUT.mp4]
                                              [--start=EPOCH_MS] [--shift=SECONDS]

The recording's start time is read from its file name (the glasses name it by the clock, in
milliseconds); --start gives it by hand. --shift moves all the sound later (earlier if negative).
Sessions older than the app change that keeps the played sound have no say_/beeps_ files: those
lines are counted and left silent.
Free: no model or voice calls.
"""
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import wave

import numpy as np
from PIL import Image

LO, HI = 16000, 48000          # the app's voice/notes rate, and its find-beep rate
VOICE, NOTES, BEEPS, MIC = 2.0, 0.5, 1.0, 1.0   # how loud each part is in the mix (the glasses boost the voice by 9 dB; the room on the microphone is loud)
DEFAULT_OFFSET_MIN = 480       # logs before the app wrote utc_offset_min: the glasses' clock was UTC+8
# Where the screen recorder's 480x640 picture sits in Sense's 1280x960 video: the middle 720x960,
# 1.5 camera pixels to one. Fitted Nov 21 on a frozen recorder frame against Sense's first frame
# 0.2 s later: scale 1.46-1.50, left edge 260-274, top 9-20 (the head moved a little in between).
SCREEN_W, SCREEN_H, CAM_W, CAM_H, CAM_LEFT, CAM_SPAN = 480, 640, 1280, 960, 280, 720
STATUS_ROWS = (136, 178)       # the recorder's own bar (clock, timer, "double-click to stop"): not part of the display
DISPLAY_GREEN = np.array([80, 240, 70], dtype=np.float32)
FPS = 24


def pcm16(path):
    return np.frombuffer(open(path, "rb").read(), dtype="<i2").astype(np.float32) / 32768


def notes(freqs, ms, soft):
    """Sense.kt tone(): short notes one after another."""
    per = LO * ms // 1000
    p = np.arange(per)
    env = np.minimum(1.0, np.minimum(p / 80.0, (per - p) / 80.0))
    amp = (8000 if soft else 22000) / 32768
    return np.concatenate([env * np.sin(2 * np.pi * f * p / LO) * amp for f in freqs]).astype(np.float32)


def tick():
    """Sense.kt hum(): one soft tick, repeated every 0.45 s while the model thinks."""
    on = LO * 6 // 100
    i = np.arange(on)
    return (np.minimum(1.0, np.minimum(i / 150.0, (on - i) / 150.0)) * np.sin(2 * np.pi * 330 * i / LO) * 6000 / 32768).astype(np.float32)


def place(track, clip, at_s, rate, gain):
    """Adds clip into track starting at at_s seconds; whatever falls outside the recording is dropped."""
    start = int(round(at_s * rate))
    a, b = max(0, start), min(len(track), start + len(clip))
    if b <= a:
        return False
    track[a:b] += clip[a - start:b - start] * gain
    return True


def load(folder):
    """The session's rows, and its clock: log times are the glasses' local time."""
    rows = [json.loads(l) for l in open(os.path.join(folder, "session.jsonl")) if l.strip()]
    offset = next((r["utc_offset_min"] for r in rows if r.get("event") == "start" and "utc_offset_min" in r), None)
    if offset is None:
        offset = DEFAULT_OFFSET_MIN
        print("  %s: no utc_offset_min in the log, taking UTC%+d" % (os.path.basename(folder), offset // 60))
    return rows, dt.timezone(dt.timedelta(minutes=offset))


def add_session(folder, video_start, lo, hi, counts):
    rows, zone = load(folder)

    def t(stamp):   # a log time (the glasses' local clock) as seconds into the recording
        return dt.datetime.fromisoformat(stamp).replace(tzinfo=zone).timestamp() - video_start

    whole_run = os.path.exists(os.path.join(folder, "audio.pcm"))
    tick_from = None
    for i, r in enumerate(rows):
        ev = r.get("event")
        if ev == "spoke":
            name = r.get("audio")
            if not name or not os.path.exists(os.path.join(folder, name)):
                counts["spoken lines with no kept sound"] += 1
                continue
            clip = pcm16(os.path.join(folder, name))
            done = next((d for d in rows[i + 1:] if d.get("event") == "spoke_done" and d.get("photo") == r.get("photo")), None)
            if done and done.get("cut_off"):
                clip = clip[:max(0, int((t(done["done_at"]) - t(r["audio_at"])) * LO))]
            counts["spoken lines"] += place(lo, clip, t(r["audio_at"]), LO, VOICE)
        elif ev == "tone":
            counts["notes"] += place(lo, notes(r["freqs"], r["ms"], r["soft"]), t(r["at"]), LO, NOTES)
        elif ev == "tick_start":
            tick_from = t(r["at"])
        elif ev == "tick_stop" and tick_from is not None:
            at = tick_from
            while at < t(r["at"]):
                counts["thinking ticks"] += place(lo, tick(), at, LO, 1.0)
                at += 0.45
            tick_from = None
        elif ev == "beeps" and os.path.exists(os.path.join(folder, r["file"])):
            clip = pcm16(os.path.join(folder, r["file"]))
            clip = clip[:len(clip) // 2 * 2].reshape(-1, 2)
            counts["find runs (beeps)"] += place(hi, clip, t(r["at"]), HI, BEEPS)
        elif ev == "audio_start" and whole_run:
            counts["whole-run microphone"] += place(lo, pcm16(os.path.join(folder, "audio.pcm")), t(r["at"]), LO, MIC)
        elif r.get("question_audio") and not whole_run and os.path.exists(os.path.join(folder, r["question_audio"])):
            with wave.open(os.path.join(folder, r["question_audio"])) as w:
                clip = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
            counts["recorded questions"] += place(lo, clip, t(r["question_audio_start_at"]), LO, MIC)


def save_wav(path, track, rate):
    with wave.open(path, "wb") as w:
        w.setnchannels(1 if track.ndim == 1 else 2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((np.clip(track, -1, 1) * 32767).astype("<i2").tobytes())


def length_of(path):
    return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path]).decode().strip())


def sound(folders, start, length):
    """The whole sound track for a picture that starts at `start` (seconds since 1970) and runs `length` seconds."""
    lo = np.zeros(int(length * LO), dtype=np.float32)
    hi = np.zeros((int(length * HI), 2), dtype=np.float32)
    counts = {k: 0 for k in ("spoken lines", "spoken lines with no kept sound", "notes", "thinking ticks",
                             "find runs (beeps)", "recorded questions", "whole-run microphone")}
    for folder in folders:
        add_session(folder, start, lo, hi, counts)
    for k, v in counts.items():
        print("  %-32s %d" % (k, v))
    return lo, hi, counts


def mux(picture, lo, hi, out, copy):
    with tempfile.TemporaryDirectory() as tmp:
        save_wav(os.path.join(tmp, "lo.wav"), lo, LO)
        save_wav(os.path.join(tmp, "hi.wav"), hi, HI)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", picture, "-i", os.path.join(tmp, "lo.wav"), "-i", os.path.join(tmp, "hi.wav"),
                        "-filter_complex", "[1:a]aresample=%d,pan=stereo|c0=c0|c1=c0[v];[v][2:a]amix=inputs=2:normalize=0,alimiter=limit=0.95[a]" % HI,
                        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", out], check=True)
    print(out)


def display_frames(screen, screen_start, from_s):
    """The display alone, lifted out of the screen recording: (seconds since 1970, how strongly each
    pixel is display) for every recorder frame from `from_s` on. Once Sense has the camera the
    recorder's background is one still frame, so whatever is greener than a pixel's least-green
    moment is display. The recorder only writes a frame when the display changes."""
    pts = np.array([float(x) for x in subprocess.check_output(
        ["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts_time", "-of", "csv=p=0", screen]).decode().split()])
    pts.sort()
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", screen, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, SCREEN_H, SCREEN_W, 3)
    keep = (screen_start + pts[:len(frames)]) >= from_s
    frames, when = frames[keep], screen_start + pts[:len(frames)][keep]
    if not len(frames):
        return when, np.zeros((0, SCREEN_H, SCREEN_W), np.uint8)
    green = frames[..., 1].astype(np.int16) - np.maximum(frames[..., 0], frames[..., 2])
    settled = when >= from_s + 1.0     # the first second can still be the live camera
    base = green[settled].min(axis=0) if settled.any() else green.min(axis=0)
    alpha = (np.clip((green - base - 12) / 40.0, 0, 1) * 255).astype(np.uint8)
    alpha[:, STATUS_ROWS[0]:STATUS_ROWS[1]] = 0
    return when, alpha


def stitch(screen, screen_start, folders, folder, video, start, out):
    """Sense's camera video with the display from the screen recording on top, and the sound."""
    src = os.path.join(folder, video)
    length = length_of(src)
    when, alpha = display_frames(screen, screen_start, start)
    print("%s: %.1f s of camera video, %d display frames from the screen recording" % (video, length, len(when)))
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-vf", "fps=%d,scale=%d:%d" % (FPS, CAM_W, CAM_H), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           stdout=subprocess.PIPE)
    silent = out.replace(".mp4", ".silent.mp4")
    enc = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (CAM_W, CAM_H), "-r", str(FPS),
                            "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "19", silent], stdin=subprocess.PIPE)
    shown, layer, n = -1, None, 0
    while True:
        raw = dec.stdout.read(CAM_W * CAM_H * 3)
        if len(raw) < CAM_W * CAM_H * 3:
            break
        t = start + n / FPS
        n += 1
        i = int(np.searchsorted(when, t, side="right")) - 1     # the newest recorder frame by now: the display holds until it changes
        if i >= 0 and t > screen_start + length_of_screen[0]:
            i = -1                                               # the screen recording has ended
        if i != shown:
            shown = i
            layer = None if i < 0 else np.asarray(Image.fromarray(alpha[i]).resize((CAM_SPAN, CAM_H), Image.LANCZOS), dtype=np.float32)[..., None] / 255
        if layer is not None:
            frame = np.frombuffer(raw, np.uint8).reshape(CAM_H, CAM_W, 3).copy()
            part = frame[:, CAM_LEFT:CAM_LEFT + CAM_SPAN].astype(np.float32)
            frame[:, CAM_LEFT:CAM_LEFT + CAM_SPAN] = (part * (1 - layer) + DISPLAY_GREEN * layer).astype(np.uint8)
            raw = frame.tobytes()
        enc.stdin.write(raw)
    enc.stdin.close()
    enc.wait()
    dec.wait()
    lo, hi, _ = sound(folders, start, n / FPS)
    mux(silent, lo, hi, out, copy=True)
    os.remove(silent)


length_of_screen = [0.0]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    if len(args) < 2:
        sys.exit(__doc__)
    video, folders = args[0], [f.rstrip("/") for f in args[1:]]
    named = re.search(r"(\d{13})", os.path.basename(video))
    if "start" in opts:
        video_start = int(opts["start"]) / 1000
    elif named:
        video_start = int(named.group(1)) / 1000
    else:
        sys.exit("No 13-digit start time in the file name: give --start=EPOCH_MS")
    video_start -= float(opts.get("shift", 0))
    length = length_of(video)
    length_of_screen[0] = length
    print("screen recording: %.1f s from %s UTC" % (length, dt.datetime.fromtimestamp(video_start, dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S")))
    # Sense's own camera videos that overlap the screen recording: the live picture.
    camera = []
    for folder in folders:
        rows, zone = load(folder)
        for r in rows:
            if r.get("event") == "video_start" and os.path.exists(os.path.join(folder, r["file"])):
                at = dt.datetime.fromisoformat(r["at"]).replace(tzinfo=zone).timestamp()
                if at < video_start + length and at + length_of(os.path.join(folder, r["file"])) > video_start:
                    camera.append((folder, r["file"], at))
    base = opts.get("out") or os.path.splitext(video)[0] + ("-stitched.mp4" if camera else "-sound.mp4")
    if camera:
        for k, (folder, name, at) in enumerate(camera):
            stitch(video, video_start, folders, folder, name, at, base if k == 0 else base.replace(".mp4", "_%d.mp4" % (k + 1)))
        return
    lo, hi, counts = sound(folders, video_start, length)
    if not any(v for k, v in counts.items() if k != "spoken lines with no kept sound"):
        sys.exit("Nothing from these sessions falls inside the recording: check the session, or --start / --shift.")
    mux(video, lo, hi, base, copy=True)


if __name__ == "__main__":
    main()
