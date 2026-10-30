"""Side-by-side sheets of eval runs: image, question, gold, each system's reply and verdict.

Usage: eval_sheets.py DATASET SPLIT [--n=8] [--systems=items,ask,ask-v3]
Writes data/eval/sheets/DATASET_SPLIT_<k>.jpg (a few examples per sheet).
Examples are picked where the systems disagree most, spread across question types.
"""
import json
import os
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

from eval_sets import EVAL, RUNS, load_items, short_gold

FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"
BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
F, FB, FS = ImageFont.truetype(FONT, 17), ImageFont.truetype(BOLD, 17), ImageFont.truetype(FONT, 14)
COLORS = {"correct": (20, 130, 50), "abstain_ok": (20, 130, 50), "good": (20, 130, 50),
          "partial": (190, 120, 0), "ok": (190, 120, 0),
          "wrong": (200, 30, 30), "abstain_bad": (200, 30, 30), "poor": (200, 30, 30)}
SCORE = {"correct": 1, "abstain_ok": 1, "partial": .5}
DEFAULT = {"egoblind": "items,items+N,ask,ask-v3,ask+N-v3", "vizwiz": "items,ask,ask-v3",
           "guidedog": "items"}
IMG_W, ROW_H, TEXT_W = 430, 470, 900


def load_run(system, dataset, split):
    path = os.path.join(RUNS, f"{system}__{dataset}__{split}.jsonl")
    return {r["id"]: r for r in map(json.loads, open(path))} if os.path.exists(path) else {}


def verdict(r):
    if "covered" in r:
        return "correct" if r["covered"] else "wrong", "hazard named" if r["covered"] else "top hazard missed"
    return r.get("verdict", "?"), r["verdict"].replace("_", " ") if r.get("verdict") else "?"


def pick(items, runs, n):
    """Most disagreement first, one per question type in turn, so the sheet isn't all one kind."""
    def spread_score(i):
        s = [SCORE.get(verdict(r[i["id"]])[0], 0) for r in runs.values() if i["id"] in r]
        return (max(s) - min(s)) if len(s) > 1 else 0
    pool = [i for i in items if all(i["id"] in r for r in runs.values())]
    if len(runs) == 1:            # nothing to disagree with: alternate hits and misses instead
        only = next(iter(runs.values()))
        hits = [i for i in pool if SCORE.get(verdict(only[i["id"]])[0], 0) >= 1]
        misses = [i for i in pool if i not in hits]
        pool = [x for pair in zip(misses, hits) for x in pair] + misses[len(hits):] + hits[len(misses):]
    else:
        pool.sort(key=spread_score, reverse=True)
    by_type, out, videos = {}, [], set()
    for i in pool:
        video = i["id"].rsplit("_", 1)[0]          # egoblind ids are v_<video>_<n>
        if i["dataset"] == "egoblind" and video in videos:
            continue
        videos.add(video)
        by_type.setdefault(i["type"], []).append(i)
    while len(out) < n and any(by_type.values()):
        for t in list(by_type):
            if by_type[t] and len(out) < n:
                out.append(by_type[t].pop(0))
    return out


def draw_wrapped(d, xy, text, font, fill, width, max_lines=4):
    x, y = xy
    for line in textwrap.wrap(text, width)[:max_lines]:
        d.text((x, y), line, font=font, fill=fill)
        y += font.size + 4
    return y


def row(item, runs, gold):
    im = Image.new("RGB", (IMG_W + TEXT_W, ROW_H), "white")
    d = ImageDraw.Draw(im)
    main = Image.open(os.path.join(EVAL, item["frames"][-1])).convert("RGB")
    main.thumbnail((IMG_W - 20, 330))
    im.paste(main, (10, 10))
    if len(item["frames"]) > 1:              # the earlier frames the +N systems also saw
        d.text((10, 350), "earlier frames (1 s apart), seen by +N:", font=FS, fill=(90, 90, 90))
        for k, f in enumerate(item["frames"][:-1]):
            t = Image.open(os.path.join(EVAL, f)).convert("RGB")
            t.thumbnail((130, 90))
            im.paste(t, (10 + k * 138, 372))
    x, y = IMG_W + 10, 10
    head = item["question"] and f"Q ({item['type']}): {item['question']}" or f"Walking frame, {item['type']}"
    y = draw_wrapped(d, (x, y), head, FB, "black", 80, 2) + 4
    y = draw_wrapped(d, (x, y), "GOLD: " + gold, F, (60, 60, 160), 88, 3) + 10
    for name, r in runs.items():
        v, label = verdict(r[item["id"]])
        said = r[item["id"]]["spoken"] or "(silent)"
        d.text((x, y), f"{name}", font=FB, fill="black")
        d.text((x + 140, y), label.upper(), font=FB, fill=COLORS.get(v, "black"))
        y = draw_wrapped(d, (x + 340, y), f'"{said}"', F, "black", 54, 2)
        note = r[item["id"]].get("note") or ""
        y = draw_wrapped(d, (x + 340, y), "judge: " + note, FS, (110, 110, 110), 75, 1) + 8
    d.line((0, ROW_H - 1, IMG_W + TEXT_W, ROW_H - 1), fill=(200, 200, 200), width=2)
    return im


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--"))
    dataset, split = args
    systems = opts.get("systems", DEFAULT[dataset]).split(",")
    runs = {s: load_run(s, dataset, split) for s in systems}
    runs = {s: r for s, r in runs.items() if r}
    items = pick(load_items(dataset, split), runs, int(opts.get("n", 8)))
    sg = short_gold()
    out_dir = os.path.join(EVAL, "sheets")
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for i in items:
        gold = "; ".join(sg.get(i["id"]) or []) if dataset == "guidedog" else " | ".join(i["refs"][:3])
        rows.append(row(i, runs, gold))
    per = 3
    for k in range(0, len(rows), per):
        chunk = rows[k:k + per]
        sheet = Image.new("RGB", (chunk[0].width, sum(r.height for r in chunk)), "white")
        for j, r in enumerate(chunk):
            sheet.paste(r, (0, j * ROW_H))
        path = os.path.join(out_dir, f"{dataset}_{split}_{k // per + 1}.jpg")
        sheet.save(path, quality=85)
        print(path)


if __name__ == "__main__":
    main()
