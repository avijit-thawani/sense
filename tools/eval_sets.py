"""Offline evaluation of sense prompts on public blind-assistance datasets.

  prep                       build data/eval/items.jsonl + frames (free, local)
  shorten [--split S]        GuideDog: boil each long label down to a short spoken gold (paid, cached)
  run SYSTEM DATASET SPLIT [--limit N] [--tag T] [--prompt-file F]
  report [DATASET] [SPLIT]   summary table of every run

Splits are fixed by hashing the video/image id (no leakage between them):
  egoblind  train/dev/test by video        vizwiz  train/dev/test by image
  guidedog  train/dev = AI-labelled India frames split by city; test = the 18 hand-checked frames
`run ... test` refuses unless --final is given: tune on train, compare on dev, test once at the end.

Systems:
  items      live default (describe.ITEMS_PROMPT), current frame only: a button press, no question
  items+N    same, plus the previous N frames at 1 s spacing: "always clicking, describe on trigger"
  ask        push-to-talk: the user's question + current frame
  ask+N      question + previous N frames
Scoring does not compare wording to the references (they are long, written for reading). A judge
model, shown the newest frame, decides whether the reply carries the essential fact; length is
reported separately as words and seconds of speech.
"""
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

import describe as d
from enhance import enhance
from fetch_zip import spread
from orclient import call, describe

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DATA = os.path.join(ROOT, "data")
EVAL = os.path.join(DATA, "eval")
ITEMS = os.path.join(EVAL, "items.jsonl")
SHORT = os.path.join(EVAL, "guidedog_short.jsonl")
RUNS = os.path.join(EVAL, "runs")
N_PREV = 3                   # earlier frames for the +N systems, 1 s apart
SUBTITLE_CROP = 0.15         # EgoBlind has burned-in captions near the bottom
GUIDEDOG_DEV_CITIES = {"Mysore", "Ooty"}   # frames from one city share source videos
JUDGE = "anthropic/claude-haiku-5.5"
SHORTENER = JUDGE
SAY_WPM = d.SAY_WPM

ASK_PROMPT = ("You are the eyes of a blind person, who just asked: \"{q}\" "
              "Answer in at most 10 words, the useful part first. Use left/right/ahead and "
              "distance in steps when it helps. Quote text only if clearly legible. "
              "If the image can't answer it, reply 'Can't tell' plus, if useful, how to aim "
              "the camera, e.g. 'Can't tell, turn left'.")
PREV_PROMPT = (" The first {n} images are from the previous seconds, oldest first; the last image "
               "is now. Answer about now; use earlier images only if they help.")


def split_of(key, test_share=25, dev_share=25):
    h = int(hashlib.md5(str(key).encode()).hexdigest(), 16) % 100
    return "test" if h < test_share else "dev" if h < test_share + dev_share else "train"


def small_jpeg(img, crop_bottom=0.0):
    if crop_bottom:
        img = img.crop((0, 0, img.width, int(img.height * (1 - crop_bottom))))
    img.thumbnail((640, 640))
    out = io.BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=80)
    return out.getvalue()


def frame_at(video, t):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{max(t, 0):.2f}", "-i", video,
                          "-frames:v", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "-"],
                         capture_output=True).stdout
    return Image.open(io.BytesIO(raw)) if raw else None


