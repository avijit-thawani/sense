"""Hill-climb the device's no-question prompt against human-checked moment gold.

  run NAME PROMPT_FILE SPLIT [--hist=0] [--px=640] [--model=M] [--reasoning=none|low] [--judge=J] [--final]
  report [SPLIT]                 one line per run: score, coverage, critical errors, words, by category
  sheet NAME[,NAME...] SPLIT     side-by-side image / gold / replies / judge, worst first

The device gets no category: it must notice a note, a sign, an object or a street by itself.
Only moments whose gold you approved or edited count. Test runs need --final (do it once).
"""
import base64
import io
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

from enhance import enhance
from moments import OUT, load
from orclient import MODEL, call, describe

RUNS = os.path.join(OUT, "runs")
JUDGE = "anthropic/claude-haiku-5.5"
HERE = os.path.dirname(os.path.abspath(__file__))

RUBRIC = """You are judging an assistive camera for a blind person in India. The user asked nothing;
the device spoke on its own. Below is the human-checked GOLD line (what it should say) and what
the DEVICE said. Use the image to settle disagreements.
GOLD: {gold}
DEVICE: {pred}

Return JSON only:
{{"facts": [{{"fact": "<a key fact in the gold, <=8 words>", "conveyed": "yes" | "partly" | "no"}}, ...  (at most 5, most important first)],
 "wrong": ["<each device claim that is false for this image, <=10 words>", ...],
 "critical": true/false  (any wrong claim that could cost money or safety: wrong note value, traffic
             or path direction wrong, moving vs stopped wrong, misread key number or name, saying a
             path is clear when it isn't),
 "extra": "<needless words or trivia, or ''>",
 "score": 0-10  (10 = conveys the gold's essentials, nothing wrong, about as brief; SILENT gold:
                 10 only if the device was silent or said something harmless and brief),
 "note": "<= 15 words"}}"""


def human_gold(split):
    return [m for m in load() if m["gold_status"] in ("approved", "edited") and m["split"] == split]


def frames_for(m, hist, px):
    out = []
    for rel in m["frames"][-(hist + 1):]:
        img = Image.open(os.path.join(OUT, rel)).convert("RGB")
        img.thumbnail((px, px))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        out.append(enhance(buf.getvalue(), "auto"))
    return out


def judge(m, pred, model):
    img = open(os.path.join(OUT, m["frames"][-1]), "rb").read()
    content = [{"type": "text", "text": RUBRIC.format(gold=m["gold"], pred=pred)},
               {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(img).decode()}}]
    text, _, cost = call(content, model, {"max_tokens": 1500, "reasoning": {"effort": "low"}},
                         log={"climb": "judge", "id": m["id"]})
    s, e = text.find("{"), text.rfind("}")
    try:
        return {**json.loads(text[s:e + 1]), "judge_cost": cost}
    except ValueError:
        return {"error": text[:200], "judge_cost": cost}


def run(name, prompt_file, split, hist=0, px=640, model=MODEL, judge_model=JUDGE, final=False, reasoning="none"):
    if split == "test" and not final:
        sys.exit("test is held out: pass --final for the one last check")
    prompt = open(os.path.join(HERE, prompt_file) if not os.path.isabs(prompt_file) else prompt_file).read().strip()
    os.makedirs(RUNS, exist_ok=True)
    path = os.path.join(RUNS, f"{name}__{split}.jsonl")
    done = {json.loads(l)["id"] for l in open(path)} if os.path.exists(path) else set()
    todo = [m for m in human_gold(split) if m["id"] not in done]
    print(f"{name} on {split}: {len(todo)} moments ({len(done)} done), model {model}, hist {hist}, {px}px")

    def one(m):
        for attempt in range(3):
            try:
                jpegs = frames_for(m, hist, px)
                p = prompt + (f"\nThe first {len(jpegs) - 1} images are the previous seconds, oldest first; "
                              "the last image is now." if len(jpegs) > 1 else "")
                text, secs, cost = describe(jpegs, p, model=model,
                                            # thinking models return nothing if thinking fills max_tokens
                                            extra={"max_tokens": 120 if reasoning == "none" else 2500,
                                                   "reasoning": {"effort": reasoning}},
                                            log={"climb": name, "id": m["id"], "n_images": len(jpegs)})
                spoken = "" if text.strip(" .\"'").upper() == "SILENT" else text
                v = judge(m, spoken or "(silent)", judge_model)
                return {"id": m["id"], "category": m["category"], "gold": m["gold"], "said": spoken,
                        "words": len(spoken.split()), "model_s": round(secs, 2), "cost_usd": cost,
                        "prompt_file": prompt_file, "hist": hist, "px": px, "model": model, "reasoning": reasoning, **v}
            except Exception as e:
                if "spend cap" in str(e):
                    raise
                print(f"  {m['id']}: {type(e).__name__} {str(e)[:80]} (attempt {attempt + 1})")
        return None

    with ThreadPoolExecutor(6) as ex, open(path, "a") as f:
        for row in ex.map(one, todo):
            if row:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
                f.flush()
    report(split, only=name)


