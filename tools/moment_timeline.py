"""One hold-to-ask moment as overlays on the photo: a lap timer and parallel timelines.

  stills SESSION_DIR N T1,T2,...   frames of the Nth ask in the session at those seconds after the press
  video  SESSION_DIR N OUT.mp4     the moment as a video (question re-voiced, answer when it was spoken)

Rows run in parallel: you (speaking), camera (photo over Wi-Fi), speech-to-text, model, voice.
Steps the phone doesn't log yet are estimated and drawn with a "~" and a dashed outline:
release = answer received - model time - 0.3 s speech-to-text (until press/release/stt are logged).
"""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Helvetica.ttc"
W, H = 960, 720                   # the photo is 4:3; everything is drawn over it
FPS = 20
STT_EST_S = 0.3
SAY_WPM = 230
COLORS = {"you": (255, 204, 0), "camera": (90, 200, 250), "speech-to-text": (190, 140, 255),
          "model": (255, 140, 60), "voice": (90, 220, 120)}


def font(size, bold=False):
    return ImageFont.truetype(FONT, size, index=1 if bold else 0)


def ts(s):
    return dt.datetime.fromisoformat(s).timestamp()


def load(folder, n):
    rows = [json.loads(l) for l in open(os.path.join(folder, "session.jsonl"))]
    asks = [r for r in rows if r.get("mode") == "ask"]
    r = asks[n]
    spoke = next((x for x in rows if x.get("event") == "spoke" and x["photo"] == r["photo"]), {})
    done = next((x for x in rows if x.get("event") == "spoke_done" and x["photo"] == r["photo"]), {})
    if r.get("marks") and r.get("release_at") and spoke.get("audio_at"):
        # Fully logged (phone builds from Oct 30 11:05). "you" = press to release: the recognizer's
        # speech-begin mark arrives ~1.6 s late, around the release, so it isn't used.
        p = ts(r["press_at"])
        mk = {k: ts(v) - p for k, v in r["marks"].items()}
        rel, stt = ts(r["release_at"]) - p, ts(r["stt_at"]) - p
        voice_at = ts(spoke["audio_at"]) - p
        voice_end = ts(done["done_at"]) - p if done.get("done_at") else None
        audio = None
        if r.get("question_audio") and os.path.exists(os.path.join(folder, r["question_audio"])):
            audio = (os.path.join(folder, r["question_audio"]), ts(r["question_audio_start_at"]) - p)
        return {"photo": os.path.join(folder, r["photo"]), "question": r["question"],
                "answer": r.get("spoken_text") or r["text"], "est": False, "voice_end": voice_end,
                "question_audio": audio,
                "steps": [("you", "holding, speaking", 0.0, rel, False),
                          ("camera", "photo over Wi-Fi", mk["photo_requested"], mk["photo_in_hand"], False),
                          ("speech-to-text", "final words", rel, stt, False),
                          ("model", "request to answer", mk["request_sent"], mk["answer_received"], False),
                          ("voice", "speaking answer", voice_at, voice_end, voice_end is None)]}
    press = ts(r["press_at"]) if r.get("press_at") else ts(r["captured_at"])
    answered = ts(r["answered_at"]) - press
    model = r["model_s"]
    est = not (r.get("release_at") and r.get("stt_at"))
    stt_end = (ts(r["stt_at"]) - press) if not est else answered - model
    release = (ts(r["release_at"]) - press) if not est else stt_end - STT_EST_S
    voice_at = (ts(spoke["audio_at"]) - press) if spoke.get("audio_at") else answered + 0.1
    return {"photo": os.path.join(folder, r["photo"]), "question": r["question"],
            "answer": r.get("spoken_text") or r["text"], "est": est,
            "steps": [  # (row, label, start, end, estimated)
                ("you", "speaking", 0.0, release, est),
                ("camera", "photo over Wi-Fi", 0.0, r["camera_s"], False),
                ("speech-to-text", "final words", release, stt_end, est),
                ("model", "request to answer", stt_end, answered, False),
                ("voice", "speaking answer", voice_at, None, True)]}  # start logged; length is the video voice's


def with_voice(m, dur):
    steps = [list(s) for s in m["steps"]]
    if steps[-1][3] is None:              # phone's voice length not logged: use the video voice's
        steps[-1][3] = steps[-1][2] + dur
    return {**m, "steps": [tuple(s) for s in steps]}


def dashed_rect(d, box, color, width=2, dash=6):
    x0, y0, x1, y1 = box
    for x in range(int(x0), int(x1), dash * 2):
        d.line((x, y0, min(x + dash, x1), y0), fill=color, width=width)
        d.line((x, y1, min(x + dash, x1), y1), fill=color, width=width)
    for y in range(int(y0), int(y1), dash * 2):
        d.line((x0, y, x0, min(y + dash, y1)), fill=color, width=width)
        d.line((x1, y, x1, min(y + dash, y1)), fill=color, width=width)