def prep():
    os.makedirs(os.path.join(EVAL, "frames"), exist_ok=True)
    items = []

    vids = os.path.join(DATA, "egoblind", "videos")
    have = {int(f[:-4]) for f in os.listdir(vids) if f.endswith(".mp4")}
    for r in csv.DictReader(open(os.path.join(DATA, "egoblind", "train.csv"))):
        v, t = int(r["video_name"]), float(r["start-time/s"])
        if v not in have:
            continue
        frames = []
        for k in range(N_PREV, -1, -1):          # oldest first, newest (= question time) last
            if t - k < 0:
                continue
            path = os.path.join(EVAL, "frames", f"eb_{v}_{t - k:.2f}.jpg")
            if not os.path.exists(path):
                img = frame_at(os.path.join(vids, f"{v:05d}.mp4"), t - k)
                if img is None:
                    continue
                open(path, "wb").write(small_jpeg(img, SUBTITLE_CROP))
            frames.append(os.path.relpath(path, EVAL))
        if not frames:
            continue
        refs = [r[f"answer{i}"] for i in range(4) if r[f"answer{i}"].strip()]
        items.append({"id": r["question_id"], "dataset": "egoblind", "split": split_of(f"eb{v}"),
                      "type": r["type"].lower(), "question": r["question"], "refs": refs, "frames": frames})

    vz = os.path.join(DATA, "vizwiz", "val")
    have = set(os.listdir(vz))
    for x in json.load(open(os.path.join(DATA, "vizwiz", "val.json"))):
        if x["image"] not in have:
            continue
        path = os.path.join(EVAL, "frames", "vz_" + x["image"])
        if not os.path.exists(path):
            open(path, "wb").write(small_jpeg(Image.open(os.path.join(vz, x["image"]))))
        answers = [a["answer"] for a in x["answers"]]
        refs = sorted(set(answers), key=answers.count, reverse=True)[:4]
        items.append({"id": x["image"][:-4], "dataset": "vizwiz", "split": split_of(x["image"]),
                      "type": x["answer_type"], "question": x["question"], "refs": refs,
                      "answerable": x["answerable"], "frames": [os.path.relpath(path, EVAL)]})

    for sub, label_key in (("india", "gold_label"), ("india_silver", "silver_label")):
        folder = os.path.join(DATA, "guidedog", sub)
        for x in json.load(open(os.path.join(folder, "labels.json"))):
            path = os.path.join(EVAL, "frames", f"gd_{sub}_{x['file']}")
            if not os.path.exists(path):
                open(path, "wb").write(small_jpeg(Image.open(os.path.join(folder, x["file"]))))
            if sub == "india":
                split = "test"
            else:
                split = "dev" if x["video_city"] in GUIDEDOG_DEV_CITIES else "train"
            items.append({"id": f"{sub}/{x['file']}", "dataset": "guidedog", "split": split,
                          "type": x["video_city"], "question": None, "refs": [x[label_key]],
                          "frames": [os.path.relpath(path, EVAL)]})

    with open(ITEMS, "w") as f:
        f.writelines(json.dumps(i, ensure_ascii=False) + "\n" for i in items)
    summary = {}
    for i in items:
        key = (i["dataset"], i["split"])
        summary[key] = summary.get(key, 0) + 1
    for k in sorted(summary):
        print(*k, summary[k])
    cities = {}
    for i in items:
        if i["dataset"] == "guidedog":
            cities.setdefault(i["split"], set()).add(i["type"])
    print({k: sorted(v) for k, v in cities.items()})


def load_items(dataset=None, split=None):
    out = [json.loads(l) for l in open(ITEMS)]
    return [i for i in out if (not dataset or i["dataset"] == dataset) and (not split or i["split"] == split)]


def img_part(rel):
    import base64
    return {"type": "image_url", "image_url": {
        "url": "data:image/jpeg;base64," + base64.b64encode(open(os.path.join(EVAL, rel), "rb").read()).decode()}}


def parse_json(text):
    m = re.search(r"\{.*\}", text, re.S)
    return json.loads(m.group(0)) if m else {"error": text[:200]}


SHORTEN_RUBRIC = """This is a street photo from a blind pedestrian's point of view, with a long hazard
description written by {who}. Write what a guide should SAY in a few seconds: the 1-2 things that
matter most for walking safely right now, each as 2-5 words with clock direction or left/right/ahead
and distance in steps if known. Only include things actually visible in the photo; fix the
description if it is wrong. Return JSON only: {{"say": ["item", "item"], "note": "what you fixed, if anything"}}
Long description: {label}"""