def summarize(rows):
    def s(rs):
        n = len(rs)
        if not n:
            return ""
        score = sum(r.get("score", 0) for r in rs) / n
        facts = [f for r in rs for f in r.get("facts", [])]
        cov = sum({"yes": 1, "partly": .5}.get(f.get("conveyed"), 0) for f in facts) / max(len(facts), 1)
        crit = sum(1 for r in rs if r.get("critical")) / n
        words = sum(r["words"] for r in rs) / n
        return f"score {score:4.1f}  facts {100 * cov:3.0f}%  critical {100 * crit:3.0f}%  words {words:4.1f}  (n={n})"
    out = [f"ALL      {s(rows)}"]
    for c in ("currency", "text", "items", "outdoor", "home"):
        rs = [r for r in rows if r["category"] == c]
        if rs:
            out.append(f"{c:<8} {s(rs)}")
    return out


def report(split=None, only=None):
    if not os.path.isdir(RUNS):
        return
    for fn in sorted(os.listdir(RUNS)):
        name, sp = fn[:-6].rsplit("__", 1)
        if (split and sp != split) or (only and name != only):
            continue
        rows = [json.loads(l) for l in open(os.path.join(RUNS, fn))]
        print(f"== {name} [{sp}]")
        for line in summarize(rows):
            print("   " + line)


def sheet(names, split, n=12):
    from PIL import ImageDraw, ImageFont
    import textwrap
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 15)
    bold = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 15)
    runs = {nm: {r["id"]: r for r in map(json.loads, open(os.path.join(RUNS, f"{nm}__{split}.jsonl")))}
            for nm in names}
    ids = [i for i in runs[names[-1]] if all(i in r for r in runs.values())]
    ids.sort(key=lambda i: runs[names[-1]][i].get("score", 0))       # worst first for the newest run
    ids = ids[:n]
    moments = {m["id"]: m for m in load()}
    W, ROW = 1300, 300
    img = Image.new("RGB", (W, ROW * len(ids)), "white")
    d = ImageDraw.Draw(img)
    for k, i in enumerate(ids):
        y = k * ROW
        im = Image.open(os.path.join(OUT, moments[i]["frames"][-1]))
        im.thumbnail((380, ROW - 20))
        img.paste(im, (10, y + 10))
        x, yy = 410, y + 10
        def line(label, text, color, f=font, width=105, maxl=2):
            nonlocal yy
            d.text((x, yy), label, font=bold, fill=color)
            for t in textwrap.wrap(text.replace("₹", "Rs "), width)[:maxl]:
                d.text((x + 120, yy), t, font=f, fill="black")
                yy += 19
            yy += 3
        line(moments[i]["category"].upper(), "", (90, 90, 90), maxl=0)
        yy += 19
        line("GOLD", moments[i]["gold"], (40, 40, 160), maxl=3)
        for nm in names:
            r = runs[nm][i]
            color = (200, 30, 30) if r.get("critical") else (20, 130, 50) if r.get("score", 0) >= 7 else (190, 120, 0)
            line(f"{nm[:12]} {r.get('score', '?')}", r["said"] or "(silent)", color)
            if r.get("wrong"):
                line("", "wrong: " + "; ".join(r["wrong"])[:200], (200, 30, 30), maxl=1)
        d.line((0, y + ROW - 1, W, y + ROW - 1), fill=(210, 210, 210), width=2)
    path = os.path.join(OUT, f"climb_{'+'.join(names)}_{split}.jpg")
    img.save(path, quality=85)
    print(path)


def rejudge(name, split, judge_model, skip=("currency",)):
    """Re-score an existing run with another judge (no new device calls); prints agreement."""
    rows = [json.loads(l) for l in open(os.path.join(RUNS, f"{name}__{split}.jsonl"))]
    rows = [r for r in rows if r["category"] not in skip]
    moments = {m["id"]: m for m in load()}
    with ThreadPoolExecutor(6) as ex:
        new = list(ex.map(lambda r: judge(moments[r["id"]], r["said"] or "(silent)", judge_model), rows))
    out = os.path.join(RUNS, f"{name}__{split}.judge-{judge_model.split('/')[-1]}.json")
    json.dump([{**r, "rejudge": n} for r, n in zip(rows, new)], open(out, "w"), ensure_ascii=False, indent=1)
    diffs = [abs(r.get("score", 0) - n.get("score", 0)) for r, n in zip(rows, new)]
    crit = sum(bool(r.get("critical")) == bool(n.get("critical")) for r, n in zip(rows, new))
    print(f"{len(rows)} rows: mean |score diff| {sum(diffs) / len(diffs):.1f}, critical agrees {crit}/{len(rows)}")
    for r, n in zip(rows, new):
        if abs(r.get("score", 0) - n.get("score", 0)) >= 3 or bool(r.get("critical")) != bool(n.get("critical")):
            print(f"  {r['category']:<8} {JUDGE.split('/')[-1]} {r.get('score')}{'!' if r.get('critical') else ''}"
                  f" vs {n.get('score')}{'!' if n.get('critical') else ''} | {r['said'][:70]} | {n.get('note', '')}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict((a[2:].split("=", 1) + [True])[:2] for a in sys.argv[1:] if a.startswith("--"))
    if args[0] == "run":
        run(args[1], args[2], args[3], int(opts.get("hist", 0)), int(opts.get("px", 640)),
            opts.get("model", MODEL), opts.get("judge", JUDGE), "final" in opts, opts.get("reasoning", "none"))
    elif args[0] == "report":
        report(args[1] if len(args) > 1 else None)
    elif args[0] == "rejudge":
        rejudge(args[1], args[2], opts.get("judge", "anthropic/claude-sonnet-5.5"))
    elif args[0] == "sheet":
        sheet(args[1].split(","), args[2], int(opts.get("n", 12)))
