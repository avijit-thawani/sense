"""Turns a recorded run (./sense-demo.sh record) into demo clips: the glasses' own video and sound,
with what the wearer was shown and told drawn on top.

  ../../.venv-tools/bin/python demo_video.py ../notes/sessions/<session> [START LENGTH]

START and LENGTH (seconds from the start of the video) cut out one stretch of a long run.

One demo_N.mp4 per video_N.mp4 in the session, written next to it. Drawn on the video:
  bottom bar   what the glasses were doing (looking, listening), then the question and the answer
  green box    find mode: the box the display was showing at that moment (so it trails by the delay)
The sound is the glasses' own microphone: the wearer's voice, and the beeps and spoken answers as
the microphone picked them up from the speakers.
"""
import datetime as dt
import json
import os
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Helvetica.ttc"
GREEN, YELLOW, WHITE, GREY = (90, 220, 120), (255, 204, 0), (255, 255, 255), (170, 170, 170)


def font(size, bold=False):
    return ImageFont.truetype(FONT, size, index=1 if bold else 0)


def ts(s):
    return dt.datetime.fromisoformat(s).timestamp()


def load(folder):
    rows = [json.loads(l) for l in open(os.path.join(folder, "session.jsonl"))]
    videos, audio_start, captions, boxes = [], None, [], []
    for r in rows:
        ev = r.get("event")
        if ev == "video_start":
            videos.append({"file": r["file"], "start": ts(r["at"])})
        elif ev == "audio_start":
            audio_start = ts(r["at"])
        elif ev == "find_start":
            captions.append((ts(r["at"]), "find", "Finding: " + r["target"], ""))
        elif ev == "find_stop":
            captions.append((ts(r["at"]), "idle", "", ""))
            boxes.append((ts(r["at"]), None, ""))
        elif r.get("mode") in ("describe", "ask"):
            press = ts(r["press_at"])
            captions.append((press, "busy", "Listening..." if r["mode"] == "ask" else "Looking...", ""))
            if r.get("release_at"):
                captions.append((ts(r["release_at"]), "busy", "Thinking...", ""))
            if "error" in r:
                captions.append((press + 0.5, "answer", "Failed", r["error"][:80]))
            else:
                note = "answer %.1f s after the %s" % (
                    ts(r["answered_at"]) - (ts(r["release_at"]) if r.get("release_at") else press),
                    "question" if r.get("release_at") else "tap")
                q = ('"%s"   ' % r["question"]) if r.get("question") else ""
                captions.append((ts(r["answered_at"]), "answer", r["text"], q + note))
        elif r.get("mode") == "find" and "error" not in r:
            boxes.append((ts(r["answered_at"]), r.get("box"), r.get("where", ""), ts(r["press_at"])))
    return videos, audio_start, sorted(captions), sorted(boxes, key=lambda b: b[0])


def make(folder, video, audio_start, captions, boxes, clip=None):
    src = os.path.join(folder, video["file"])
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height,avg_frame_rate,nb_frames", "-of", "json", src], capture_output=True, text=True)
    st = json.loads(probe.stdout)["streams"][0]
    w, h = st["width"], st["height"]
    num, den = st["avg_frame_rate"].split("/")
    fps = float(num) / float(den)
    bar = 150
    out = os.path.join(folder, video["file"].replace("video_", "demo_"))
    cut = []
    if clip:      # only this stretch of the video: (seconds from its start, length)
        out = out.replace(".mp4", "_%d.mp4" % clip[0])
        cut = ["-ss", str(clip[0]), "-t", str(clip[1])]
        video = dict(video, start=video["start"] + clip[0])
    dec = subprocess.Popen(["ffmpeg", "-loglevel", "error"] + cut + ["-i", src, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    silent = out.replace(".mp4", ".silent.mp4")
    enc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (w, h + bar),
                            "-r", "%.4f" % fps, "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", silent], stdin=subprocess.PIPE)
    n = 0
    while True:
        raw = dec.stdout.read(w * h * 3)
        if len(raw) < w * h * 3:
            break
        t = video["start"] + n / fps
        n += 1
        frame = Image.new("RGB", (w, h + bar), (0, 0, 0))
        frame.paste(Image.frombytes("RGB", (w, h), raw), (0, 0))
        d = ImageDraw.Draw(frame)
        cap = [c for c in captions if c[0] <= t]
        cap = cap[-1] if cap else None
        finding = cap is not None and cap[1] == "find"
        if finding:
            seen = [b for b in boxes if b[0] <= t and len(b) == 4 and b[0] >= cap[0]]
            if seen:
                b = max(seen, key=lambda x: x[3])     # the newest photo's answer, as on the glasses
                if b[1]:
                    y0, x0, y1, x1 = [v / 1000 for v in b[1]]
                    d.rectangle((x0 * w, y0 * h, x1 * w, y1 * h), outline=GREEN, width=6)
                    d.text((x0 * w + 4, max(0, y0 * h - 34)), "%.1f s old" % (t - b[3]), font=font(26, True), fill=GREEN)
                    d.text((20, h + 62), b[2], font=font(30), fill=WHITE)
                else:
                    d.text((20, h + 62), "not in view", font=font(30), fill=GREY)
            d.text((20, h + 14), cap[2], font=font(36, True), fill=YELLOW)
        elif cap and cap[2]:
            lines = textwrap.wrap(cap[2], 62)[:2]
            color = WHITE if cap[1] == "answer" else YELLOW
            for i, line in enumerate(lines):
                d.text((20, h + 12 + i * 44), line, font=font(36, True), fill=color)
            if cap[3]:
                d.text((20, h + 108), cap[3][:110], font=font(24), fill=GREY)
        enc.stdin.write(frame.tobytes())
    enc.stdin.close()
    enc.wait()
    dec.wait()
    pcm = os.path.join(folder, "audio.pcm")
    if audio_start is not None and os.path.exists(pcm):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", silent, "-f", "s16le", "-ar", "16000", "-ac", "1",
                        "-ss", "%.3f" % max(0.0, video["start"] - audio_start), "-i", pcm,
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "96k", "-shortest", out], check=True)
        os.remove(silent)
    else:
        os.replace(silent, out)
    print("%s  %.0f s, %d frames at %.0f a second" % (out, n / fps, n, fps))
    return out


if __name__ == "__main__":
    folder = sys.argv[1]
    videos, audio_start, captions, boxes = load(folder)
    if not videos:
        sys.exit("No video in this session. Record one with ./sense-demo.sh record")
    clip = (float(sys.argv[2]), float(sys.argv[3])) if len(sys.argv) > 3 else None
    for v in videos:
        if os.path.exists(os.path.join(folder, v["file"])):
            make(folder, v, audio_start, captions, boxes, clip)
