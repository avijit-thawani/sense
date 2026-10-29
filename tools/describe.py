"""Camera -> vision model -> spoken description.

Space = describe once.  l = live on/off.  r = cycle 640x480 / 800x600 / 320x240.
c = cycle mode: dedupe / none / last N phrases / phrases + last N photos.  q = quit.
Each run records photos + session.jsonl (prompt, answer, spoken or why not) to sessions/<time>/.

Live mode paces photos to speech: the next photo is taken about one model-latency
before the current sentence ends, so its answer arrives just as the speaker frees up.
With context the model may answer SILENT when nothing changed; silence costs no
speaking time, so the next photo follows immediately.
"""
import json
import os
import select
import subprocess
import sys
import termios
import threading
import time
import tty

from enhance import enhance
from orclient import MODEL, describe, spent_usd
from snap import connect, grab

PROMPT = ("You are the eyes of a blind person. Reply in 2-6 words, telegraphic: people and hazards "
          "first, otherwise the key object, plus position. Quote text only if clearly legible. "
          "No articles, no 'there is', no 'you are', no full sentences. Examples: "
          "'Person ahead, close.' 'Step down, left.' 'Bottle on table.' "
          "If too dark or blurry to be sure, reply exactly SILENT.")

# items mode: several things per photo; only ones not said recently are spoken, so a still
# scene yields new details instead of silence, without feeding past phrases to the model.
ITEMS_PROMPT = ("You are the eyes of a blind person. List up to 3 things you can see, most "
                "important first: people and hazards, then nearby objects. Each item 1-4 words "
                "with position, items separated by ';'. Quote text only if clearly legible. "
                "No articles, no filler, no full sentences. Example: "
                "'Person ahead, close; chair left; window behind'. "
                "If too dark or blurry to be sure, reply exactly SILENT.")
ITEM_MEMORY_S = 8            # an item said within this many seconds is not said again
ITEM_OVERLAP = 0.67          # "phone screen" vs "laptop screen" (0.5) are different things
MAX_ITEMS_SPOKEN = 2

CONTEXT_PROMPT = (" For reference only, you recently said: {said}."
                  " Describe only what is clearly visible in this image; never infer actions,"
                  " changes, or objects you cannot see. Reply exactly SILENT if your answer would"
                  " repeat those, or if the image is too dark or blurry to be sure.")
PHOTO_PROMPT = (" The first {n} images are earlier frames, oldest first; the last image is now. "
                "Mention movement or change if it matters.")
# dedupe: model sees only the current photo; we skip phrases too similar to recent ones.
# text / text+photos feed recent phrases (and frames) to the model; they can hallucinate.
MODES = ["items", "dedupe", "none", "text", "text+photos"]
N_CONTEXT = 3
DUP_OVERLAP = 0.5            # share of content words in common that counts as a repeat
# Position words don't make two phrases the same thing ("phone, right" vs "lamp, right").
STOPWORDS = set("a an the is are of on in at to and with near your you there this it its now some "
                "left right center centre ahead front close very far behind background foreground "
                "bottom top upper lower middle side straight forward slightly centered edge nearby below "
                "above".split())

# firmware/snapwifi USB commands; r cycles in this order. Over USB at 460800 baud:
# 1600x1200 1.76 s, 1024x768 ~0.7 s, 640x480 0.36 s. Model cost is the same for all.
SIZES = {"1600x1200": b"u", "1024x768": b"g", "640x480": b"v", "320x240": b"s"}
IN_FLIGHT = 1
SAY_WPM = 230
VOICE_STARTUP_S = 0.1        # persistent engine, measured Oct 28
LEAD_S = 1.1                 # camera + model time; updated from each answer (size-dependent)
SESSIONS = os.path.join(os.path.dirname(__file__), "..", "sessions")
session_dir = None           # sessions/YYYYMMDD-HHMMSS, set in main()

slots = threading.Semaphore(IN_FLIGHT)
lock = threading.Lock()
lock_log = threading.Lock()
newest_spoken = 0.0          # capture time of the newest answer handed to speech
pending = None               # (captured_at, text) waiting for the speaker
wake = threading.Event()
speech_ends = 0.0            # estimated time the current sentence finishes
in_flight = 0
mode = "items"
said_items = []              # (time, word set) of items spoken recently
enhance_method = "auto"      # brighten dim frames before the model sees them; None = raw
said = []                    # recent spoken phrases, oldest first
frames = []                  # recent photos, oldest first


def iso(t):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t)) + f".{int(t * 1000) % 1000:03d}"