def shorten(split=None, limit=None):
    done = {json.loads(l)["id"] for l in open(SHORT)} if os.path.exists(SHORT) else set()
    items = load_items("guidedog", split)
    items = spread(items, limit) if limit else items
    todo = [i for i in items if i["id"] not in done]
    print(f"shortening {len(todo)} GuideDog labels with {SHORTENER}")

    def one(i):
        who = "a person (checked)" if i["split"] == "test" else "an AI model (unchecked, may be wrong)"
        content = [{"type": "text", "text": SHORTEN_RUBRIC.format(who=who, label=i["refs"][0])},
                   img_part(i["frames"][-1])]
        text, _, cost = call(content, SHORTENER, {"max_tokens": 1500, "reasoning": {"effort": "low"}},
                             log={"eval": "shorten", "id": i["id"]})
        return {"id": i["id"], **parse_json(text), "cost_usd": cost}

    with ThreadPoolExecutor(6) as ex, open(SHORT, "a") as f:
        for row in ex.map(one, todo):
            if not row.get("say"):
                print(row["id"], "no answer, will retry next time:", row.get("error", "")[:80])
                continue
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            f.flush()
            print(row["id"], row.get("say"))


def short_gold():
    return {r["id"]: r.get("say") for r in map(json.loads, open(SHORT))} if os.path.exists(SHORT) else {}


QA_RUBRIC = """A blind person's camera saw the attached image (the newest frame). {situation}
Sighted annotators' reference answers (may be wordy or disagree; some say they don't know): {refs}
The assistant said: "{pred}"

Judge only whether the reply gives the user the ESSENTIAL fact they need, correctly. Do not reward
length or matching the references' wording; a 3-word correct reply is perfect. Return JSON only:
{{"verdict": "correct" | "partial" | "wrong" | "abstain_ok" | "abstain_bad",
  "hallucination": true/false (states something not supported by the image),
  "note": "<= 15 words"}}
correct = essential fact right. partial = useful but vague/incomplete. wrong = incorrect, or doesn't
address the need. abstain_ok = says it can't tell / stays silent, and the image really doesn't allow
an answer. abstain_bad = abstains but the answer was visible."""

HAZARD_RUBRIC = """A blind pedestrian's camera saw the attached image. What a good guide would say
(short gold, checked against the photo): {gold}
The assistant said: "{pred}"

Return JSON only:
{{"covered": true/false (did it mention the most important item in the gold, direction roughly right),
  "wrong_items": number of things it said that are false or badly misplaced in the image,
  "verdict": "good" | "ok" | "poor",
  "note": "<= 15 words"}}"""


def build(system, item, prompt_override=None):
    frames = item["frames"] if system.endswith("+N") else item["frames"][-1:]
    jpegs = [enhance(open(os.path.join(EVAL, f), "rb").read(), "auto") for f in frames]
    if system.startswith("ask"):
        prompt = (prompt_override or ASK_PROMPT).format(q=item["question"])
    else:
        prompt = prompt_override or d.ITEMS_PROMPT
    if len(jpegs) > 1:
        prompt += PREV_PROMPT.format(n=len(jpegs) - 1)
    return prompt, jpegs


def judge(item, pred, system):
    if item["dataset"] == "guidedog":
        gold = short_gold().get(item["id"])
        if not gold:
            raise RuntimeError("no short gold yet: run `eval_sets.py shorten` first")
        text = HAZARD_RUBRIC.format(gold="; ".join(gold), pred=pred)
    else:
        if system.startswith("ask"):
            situation = f'They asked: "{item["question"]}"'
        else:
            situation = (f'They pressed "describe" without asking anything; what they wanted to know was: '
                         f'"{item["question"]}". Judge whether the description would answer that.')
        text = QA_RUBRIC.format(situation=situation, refs=" | ".join(item["refs"]), pred=pred)
    out, _, cost = call([{"type": "text", "text": text}, img_part(item["frames"][-1])], JUDGE,
                        {"max_tokens": 1000, "reasoning": {"effort": "low"}},
                        log={"eval": "judge", "id": item["id"]})
    return {**parse_json(out), "judge_cost": cost}


