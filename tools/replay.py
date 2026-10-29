"""Replays a session's photos through the current prompt and mode logic, in order,
and writes the result as a session folder (DIR-replay-MODE/) for session_video.py.

Usage: replay.py MODE DIR [FIRST LAST] [--cached] [--prompt=TEXT --tag=NAME]
  replay.py dedupe ../sessions/20261028-202406
  --cached reuses answers already in ledger.jsonl for this mode and photo (free).
  --prompt overrides describe.PROMPT; --tag names the output (DIR-replay-MODE-TAG/).
  --enhance=auto|eq brightens each photo locally first (see enhance.py); the output folder
  keeps the enhanced photos, since that is what the model saw.

Timing keeps each photo's original capture time and camera time; the model time is
the replay's own, so lag stays realistic.
"""
import datetime as dt
import glob
import json
import os
import shutil
import sys

import describe as d
from enhance import enhance
from orclient import LEDGER, describe

args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
cached = "--cached" in sys.argv
if "prompt" in opts:
    d.PROMPT = d.ITEMS_PROMPT = opts["prompt"]
d.mode, folder = args[0], args[1].rstrip("/")
first, last = (args[2], args[3]) if len(args) > 3 else ("", "z")

source = {}
if os.path.exists(os.path.join(folder, "session.jsonl")):
    for line in open(os.path.join(folder, "session.jsonl")):
        r = json.loads(line)
        if "captured_at" in r and not r.get("event"):
            source[r["photo"]] = r
ledger = {}
if cached:
    for line in open(LEDGER):
        r = json.loads(line)
        if r.get("replay") and r.get("mode") == d.mode and r.get("tag") == opts.get("tag"):
            ledger[r["photo"]] = r

out = f"{folder}-replay-{d.mode}" + (f"-{opts['tag']}" if "tag" in opts else "")
os.makedirs(out, exist_ok=True)
rows = []
paths = sorted(p for p in glob.glob(os.path.join(folder, "*.jpg"))
               if first <= os.path.basename(p)[-10:-4] <= last)
for path in paths:
    base = os.path.basename(path)
    name = base[-10:] if base.startswith("desc-") else base   # old photos/desc-YYYYMMDD-HHMMSS.jpg
    jpeg = open(path, "rb").read()
    if "enhance" in opts:
        jpeg = enhance(jpeg, opts["enhance"])
    prompt, images = d.build_request(jpeg)
    d.frames.append(jpeg)
    del d.frames[:-d.N_CONTEXT]
    if name in ledger:
        text, secs, cost = ledger[name]["text"], ledger[name]["latency_s"], 0.0
    else:
        text, secs, cost = describe(images, prompt, log={"replay": True, "mode": d.mode, "photo": name,
                                                         "tag": opts.get("tag")})
    src = source.get(name)
    captured = dt.datetime.fromisoformat(src["captured_at"]) if src else dt.datetime.now()
    answered = captured + dt.timedelta(seconds=((src or {}).get("camera_s") or 0) + secs)
    status, spoken_text = d.decide(text, answered.timestamp())
    print(f"{name}  {'' if status == 'spoken' else f'({status}) '}{text}"
          f"{f'  -> {spoken_text}' if spoken_text and spoken_text != text else ''}")
    if src:
        with open(os.path.join(out, name), "wb") as f:
            f.write(jpeg)
        rows.append({"photo": name, "mode": d.mode, "captured_at": src["captured_at"],
                     "answered_at": answered.isoformat(timespec="milliseconds"),
                     "camera_s": src.get("camera_s"), "model_s": secs, "cost_usd": cost,
                     "status": status, "text": text, "spoken_text": spoken_text,
                     "prompt": prompt, "replay_of": folder})
with open(os.path.join(out, "session.jsonl"), "w") as f:
    f.writelines(json.dumps(r) + "\n" for r in rows)
print(f"wrote {out}/ ({len(rows)} rows)")