def log_row(row):
    with lock_log, open(os.path.join(session_dir, "session.jsonl"), "a") as f:
        f.write(json.dumps(row) + "\n")


def words(text):
    return {w.strip(".,;:!?\"'").lower() for w in text.split()} - STOPWORDS - {""}


def is_repeat(text):
    new = words(text)
    return any(new and len(new & words(old)) / len(new) >= DUP_OVERLAP for old in said)


def decide(text, now):
    """Returns (status, text to speak) and remembers what is spoken. Call under `lock`."""
    if text.strip(" .\"'").upper() == "SILENT":
        return "silent", None
    if mode == "items":
        del said_items[:-20]
        recent = [w for t, w in said_items if now - t < ITEM_MEMORY_S]
        fresh = []
        for item in (i.strip(" .") for i in text.split(";")):
            w = words(item)
            if w and not any(len(w & old) / len(w) >= ITEM_OVERLAP for old in recent):
                fresh.append(item)
                recent.append(w)
        fresh = fresh[:MAX_ITEMS_SPOKEN]
        if not fresh:
            return "repeat", None
        said_items.extend((now, words(i)) for i in fresh)
        return "spoken", ". ".join(i[0].upper() + i[1:] for i in fresh) + "."
    if mode == "dedupe" and is_repeat(text):
        return "repeat", None
    said.append(text)
    del said[:-N_CONTEXT]
    return "spoken", text


def build_request(jpeg):
    prompt, images = (ITEMS_PROMPT if mode == "items" else PROMPT), [jpeg]
    if mode in ("text", "text+photos") and said:
        prompt += CONTEXT_PROMPT.format(said="; ".join(f'"{t}"' for t in said))
    if mode == "text+photos" and frames:
        prompt += PHOTO_PROMPT.format(n=len(frames))
        images = frames + [jpeg]
    return prompt, images


def ready_for_next():
    """Live mode: shoot only if nothing is queued and speech ends within LEAD_S."""
    with lock:
        return in_flight == 0 and pending is None and time.time() >= speech_ends - LEAD_S


def speaker():
    """Talks to one persistent speech engine (speak_server.py): ~0.1 s to first word, vs
    ~0.95 s for launching `say` per phrase. Logs when audio actually starts."""
    global pending, speech_ends
    if sys.platform != "darwin":
        return speaker_fallback()
    server = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), "speak_server.py"),
                               str(SAY_WPM)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    server.stdout.readline()  # "ready"

    def speak(text):
        t0 = time.time()
        server.stdin.write(text.replace("\n", " ") + "\n")
        server.stdin.flush()
        audio = t0
        for line in server.stdout:
            if line.startswith("audio "):
                audio = float(line.split()[1])
            elif line.strip() == "done":
                break
        return t0, audio

    while True:
        wake.wait()
        with lock:
            item, pending = pending, None
            wake.clear()
            if item:
                speech_ends = time.time() + VOICE_STARTUP_S + len(item[1].split()) * 60 / SAY_WPM
        if item:
            captured_at, text, photo = item
            launched, audio = speak(text)
            log_row({"photo": photo, "event": "spoke", "spoken_at": iso(launched),
                     "audio_at": iso(audio), "voice": "NSSpeechSynthesizer"})
            print(f"    heard {audio - captured_at:.1f}s after photo "
                  f"(voice startup {audio - launched:.2f}s)")


def speaker_fallback():
    """Windows / Linux: the OS voice, launched per phrase (slower to start than on a Mac)."""
    global pending, speech_ends
    while True:
        wake.wait()
        with lock:
            item, pending = pending, None
            wake.clear()
            if item:
                speech_ends = time.time() + 1.0 + len(item[1].split()) * 60 / SAY_WPM
        if not item:
            continue
        captured_at, text, photo = item
        launched = time.time()
        safe = text.replace("'", "")
        if sys.platform == "win32":
            cmd = ["powershell", "-NoProfile", "-Command",
                   "Add-Type -AssemblyName System.Speech; "
                   f"(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('{safe}')"]
        else:
            cmd = ["espeak", "-s", str(SAY_WPM), safe]
        log_row({"photo": photo, "event": "spoke", "spoken_at": iso(launched), "voice": cmd[0]})
        print(f"    speaking {launched - captured_at:.1f}s after photo")
        try:
            subprocess.run(cmd, check=False)
        except FileNotFoundError:
            print(f"    (no speech program found: {cmd[0]}) {text}")