def frame(t, m, photo):
    img = photo.copy().convert("RGBA")
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    steps = m["steps"]
    end = max(s[3] for s in steps) + 0.4

    # question banner (top right) while the moment lasts
    fq = font(24, True)
    q = f"\u201c{m['question']}\u201d"
    qw = d.textlength(q, font=fq)
    d.rounded_rectangle((W - qw - 40, 14, W - 14, 54), 10, fill=(255, 204, 0, 235))
    d.text((W - qw - 27, 20), q, font=fq, fill=(0, 0, 0))

    # milestone pop-up (top centre): the latest event, shown for 1.2 s after it happens
    events = [(steps[1][3], f"photo received  {steps[1][3] - steps[1][2]:.2f} s"),
              (steps[0][3], f"{'~' if steps[0][4] else ''}you let go"),
              (steps[2][3], f"{'~' if steps[2][4] else ''}words final"),
              (steps[3][2], "request sent"),
              (steps[3][3], "answer received"),
              (steps[4][2], "voice starts")]
    # latency badge under the question, from the moment the voice starts: let go -> first word
    voice_at, released = steps[-1][2], steps[0][3]
    if t >= voice_at:
        fl = font(22, True)
        lat = f"latency {'~' if steps[0][4] else ''}{voice_at - released:.2f} s"
        sub = "from letting go to first word"   # Helvetica has no arrow glyph
        lw, sw = d.textlength(lat, font=fl), d.textlength(sub, font=font(14))
        bw = max(lw, sw)
        d.rounded_rectangle((W - bw - 42, 62, W - 14, 118), 12, fill=COLORS["voice"] + (240,))
        d.text((W - lw - 28, 66), lat, font=fl, fill=(0, 0, 0))
        d.text((W - sw - 28, 96), sub, font=font(14), fill=(20, 40, 20))

    recent = [e for e in events if e[0] <= t < e[0] + 1.2]
    if recent:
        msg = max(recent)[1]
        fm = font(20, True)
        mw = d.textlength(msg, font=fm)     # right-aligned under the question (and badge), clear of the lap timer
        y = 128 if t >= voice_at else 66
        d.rounded_rectangle((W - mw - 42, y, W - 14, y + 34), 16, fill=(255, 255, 255, 230))
        d.text((W - mw - 28, y + 6), msg, font=fm, fill=(20, 20, 20))

    # lap timer (top left): total since press, then a line per step in the order steps finish
    started = [s for s in steps if s[2] <= t]
    lap_h = 36 + 26 * len(started) + (18 if m["est"] else 0)
    d.rounded_rectangle((14, 14, 330, 14 + lap_h + 8), 10, fill=(0, 0, 0, 185))
    d.text((26, 18), f"{min(t, end):5.2f} s", font=font(30, True), fill=(255, 255, 255))
    d.text((150, 30), "since press", font=font(16), fill=(190, 190, 190))
    if m["est"]:
        d.text((26, 14 + lap_h - 14), "~ = estimated (not yet logged)", font=font(12), fill=(170, 170, 170))
    order = lambda s: (s[3] if s[3] is not None and t >= s[3] else 1e9, s[2])
    for i, (row, label, a, b, est) in enumerate(sorted(started, key=order)):
        y = 58 + 26 * i
        done = b is not None and t >= b
        dur = (b - a) if done else (t - a)
        c = COLORS[row]
        d.ellipse((26, y + 5, 38, y + 17), fill=c if done else None, outline=c, width=2)
        d.text((46, y), f"{row}", font=font(17, True), fill=c)
        d.text((180, y), f"{'~' if est else ' '}{dur:4.2f} s", font=font(17, done), fill=(255, 255, 255) if done else (200, 200, 200))
        if not done:
            d.text((262, y), "…", font=font(17), fill=(200, 200, 200))

    # answer caption, once the voice starts
    voice = steps[-1]
    if t >= voice[2]:
        fa = font(26, True)
        words, lines, line = m["answer"].split(), [], ""
        for w in words:
            if d.textlength(f"{line} {w}".strip(), font=fa) > W - 60 and line:
                lines.append(line); line = w
            else:
                line = f"{line} {w}".strip()
        lines.append(line)
        top = H - 128 - 34 * len(lines)
        d.rectangle((0, top - 10, W, top + 34 * len(lines)), fill=(0, 0, 0, 200))
        for i, l in enumerate(lines):
            d.text((30, top + 34 * i), l, font=fa, fill=(255, 255, 255))

    # two timelines (bottom band): "you" (the hold) and "device" (camera, speech-to-text, model and
    # voice run one after another, coloured as in the lap timer); bars grow as time passes
    band_top, row_h, x0, x1 = H - 106, 40, 96, W - 24
    d.rectangle((0, band_top - 8, W, H), fill=(10, 10, 14, 228))
    sx = lambda s: x0 + (x1 - x0) * s / end
    for k in range(0, int(end) + 1):
        d.line((sx(k), band_top, sx(k), H - 14), fill=(70, 70, 80, 255), width=1)
        d.text((sx(k) - 6, H - 16), f"{k}s", font=font(11), fill=(130, 130, 140))
    lanes = [("you", COLORS["you"], steps[:1]), ("device", (220, 220, 225), steps[1:])]
    for i, (name, name_color, lane) in enumerate(lanes):
        y = band_top + 4 + row_h * i
        d.text((14, y + 9), name, font=font(16, True), fill=name_color)
        for row, label, a, b, est in lane:
            if t < a:
                continue
            c = COLORS[row]
            right = min(t, b) if b is not None else t
            box = (sx(a), y + 4, max(sx(right), sx(a) + 3), y + row_h - 6)
            if est:
                d.rectangle(box, fill=c + (90,))
                dashed_rect(d, box, c)
            else:
                d.rectangle(box, fill=c + (230,))
            txt = f"{'~' if est else ''}{right - a:.2f}s"
            if box[2] - box[0] > d.textlength(txt, font=font(13)) + 8:
                d.text((box[0] + 4, y + 11), txt, font=font(13, True), fill=(0, 0, 0) if not est else (255, 255, 255))
    d.line((sx(min(t, end)), band_top - 4, sx(min(t, end)), H - 18), fill=(255, 255, 255, 255), width=2)
    return Image.alpha_composite(img, over).convert("RGB")


