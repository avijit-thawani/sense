# What we've learned (Oct 28 2026, ESP32-CAM)

Raw timings: [benchmarks.md](benchmarks.md). Sessions and videos named here are local
(gitignored); one example video is in [media/](media/).

## Latency

| Step | Before | Now | How |
|---|---|---|---|
| Photo over USB | ~3 s | **0.36 s** | board drops its stale frame (was: send 3, keep 1); 460800 baud; JPEG quality 10; 640×480 (~11 KB) |
| Model | 1.2 s | **~1.0–1.2 s** | `reasoning: none`, `provider.sort: latency`; cost is the same at every photo size (1,156 tokens) |
| Pacing | sequential | **paced to speech** | next photo one camera+model time before the phrase ends; with 2 in flight only 1 in 3 was spoken |
| Speech | `say` per phrase, ~0.95 s to start | **~0.1 s** | one speech engine kept loaded (`tools/speak_server.py`); Android TTS ~0.3 s |

End to end, photo taken → voice heard: **~1.7 s on the Mac, ~1.9 s on the phone** (640×480).

## Photo size and light

- 1600×1200 over USB took 2.95 s live and came out motion-blurred: in a dim room the sensor
  slows down. 640×480 is the sweet spot; 1024×768 is the most worth trying above it.
- **Local brightening** (`tools/enhance.py` `auto`: contrast stretch + gamma lift, ~1 ms) fixed
  dark-room misreads ("White controller" → "Pillow, top left. Tablet, right.") and found a
  missed doorway. Histogram equalization (`eq`) is noisier and invented a hazard
  ("Sharp knife, right" on a pillow): avoid.
- More light still beats any software fix.

## Prompt and context

- **Speech is the bottleneck**, so short phrases beat a faster model.
- **Feeding back what was said makes it hallucinate.** With the last phrases and "don't
  repeat", the model invented a story across dark frames ("package now open… holding the
  headphones"). The model now sees only the current photo; repeats are filtered on the device.
- **Items mode (default):** the model lists up to 3 things; up to 2 not said in the last 8 s
  are spoken (position words ignored, ≥67% word overlap counts as the same thing). A still
  scene yields new details instead of silence. Synonyms still slip through ("power box /
  brick / adapter").
- **One thing per photo** (the earlier telegraphic prompt) left long silences: 21 of 40 photos
  skipped as repeats, gaps up to 15.7 s.
- **More descriptive prompts** count fingers and read colours but miscount hands cut off at the
  frame edge, and longer phrases add ~0.8 s of delay.
- "Quote readable text" on its own invented text ("ETERNITY HOLOGRAPH" on a blurry page);
  "quote text only if clearly legible" fixed it. Without "people first" it missed a face.
- Photo context (last 3 frames) costs 4× and was not clearly better.

## Models

On the same photo: gemini-3.1-flash-lite 0.88 s (default, ~$0.0003/photo); claude-haiku-5.5
0.80 s but ignores word limits; qwen3.7-flash 1.7 s; gemini-3.5-flash-lite and gpt-5-nano
refuse `reasoning: none`.

## Judging videos with a video model

`tools/judge_video.py` has gemini-4-argon watch a session video with audio (~$0.01–0.04).
Its verdicts matched hand checks, and it is fallible on dark frames: it twice called a mirror
an ironing board (the user confirmed it is a mirror). The user's knowledge of the room wins.

| Video | Judge |
|---|---|
| Text context (hallucinated story) | 1/10 |
| One thing per photo | 6–8/10, but quiet |
| Items mode + brightening (Mac) | 8/10, 34 correct / 2 wrong / 0 missed |
| Phone app, first walk | 8/10, keeping up 5/5 |

## Wi-Fi

- The board joins any known 2.4 GHz network; names are case-sensitive.
- Our home router isolates clients: Mac ↔ board and Mac ↔ phone fail on home Wi-Fi.
- On the phone's hotspot it works: 640×480 in 0.33 s, as fast as USB; faster than USB at
  large sizes (1600×1200 in 1.14 s vs 1.76 s).
- Opening the USB serial port resets the board; it rejoins Wi-Fi in ~3 s.
