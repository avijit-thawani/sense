"""Audition ElevenLabs voices for the glasses: one clip per voice and model, with time to first audio.

  python3 voice_audition.py                      # default shortlist, all fast models
  python3 voice_audition.py will:eleven_v4_turbo # chosen voice:model pairs

Method carried over from ../../../phoney/docs/latency.md: stream, time the first audio byte on a
connection that is already open (the app can keep one open; a new one costs extra), and let ears decide.
Clips go to ../notes/voices/round2/. Key: ELEVENLABS_API_KEY in ../../../.env (never printed).
Each clip uses about 70 credits on the half-price models.
"""

import http.client
import json
import struct
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "notes" / "voices" / "round2"
RATE = 24000
TEXT = ("Tap to describe, swipe forward to ask, swipe back to find, double tap to stop. "
        "Laptop on the right, books on the left. The mug is ahead, close.")

VOICES = {  # stock voices, mostly the ones ElevenLabs tags as conversational
    "sarah": "EXAVITQu4vr4xnSDxMaL", "jessica": "cgSgspJ2msm6clMCkdW9", "alice": "Xb7hH8MSUJpSbSDYk0k2",
    "river": "SAz9YHcvj6GT2YYXdXww", "will": "bIHbv24MWmeRgasZH58o", "chris": "iP95p4xoKVk53GoZ742B",
    "eric": "cjVigY5qzO86Huf0OWal", "roger": "CwhRBWXzGAHq8TQ4Fs17", "brian": "nPczCjzI2devNBz1zQrb",
    "george": "JBFqnCBsd6RMkjVDRZzb", "matilda": "XrExE9yKIg1WjnnlVkGX", "bella": "hpp4J3VqNfWAUOO0d1Us",
}
MODELS = ["eleven_flash_v2_5", "eleven_turbo_v2_5", "eleven_v4_turbo"]
DEFAULT_VOICES = ["jessica", "alice", "river", "will", "chris", "eric", "roger", "brian"]


def key() -> str:
    for line in (HERE.parents[2] / ".env").read_text().splitlines():
        if line.startswith("ELEVENLABS_API_KEY="):
            return line.split("=", 1)[1].strip().strip("\"'")
    sys.exit("ELEVENLABS_API_KEY not found")


def wav(pcm: bytes) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16) + b"data" + struct.pack("<I", len(pcm)) + pcm)


def main():
    pairs = [a.split(":") for a in sys.argv[1:]] or [[v, m] for m in MODELS for v in DEFAULT_VOICES]
    k = key()
    OUT.mkdir(parents=True, exist_ok=True)
    conn = http.client.HTTPSConnection("api.elevenlabs.io", timeout=30)
    conn.request("GET", "/v1/models", headers={"xi-api-key": k})   # free call, opens the connection
    conn.getresponse().read()
    rows = []
    for voice, model in pairs:
        path = OUT / f"{model.removeprefix('eleven_')}__{voice}.wav"
        if path.exists():
            continue
        t0 = time.time()
        try:
            conn.request("POST", f"/v1/text-to-speech/{VOICES[voice]}/stream?output_format=pcm_{RATE}",
                         body=json.dumps({"text": TEXT, "model_id": model}),
                         headers={"xi-api-key": k, "Content-Type": "application/json"})
            r = conn.getresponse()
            if r.status != 200:
                print(f"  {model:20} {voice:8} HTTP {r.status} {r.read()[:160]!r}")
                continue
            first = r.read(2048)
            ttfb = time.time() - t0
            pcm = first + r.read()
        except (http.client.HTTPException, OSError) as e:
            print(f"  {model:20} {voice:8} {type(e).__name__}: {e}")
            conn = http.client.HTTPSConnection("api.elevenlabs.io", timeout=30)
            continue
        total = time.time() - t0
        path.write_bytes(wav(pcm))
        rows.append({"model": model, "voice": voice, "first_audio_s": round(ttfb, 2), "all_audio_s": round(total, 2),
                     "clip_s": round(len(pcm) / 2 / RATE, 1)})
        print(f"  {model:20} {voice:8} first audio {ttfb:.2f}s, all {total:.2f}s, clip {len(pcm) / 2 / RATE:.1f}s")
    if rows:
        with (OUT / "results.jsonl").open("a") as f:
            f.writelines(json.dumps(r) + "\n" for r in rows)


if __name__ == "__main__":
    main()