def say(text, voice, rate, path):
    subprocess.run(["say", *(["-v", voice] if voice else []), "-r", str(rate), "-o", path, text], check=True)
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                 path], capture_output=True, text=True).stdout)


def main():
    cmd, folder, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
    m = load(folder, n)
    tmp = tempfile.mkdtemp()
    dur = say(m["answer"], None, SAY_WPM, os.path.join(tmp, "a.aiff"))
    if m.get("voice_end"):            # match the phone's logged speaking time
        target = m["voice_end"] - m["steps"][-1][2]
        dur = say(m["answer"], None, int(SAY_WPM * dur / max(target, 0.5)), os.path.join(tmp, "a.aiff"))
    m = with_voice(m, dur)
    photo = Image.open(m["photo"]).convert("RGB").resize((W, H))
    if cmd == "stills":
        out_dir = os.path.join(os.path.dirname(__file__), "..", "videos", "timeline_stills")
        os.makedirs(out_dir, exist_ok=True)
        for t in (float(x) for x in sys.argv[4].split(",")):
            path = os.path.join(out_dir, f"ask{n}_t{t:04.1f}.jpg")
            frame(t, m, photo).save(path, quality=88)
            print(path)
    elif cmd == "video":
        out = sys.argv[4]
        end = max(s[3] for s in m["steps"]) + 1.0
        video = os.path.join(tmp, "v.mp4")
        ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                               "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264",
                               "-pix_fmt", "yuv420p", video], stdin=subprocess.PIPE)
        for i in range(int(end * FPS)):
            ff.stdin.write(frame(i / FPS, m, photo).tobytes())
        ff.stdin.close()
        ff.wait()
        aa = int(m["steps"][-1][2] * 1000)
        if m.get("question_audio"):              # the user's own recorded voice, at its logged offset
            qfile, start = m["question_audio"]
            qa, qfilter = int(max(start, 0) * 1000), "loudnorm=I=-18:TP=-2"
        else:
            qfile = os.path.join(tmp, "q.aiff")
            qdur = say(m["question"], "Samantha", 200, qfile)
            # with the hold logged, stretch the re-voiced question to end just before the release
            tempo = 1.0 if m["est"] else min(2.0, max(0.5, qdur / max(m["steps"][0][3] - 0.4, 0.3)))
            qa, qfilter = int(0.3 * 1000), f"atempo={tempo:.3f}"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", video, "-i", qfile,
                        "-i", os.path.join(tmp, "a.aiff"), "-filter_complex",
                        f"[1:a]{qfilter},adelay={qa}|{qa}[q];[2:a]adelay={aa}|{aa}[a];"
                        "[q][a]amix=inputs=2:normalize=0[o]",
                        "-map", "0:v", "-map", "[o]", "-c:v", "copy", "-c:a", "aac", out], check=True)
        print(out)


if __name__ == "__main__":
    main()
