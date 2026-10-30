"""Find frames from our own recorded sessions where a prompt fails, and add them to the moments set.

  run PROMPT_FILE [--limit N]   run the prompt on every unique session frame (auto-brightened, like
                                the live loop), then a judge lists what it missed or got wrong
  add [--min=2]                 add frames with severity >= min to moments.jsonl as category "home"
  page                          data/moments/mined.html: worst first, raw + brightened, all lines

Output: data/moments/mined_sessions.jsonl. No gold needed: the judge looks at the frame itself.
Split is by session, so test frames come from sessions that tuning never sees.
"""
import base64
import glob
import hashlib
import html
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor

from enhance import enhance
from moments import OUT, load, write, split_of, save_frame
from orclient import call, describe
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SESSIONS = os.path.join(HERE, "..", "sessions")
MINED = os.path.join(OUT, "mined_sessions.jsonl")
JUDGE = "anthropic/claude-haiku-5.5"

RUBRIC = """This frame is from a camera worn by a blind person, mostly at home (it was brightened
before the device saw it). The user asked nothing; the device chose to say: "{pred}"
(An older version of the device said, possibly about a slightly different frame: "{live}".)

What should a blind person here be told? Think of: a person close or facing them, doors (open or
closed), obstacles at head, chest or hip height, steps, things in the walking path, readable text
or labels that matter, hazards (knife, hot, glass, cables to trip on). Ignore trivia.
Return JSON only:
{{"important": ["<what matters here, <=8 words>", ...]  (0-3 items; [] if nothing),
 "missed": ["<important thing the device didn't say>", ...],
 "wrong": ["<false thing the device said>", ...],
 "severity": 0-3  (0 fine; 1 minor miss; 2 missed or wrong something they'd want to know;
                   3 safety: missed a person/obstacle/hazard close by, or said something false and risky),
 "suggested": "<what the device should have said, short, plain, like a sighted friend; SILENT if nothing>"}}"""


def session_frames():
    seen, out = set(), []
    for d in sorted(glob.glob(os.path.join(SESSIONS, "2026*"))):
        if "replay" in d:
            continue
        for p in sorted(glob.glob(os.path.join(d, "*.jpg"))):
            h = hashlib.md5(open(p, "rb").read()).hexdigest()
            if h not in seen:
                seen.add(h)
                out.append(os.path.relpath(p, SESSIONS))
    return out


def live_text(rel):
    folder, photo = os.path.split(rel)
    path = os.path.join(SESSIONS, folder, "session.jsonl")
    if os.path.exists(path):
        for line in open(path):
            r = json.loads(line)
            if r.get("photo") == photo and not r.get("event"):
                return r.get("text") or ""
    return ""


def run(prompt_file, limit=None, out=MINED, only_from=None):
    """only_from: a previous mined file; re-run just the frames it rated severity >= 2."""
    prompt = open(os.path.join(HERE, prompt_file)).read().strip()
    done = {json.loads(l)["frame"] for l in open(out)} if os.path.exists(out) else set()
    frames = session_frames()
    if only_from:
        bad = {r["frame"] for r in map(json.loads, open(only_from)) if r.get("severity", 0) >= 2}
        frames = [f for f in frames if f in bad]
    frames = [f for f in frames if f not in done]
    frames = frames[:limit] if limit else frames
    print(f"{len(frames)} frames to run with {prompt_file}")

    def one(rel):
        for attempt in range(3):
            try:
                jpeg = enhance(open(os.path.join(SESSIONS, rel), "rb").read(), "auto")
                text, _, _ = describe(jpeg, prompt, extra={"max_tokens": 120}, log={"mine": prompt_file, "photo": rel})
                pred = "SILENT" if text.strip(" .\"'").upper() == "SILENT" else text
                live = live_text(rel)
                content = [{"type": "text", "text": RUBRIC.format(pred=pred, live=live or "(none)")},
                           {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}}]
                out, _, _ = call(content, JUDGE, {"max_tokens": 1500, "reasoning": {"effort": "low"}},
                                 log={"mine": "judge", "photo": rel})
                s, e = out.find("{"), out.rfind("}")
                return {"frame": rel, "prompt_file": prompt_file, "said": pred, "live": live, **json.loads(out[s:e + 1])}
            except Exception as e:
                if "spend cap" in str(e):
                    raise
                print(f"  {rel}: {type(e).__name__} (attempt {attempt + 1})")
        return None

    with ThreadPoolExecutor(6) as ex, open(out, "a") as f:
        for row in ex.map(one, frames):
            if row:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
                f.flush()
                if row.get("severity", 0) >= 2:
                    print(f"  sev {row['severity']} {row['frame']:<34} said: {row['said'][:50]} | missed: {row.get('missed')}")
    rows = [json.loads(l) for l in open(out)]
    print({s: sum(1 for r in rows if r.get("severity") == s) for s in range(4)})


