# Working on sense

Setup from unboxing: [GETTING_STARTED.md](../GETTING_STARTED.md). Phone app build and config:
[phone/README.md](../phone/README.md). Evals: [evals.md](evals.md).

## How it works

```text
board (camera) --USB or Wi-Fi--> laptop or phone --> brighten --> Gemini Flash Lite
                                                                       |
                         earphones <-- voice <-- only things not said in the last 8 s
```

- The board runs a small program that waits for "take a photo" and sends one JPEG back.
- Live mode asks the model for up to 3 things, most important first (people and hazards), with
  position, in a few words each, or SILENT if the photo is too dark to be sure. Up to 2 new things
  are spoken; a phrase is never cut off, and the next photo is timed so its answer arrives as the
  current phrase ends.
- Describe (one tap) uses `tools/prompts/moment_v4.txt`; hold to ask uses `ask_app_v1.txt`.
- About ₹0.04 ($0.00045) per photo with Gemini 3.1 Flash Lite at 1024×768. Spending is capped.

## The loop

**1. Live:** walk around with it.

```bash
cd tools && ../.venv-tools/bin/python describe.py
```

Keys: **l** live on/off, **Space** describe once, **r** photo size, **e** brightening on/off,
**c** mode, **q** quit. On the phone: tap Describe, hold to ask, or Live in Settings.

Every run is saved to `sessions/<date-time>/`: the photos, and `session.jsonl` with the prompt,
the answer, whether it was spoken (or why not), and when the photo was taken, answered and heard.
Phone sessions: `adb pull /sdcard/Android/data/com.sense.app/files/sessions/ sessions-phone/`.

**2. Offline:** try a different prompt, model or setting on a recorded session.

```bash
../.venv-tools/bin/python replay.py items ../sessions/<dir> --enhance=auto   # ~$0.0003/photo
../.venv-tools/bin/python replay.py items ../sessions/<dir> --prompt="..." --tag=test
```

Add `--cached` to reuse answers already logged (free). Each replay writes a new session folder.

**3. Feel it:** turn a session into a video (free, local voice); optionally have a video model
review it:

```bash
../.venv-tools/bin/python session_video.py ../sessions/<dir> ../videos/<name>.mp4 "Title"
../.venv-tools/bin/python moment_timeline.py video ../sessions-phone/<dir> <n> ../videos/<name>.mp4
../.venv-tools/bin/python judge_video.py ../videos/<name>.mp4       # ~$0.02
```

`session_video.py`: 1280×720; left, the photo as taken with a timer; right, the photo being
described and how its delay split (camera, model, waiting, voice start); bottom, the spoken words.
Idle gaps over 6 s are cut to 1.5 s. `moment_timeline.py`: one hold-to-ask moment with a lap timer,
milestones, a latency badge and you/device timelines (the same view as the app's History replay);
plays the recorded question when `record_question_audio` was on.
