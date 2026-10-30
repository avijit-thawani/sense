"""Run prompt files on chosen session frames and render them next to what the live system said.

Usage: try_prompts.py OUT.jpg PROMPT[,PROMPT...] SESSION/PHOTO.jpg [SESSION/PHOTO.jpg ...]
  paths are relative to sessions/; prompts relative to tools/. Frames go in at their
  recorded resolution, brightened like the live loop. No judge: this is for looking.
"""
import json
import os
import sys
import textwrap
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageDraw, ImageFont

from enhance import enhance
from orclient import describe

HERE = os.path.dirname(os.path.abspath(__file__))
SESSIONS = os.path.join(HERE, "..", "sessions")


def live_text(rel):
    folder, photo = os.path.split(rel)
    path = os.path.join(SESSIONS, folder, "session.jsonl")
    if os.path.exists(path):
        for line in open(path):
            r = json.loads(line)
            if r.get("photo") == photo and not r.get("event"):
                return f"{r.get('text')}" + (f"  [{r['status']}]" if r.get("status") not in (None, "spoken") else "")
    return "(not logged)"


def main():
    out, prompts, frames = sys.argv[1], sys.argv[2].split(","), sys.argv[3:]
    texts = {p: open(os.path.join(HERE, p)).read().strip() for p in prompts}

    def one(args):
        rel, p = args
        jpeg = enhance(open(os.path.join(SESSIONS, rel), "rb").read(), "auto")
        text, secs, _ = describe(jpeg, texts[p], extra={"max_tokens": 120},
                                 log={"try": os.path.basename(p), "photo": rel})
        return (rel, p), (text, secs)

    with ThreadPoolExecutor(6) as ex:
        res = dict(ex.map(one, [(f, p) for f in frames for p in prompts]))

    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 16)
    bold = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 16)
    W, ROW = 1250, 250
    img = Image.new("RGB", (W, ROW * len(frames)), "white")
    d = ImageDraw.Draw(img)
    for k, rel in enumerate(frames):
        y = k * ROW
        im = Image.open(os.path.join(SESSIONS, rel))
        im.thumbnail((310, ROW - 20))
        img.paste(im, (10, y + 10))
        x, yy = 340, y + 10
        d.text((x, yy), rel, font=font, fill=(130, 130, 130))
        yy += 24
        for label, text, color in [("live then", live_text(rel), (120, 120, 120))] + \
                [(os.path.basename(p)[:-4].replace("moment_", ""), *res[(rel, p)][:1], (20, 20, 20)) for p in prompts]:
            d.text((x, yy), label, font=bold, fill=color)
            for t in textwrap.wrap(text, 95)[:3]:
                d.text((x + 120, yy), t, font=font, fill=color)
                yy += 20
            yy += 6
        d.line((0, y + ROW - 1, W, y + ROW - 1), fill=(210, 210, 210), width=2)
    img.save(out, quality=85)
    write_html(out[:-4], frames, prompts, res)
    for rel in frames:
        print(rel, "|", " || ".join(res[(rel, p)][0] for p in prompts))


def write_html(base, frames, prompts, res):
    """Full-size frames, one per row, readable and zoomable. Images are copied next to the page
    so tools/review_server.py (which serves data/moments/) can show it."""
    import html
    import shutil
    img_dir = base + "_img"
    os.makedirs(img_dir, exist_ok=True)
    rows = []
    for rel in frames:
        name = rel.replace("/", "_")
        raw = open(os.path.join(SESSIONS, rel), "rb").read()
        open(os.path.join(img_dir, name), "wb").write(raw)
        open(os.path.join(img_dir, "auto_" + name), "wb").write(enhance(raw, "auto"))
        cells = [("live then", live_text(rel))] + \
                [(os.path.basename(p)[:-4].replace("moment_", ""), res[(rel, p)][0]) for p in prompts]
        lines = "".join(f"<tr><th>{html.escape(k)}</th><td>{html.escape(v)}</td></tr>" for k, v in cells)
        sub = os.path.basename(img_dir)
        rows.append(f'<div class="r"><div><div class="p">raw (what you saw)</div><img src="{sub}/{name}">'
                    f'<div class="p">auto-brightened (what the model saw)</div><img src="{sub}/auto_{name}"></div>'
                    f'<div><div class="p">{html.escape(rel)}</div><table>{lines}</table></div></div>')
    page = ("<!doctype html><meta charset='utf-8'><title>prompt try</title><style>"
            "body{font:17px -apple-system,sans-serif;margin:20px;max-width:1300px}"
            ".r{display:flex;gap:20px;border-bottom:1px solid #ddd;padding:14px 0}"
            ".r img{width:480px;height:auto;display:block;margin-bottom:6px}.p{color:#888;font-size:14px}"
            "th{text-align:left;padding:4px 12px 4px 0;vertical-align:top;white-space:nowrap}"
            "td{padding:4px 0}</style>" + "".join(rows))
    open(base + ".html", "w").write(page)
    print(base + ".html")


if __name__ == "__main__":
    main()