def add(min_sev=2):
    rows = [json.loads(l) for l in open(MINED)]
    moments = load()
    have = {m["id"] for m in moments}
    added = 0
    for r in rows:
        if r.get("severity", 0) < min_sev:
            continue
        session, photo = os.path.split(r["frame"])
        mid = f"home_{session}_{photo[:-4]}"
        if mid in have:
            continue
        frames = [save_frame(Image.open(os.path.join(SESSIONS, r["frame"])), mid + ".jpg")]
        hints = (f"judge: important={r.get('important')}; missed={r.get('missed')}; wrong={r.get('wrong')}"
                 f" | old live line (may be for a nearby frame): {r.get('live')}")
        moments.append({"id": mid, "category": "home", "source": "own-sessions", "split": split_of(session),
                        "frames": frames, "hints": hints, "gold": r.get("suggested") or None,
                        "gold_status": "draft" if r.get("suggested") else "none",
                        "why": f"mined: {r['prompt_file']} said '{r['said']}' (severity {r['severity']})"})
        added += 1
    write(moments)
    print(f"added {added} home moments (severity >= {min_sev})")


def page():
    rows = sorted((json.loads(l) for l in open(MINED)), key=lambda r: -r.get("severity", 0))
    img_dir = os.path.join(OUT, "mined_img")
    os.makedirs(img_dir, exist_ok=True)
    out = []
    for r in rows:
        name = r["frame"].replace("/", "_")
        raw = open(os.path.join(SESSIONS, r["frame"]), "rb").read()
        open(os.path.join(img_dir, name), "wb").write(raw)
        open(os.path.join(img_dir, "auto_" + name), "wb").write(enhance(raw, "auto"))
        cells = [("severity", str(r.get("severity"))), ("device said", r["said"]), ("old live line", r.get("live") or ""),
                 ("missed", "; ".join(r.get("missed") or [])), ("wrong", "; ".join(r.get("wrong") or [])),
                 ("suggested gold", r.get("suggested") or "")]
        table = "".join(f"<tr><th>{k}</th><td>{html.escape(v)}</td></tr>" for k, v in cells)
        out.append(f'<div class="r"><div><div class="p">raw</div><img src="mined_img/{name}">'
                   f'<div class="p">auto-brightened (what the device saw)</div><img src="mined_img/auto_{name}"></div>'
                   f'<div><div class="p">{html.escape(r["frame"])}</div><table>{table}</table></div></div>')
    page = ("<!doctype html><meta charset='utf-8'><title>mined session frames</title><style>"
            "body{font:17px -apple-system,sans-serif;margin:20px;max-width:1300px}"
            ".r{display:flex;gap:20px;border-bottom:1px solid #ddd;padding:14px 0}"
            ".r img{width:440px;display:block;margin-bottom:6px}.p{color:#888;font-size:14px}"
            "th{text-align:left;padding:4px 12px 4px 0;vertical-align:top;white-space:nowrap}"
            "td{padding:4px 0}</style>" + "".join(out))
    path = os.path.join(OUT, "mined.html")
    open(path, "w").write(page)
    print(path)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict((a[2:].split("=", 1) + [True])[:2] for a in sys.argv[1:] if a.startswith("--"))
    if args[0] == "run":
        out = os.path.join(OUT, f"mined_{opts['tag']}.jsonl") if "tag" in opts else MINED
        run(args[1], int(opts["limit"]) if "limit" in opts else None, out,
            MINED if "only-bad" in opts else None)
    elif args[0] == "add":
        add(int(opts.get("min", 2)))
    elif args[0] == "page":
        page()
