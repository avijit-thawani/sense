"""Has a strong video model watch session videos and grade them as a blind user's aid.

Usage: judge_video.py VIDEO.mp4 [VIDEO.mp4 ...] [--model=google/gemini-4-argon]
Writes videos/<name>.judge.json next to each video; costs go to ledger.jsonl.
"""
import base64
import json
import os
import sys

from orclient import call

RUBRIC = """You are evaluating an assistive device for blind people. This video replays a real
session: the left side shows each camera photo as it was taken; the right panel shows which
photo the voice is currently describing; the caption and audio are what the user heard.

Judge only what the user HEARD against what the photo being described actually shows.
Return JSON only, with these keys:
- "phrases": list of {"t": seconds into the video, "said": text, "verdict": "correct" |
  "vague" | "wrong" | "hallucinated", "note": short reason}
- "missed": list of {"t", "what"}: important things on camera (people, hazards, steps,
  doors, legible text) the voice never mentioned
- "verbosity": 1-5 (1 = terse and efficient, 5 = wordy)
- "timeliness": 1-5 (5 = speech keeps up with the camera)
- "usefulness": 1-10 for a blind person walking through this space
- "summary": two sentences
Be strict: a confident wrong statement is worse than silence."""


def judge(path, model):
    video = "data:video/mp4;base64," + base64.b64encode(open(path, "rb").read()).decode()
    content = [{"type": "text", "text": RUBRIC},
               {"type": "video_url", "video_url": {"url": video}}]
    text, secs, cost = call(content, model, {"max_tokens": 8000,
                                             "response_format": {"type": "json_object"}},
                            log={"judge": os.path.basename(path)}, timeout=600)
    start, end = text.find("{"), text.rfind("}")
    result = json.loads(text[start:end + 1])
    result.update({"model": model, "cost_usd": cost, "seconds": round(secs, 1)})
    out = path[:-4] + ".judge.json"
    with open(out, "w") as f:
        json.dump(result, f, indent=1)
    return result


if __name__ == "__main__":
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--"))
    model = opts.get("model", "google/gemini-4-argon")
    for path in [a for a in sys.argv[1:] if not a.startswith("--")]:
        r = judge(path, model)
        verdicts = {}
        for p in r.get("phrases", []):
            verdicts[p["verdict"]] = verdicts.get(p["verdict"], 0) + 1
        print(f"{os.path.basename(path)}: usefulness {r.get('usefulness')}/10, verbosity "
              f"{r.get('verbosity')}/5, timeliness {r.get('timeliness')}/5, {verdicts}, "
              f"missed {len(r.get('missed', []))}, ${r['cost_usd']:.3f}, {r['seconds']}s")
        print(f"  {r.get('summary')}")