def ask(jpeg, captured_at, cam_s, size, photo):
    global newest_spoken, pending, in_flight
    t_enh = time.time()
    if enhance_method:
        jpeg = enhance(jpeg, enhance_method)
    enhance_s = time.time() - t_enh
    with lock:
        prompt, images = build_request(jpeg)
        frames.append(jpeg)
        del frames[:-N_CONTEXT]
    try:
        text, model_s, cost = describe(
            images, prompt, log={"camera_s": round(cam_s, 2), "jpeg_bytes": len(jpeg),
                                 "size": size, "mode": mode, "n_images": len(images)})
    except Exception as e:
        print(f"error: {e}")
        return
    finally:
        with lock:
            in_flight -= 1
        slots.release()
    global LEAD_S
    total = time.time() - captured_at
    with lock:
        LEAD_S = 0.7 * LEAD_S + 0.3 * total
        if captured_at < newest_spoken:
            status, spoken_text = "stale", None
        else:
            status, spoken_text = decide(text, time.time())
        spoken = status == "spoken"
        if spoken:
            newest_spoken = captured_at
            pending = (captured_at, spoken_text, photo)
            wake.set()
    log_row({"photo": photo, "mode": mode, "size": size,
             "captured_at": iso(captured_at), "answered_at": iso(captured_at + total),
             "camera_s": round(cam_s, 2), "enhance": enhance_method,
             "enhance_s": round(enhance_s, 3), "model_s": round(model_s, 2),
             "cost_usd": cost, "status": status, "text": text, "spoken_text": spoken_text,
             "prompt": prompt})
    print(f"[camera {cam_s:.2f}s + model {model_s:.2f}s = {total:.2f}s, ${cost:.5f}] "
          f"{photo} {'' if spoken else f'({status}) '}{text}"
          f"{f'  -> {spoken_text}' if spoken and spoken_text != text else ''}")


def shoot(s, size):
    global in_flight
    slots.acquire()
    with lock:
        in_flight += 1
    t0 = time.time()
    try:
        jpeg = grab(s)
    except Exception as e:
        with lock:
            in_flight -= 1
        slots.release()
        s.reset_input_buffer()
        print(f"camera error: {e}")
        return
    cam_s = time.time() - t0
    photo = time.strftime("%H%M%S") + f"-{int(t0 * 10) % 10}.jpg"
    with open(os.path.join(session_dir, photo), "wb") as f:
        f.write(jpeg)
    threading.Thread(target=ask, args=(jpeg, t0, cam_s, size, photo), daemon=True).start()


def main():
    global mode, session_dir, enhance_method
    session_dir = os.path.join(SESSIONS, time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(session_dir)
    print(f"Recording to {os.path.relpath(session_dir)}")
    s = connect()
    threading.Thread(target=speaker, daemon=True).start()
    size = "640x480"  # 1600x1200 tested Oct 28: blurrier in dim light, 4.5 s lag, same model cost
    s.write(SIZES[size])
    time.sleep(0.3)
    s.reset_input_buffer()
    from orclient import ENV
    if not ENV.get("OPENROUTER_API_KEY", "").startswith("sk-or-"):
        print("\n  No OpenRouter key yet: photos work, but nothing will be described or spoken.\n"
              "  Paste your key into .env (GETTING_STARTED.md, step 6e), then restart.\n")
    print(f"Ready ({MODEL}, spent so far ${spent_usd():.4f}).")
    print(f"Space = once, l = live on/off, r = resolution, c = context ({mode}), "
          f"e = brighten ({'on' if enhance_method else 'off'}), q = quit.")
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    live = False
    try:
        tty.setcbreak(fd)
        while True:
            ready, _, _ = select.select([sys.stdin], [], [], 0.05 if live else None)
            key = sys.stdin.read(1) if ready else ""
            if key in ("q", "\x03"):
                break
            if key == "l":
                live = not live
                print("live ON" if live else "live OFF")
            if key == "r":
                order = list(SIZES)
                size = order[(order.index(size) + 1) % len(order)]
                s.write(SIZES[size])
                time.sleep(0.3)
                s.reset_input_buffer()
                print(f"resolution {size}")
            if key == "e":
                enhance_method = None if enhance_method else "auto"
                print(f"brighten {'on' if enhance_method else 'off'}")
            if key == "c":
                mode = MODES[(MODES.index(mode) + 1) % len(MODES)]
                print(f"context {mode}")
            if key == " " or (live and ready_for_next()):
                shoot(s, size)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)
        print(f"spent so far ${spent_usd():.4f}")


if __name__ == "__main__":
    main()