def run(system, dataset, split, limit=None, tag=None, prompt_file=None, final=False):
    if split == "test" and not final:
        sys.exit("test split is held out: pass --final only for the last, one-time check")
    prompt = open(prompt_file).read().strip() if prompt_file else None
    items = load_items(dataset, split)
    items = spread(items, limit) if limit else items
    name = f"{system}{'-' + tag if tag else ''}__{dataset}__{split}"
    os.makedirs(RUNS, exist_ok=True)
    path = os.path.join(RUNS, name + ".jsonl")
    done = {json.loads(l)["id"] for l in open(path)} if os.path.exists(path) else set()
    todo = [i for i in items if i["id"] not in done]
    print(f"{name}: {len(todo)} to run ({len(done)} done)")

    def one(item):
        p, jpegs = build(system, item, prompt)
        text, secs, cost = describe(jpegs, p, log={"eval": name, "id": item["id"], "n_images": len(jpegs)})
        spoken = "" if text.strip(" .\"'").upper() == "SILENT" else text
        if system.startswith("items") and spoken:  # live speaks at most 2 items per photo
            spoken = ". ".join(s.strip(" .") for s in spoken.split(";")[:d.MAX_ITEMS_SPOKEN]) + "."
        verdict = judge(item, spoken or "(silent)", system)
        n_words = len(spoken.split())
        return {"id": item["id"], "type": item["type"], "question": item["question"], "raw": text,
                "spoken": spoken, "words": n_words, "speech_s": round(0.1 + n_words * 60 / SAY_WPM, 2),
                "model_s": round(secs, 2), "cost_usd": cost, "n_images": len(jpegs), **verdict}

    def retried(item):
        for attempt in range(3):
            try:
                return one(item)
            except Exception as e:
                if "spend cap" in str(e):
                    raise
                print(f"  {item['id']}: {type(e).__name__} (attempt {attempt + 1})")
        return None              # skipped; a rerun picks it up

    with ThreadPoolExecutor(6) as ex, open(path, "a") as f:
        for row in ex.map(retried, todo):
            if row is None:
                continue
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            f.flush()
            mark = row.get("verdict") or ("covered" if row.get("covered") else "missed")
            print(f"  {row['id']:<28} {mark:<11} {row['spoken'][:70]}")
    report(dataset, split, only=name)


def summarize(rows):
    n = len(rows)
    if not n:
        return {}
    s = {"n": n, "words": round(sum(r["words"] for r in rows) / n, 1),
         "speech_s": round(sum(r["speech_s"] for r in rows) / n, 2),
         "model_s": round(sorted(r["model_s"] for r in rows)[n // 2], 2),
         "usd_per": round(sum((r["cost_usd"] or 0) for r in rows) / n, 5)}
    if "verdict" in rows[0] and "covered" not in rows[0]:
        c = {}
        for r in rows:
            c[r.get("verdict", "error")] = c.get(r.get("verdict", "error"), 0) + 1
        s["useful%"] = round(100 * (c.get("correct", 0) + c.get("abstain_ok", 0) + 0.5 * c.get("partial", 0)) / n)
        s["wrong%"] = round(100 * c.get("wrong", 0) / n)
        s["halluc%"] = round(100 * sum(1 for r in rows if r.get("hallucination")) / n)
        s["verdicts"] = c
    else:
        s["covered%"] = round(100 * sum(1 for r in rows if r.get("covered")) / n)
        s["wrong_items"] = round(sum(r.get("wrong_items") or 0 for r in rows) / n, 2)
    return s


def report(dataset=None, split=None, only=None):
    if not os.path.isdir(RUNS):
        return print("no runs yet")
    for fn in sorted(os.listdir(RUNS)):
        name = fn[:-6]
        sysname, ds, sp = name.split("__")
        if (dataset and ds != dataset) or (split and sp != split) or (only and name != only):
            continue
        rows = [json.loads(l) for l in open(os.path.join(RUNS, fn))]
        print(f"{name:<45} {summarize(rows)}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict((a[2:].split("=", 1) + [True])[:2] for a in sys.argv[1:] if a.startswith("--"))
    cmd = args[0]
    if cmd == "prep":
        prep()
    elif cmd == "shorten":
        shorten(opts.get("split"), int(opts["limit"]) if "limit" in opts else None)
    elif cmd == "run":
        run(args[1], args[2], args[3], int(opts["limit"]) if "limit" in opts else None,
            opts.get("tag"), opts.get("prompt-file"), "final" in opts)
    elif cmd == "report":
        report(*args[1:3])
