"""Moments: a frame (plus earlier frames) where the user asked nothing, and the one thing
the device should say. Our own eval set, built from public data, with human-approved gold.

  build                 pick moments from the downloaded datasets -> data/moments/moments.jsonl
  draft [--limit N]     draft a gold line for each moment without one (paid; review after)
  review                write data/moments/review.html; run tools/review_server.py and open
                        http://localhost:8765/review.html - edits save to disk as you type
  import [FILE.json]    apply review edits (default: data/moments/review_state.json, then archived)
  bilingual [--redo]    text: gold = spoken line in USER_LANG, gold_original = text as written
  sheet [CATEGORY]      contact sheet of moments with their gold, for a quick look

Categories: currency, text, items, outdoor (people comes later).
"""
import csv
import hashlib
import html
import io
import json
import os
import random
import sys
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

from orclient import call

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DATA = os.path.join(ROOT, "data")
OUT = os.path.join(DATA, "moments")
FILE = os.path.join(OUT, "moments.jsonl")
EVAL = os.path.join(DATA, "eval")
DRAFTER = "anthropic/claude-haiku-5.5"
GOLD_PX = 1280               # gold is drafted from a sharper copy than systems get
SERVER_PORT = 8765           # tools/review_server.py

STYLE = {
    "currency": "Say which note(s): '₹500 note.' Several: '₹500 and ₹100, total ₹600.' The back of "
                "a note also shows its value (numerals, colour, size), so a readable back counts. "
                "Only if the value truly can't be seen: 'Note, can't tell which; show the other side.'",
    "text": "Read the key text verbatim, in quotes: the title or first line plus the one fact that "
            "matters (amount due, expiry, dose, bus number and destination), then stop; at most "
            "12 words. Menus and price lists: actually decode them, the main items with prices, "
            "grouped ('buns ₹20-25, tea ₹10, thali ₹120'), up to 20 words; never just 'menu'. "
            "The listener speaks English: translate any other language (keep place names, "
            "transliterated); if a sign repeats itself in several languages, say it once in "
            "English. (`moments.py bilingual` then stores the original text separately.) "
            "Ignore subtitles or captions "
            "burned into a video; they are not in the scene. If unreadable: 'Text, too blurry; hold closer.'",
    "items": "Name the thing plus its identifying detail (brand, product name, flavour) and stop. "
             "No trivia (peeling corners, lit keys), no guesses about what you can't see, no "
             "assumptions ('red cloth', not 'red shirt' unless clearly a shirt). Position only in "
             "simple frame terms when useful ('top right', 'towards bottom right'), or what it sits "
             "on if that helps find it. Usually 2-12 words. Reviewed examples (draft -> approved): "
             "'Giraffe poster on the wall, top right corner peeling.' -> 'Giraffe poster.' | "
             "'Keyboard, backlit; the lit O key is top left.' -> 'Keyboard.' | "
             "'Dark-glass wine bottle; can't see the colour through it.' -> 'Dark-glass wine bottle.' | "
             "'Monitor showing Bing search results, on the desk ahead.' -> 'Monitor showing Bing search results.' | "
             "'Orange Crush soda, in the upper right of the view.' -> 'Orange Crush soda, top right.' | "
             "'Trader Joe's Milk Thistle bottle, 100 capsules, on the table in front of you.' -> "
             "'Trader Joe's Milk Thistle supplement bottle, 100 capsules, on the table over some printed paper.'",
    "home": "Indoors at home: a person close or facing you first; then doors (open/closed), obstacles at "
            "head, chest or hip height (rods, open cabinet doors, lamps), things low in the walking path "
            "(bins, stools, clothes piles), steps; readable text only if it matters. Plain, short, like a "
            "sighted friend. If it's too dark to tell, say 'Too dark to see.' rather than SILENT.",
    "outdoor": "Orient a blind walker, like a sighted friend would: (1) the kind of place in 1-2 words "
               "('Crowded market.', 'Clear road.', 'Narrow lane.'); (2) where the walkable path is "
               "('path straight ahead', 'left side clearer'); (3) what lines each side ('shops both "
               "sides', 'fruit stall left, handbags right'); (4) people and vehicles with which way they "
               "move ('people walking towards and away from you', 'family walking away', 'scooter "
               "coming from left'); (5) read any signboard that helps ('Signboard left: Direct Booking "
               "Office'). Plain words ('short barriers', not 'bollards'); real distances ('few feet "
               "away', 'across the road'), not just 'close'. Mention a hazard first only if it is "
               "really in the way or moving at you. About 10-20 words. Reviewed examples (draft -> approved): "
               "'Crowd ahead, centre, close. Parked scooters left.' -> 'Crowded market. Parked bikes on "
               "left, shops both sides, path straight ahead with a family walking away.' | "
               "'Bollard post ahead, center, close. Another right.' -> 'Short barriers ahead, many people "
               "walking towards and away from you, left side clearer.' | "
               "'Parked scooter left, close. Scaffolding and sandbags right.' -> 'Clear road, parked scooter "
               "left, few feet away. Scaffolding and sandbags right, across the road.' | "
               "'Signboard left, close. Pedestrians ahead.' -> 'Signboard left: Direct Booking Office. "
               "Pedestrians walking ahead into a brick-walled gate.'",
}
DRAFT_PROMPT = """You are writing the gold-standard spoken line for an assistive camera worn by a
blind person in India. The user asked nothing; this is what the device should SAY about this
moment, in a few seconds of speech. No filler, no 'I see', no 'there is'.
Category: {cat}. Rule: {rule}
If nothing here is worth saying, the line is exactly SILENT.
Hints from the source dataset (may be wrong or wordy; trust the image): {hints}
Return JSON only: {{"gold": "the line", "why": "<= 15 words: what you relied on, anything unsure"}}"""


def split_of(key):
    h = int(hashlib.md5(key.encode()).hexdigest(), 16) % 100
    return "test" if h < 25 else "dev" if h < 50 else "train"


def save_frame(img, name, crop_bottom=0.0):
    os.makedirs(os.path.join(OUT, "frames"), exist_ok=True)
    img = img.convert("RGB")
    if crop_bottom:
        img = img.crop((0, 0, img.width, int(img.height * (1 - crop_bottom))))
    img.thumbnail((GOLD_PX, GOLD_PX))
    path = os.path.join(OUT, "frames", name)
    img.save(path, "JPEG", quality=88)
    return os.path.relpath(path, OUT)


def load():
    return [json.loads(l) for l in open(FILE)] if os.path.exists(FILE) else []


def write(moments):
    with open(FILE, "w") as f:
        f.writelines(json.dumps(m, ensure_ascii=False) + "\n" for m in moments)


def moment(mid, cat, source, frames, hints, split_key):
    return {"id": mid, "category": cat, "source": source, "split": split_of(split_key),
            "frames": frames, "hints": hints, "gold": None, "gold_status": "none"}


TEXT_WORDS = ("read", "say", "written", "sign", "label", "text", "menu", "number", "title",
              "name of", "brand", "expir", "price", "screen", "board")
ITEM_WORDS = ("what is this", "what's this", "what is in", "what's in", "what kind", "which",
              "what type", "what color", "what flavor", "what flavour", "holding", "in my hand")


def build():
    random.seed(7)
    old = {m["id"]: m for m in load()}
    out = []

    cur = os.path.join(DATA, "currency", "Indian Currency Dataset")
    for denom in sorted(os.listdir(cur), key=int):
        for f in sorted(os.listdir(os.path.join(cur, denom))):
            mid = f"cur_{denom}_{f[:-4]}"
            frames = [save_frame(Image.open(os.path.join(cur, denom, f)), mid + ".jpg")]
            out.append(moment(mid, "currency", "mendeley-indian-currency", frames,
                              f"dataset label: ₹{denom}", mid))

    # EgoBlind: reading and item questions become no-question moments; keep 3 earlier frames.
    eb = {}
    for r in csv.DictReader(open(os.path.join(DATA, "egoblind", "train.csv"))):
        eb.setdefault(r["question_id"], r)
    items = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(EVAL, "items.jsonl"))}
    picked = {"text": [], "items": []}
    for qid, it in items.items():
        if it["dataset"] != "egoblind":
            continue
        q = it["question"].lower()
        cat = "text" if any(w in q for w in TEXT_WORDS) else "items" if any(w in q for w in ITEM_WORDS) else None
        if cat:
            picked[cat].append(it)
    for cat, n in (("text", 22), ("items", 16)):
        seen_video = set()
        for it in random.sample(picked[cat], len(picked[cat])):
            video = it["id"].rsplit("_", 1)[0]
            if video in seen_video or len([m for m in out if m["category"] == cat and m["source"] == "egoblind"]) >= n:
                continue
            seen_video.add(video)
            mid = f"eb_{it['id']}"
            frames = []
            for k, rel in enumerate(it["frames"]):   # already cropped of bottom captions, 640 px
                frames.append(save_frame(Image.open(os.path.join(EVAL, rel)), f"{mid}_{k}.jpg"))
            out.append(moment(mid, cat, "egoblind", frames,
                              f"user had asked: {it['question']} | answers: {' | '.join(it['refs'][:2])}", video))

    vz = json.load(open(os.path.join(DATA, "vizwiz", "val.json")))
    have = set(os.listdir(os.path.join(DATA, "vizwiz", "val")))
    vz = [x for x in vz if x["image"] in have and x["answerable"]]
    for cat, n in (("text", 20), ("items", 16)):
        words = TEXT_WORDS if cat == "text" else ITEM_WORDS
        pool = [x for x in vz if any(w in x["question"].lower() for w in words)]
        for x in random.sample(pool, min(n, len(pool))):
            mid = f"vz_{x['image'][:-4]}"
            if any(m["id"] == mid for m in out):
                continue
            answers = [a["answer"] for a in x["answers"]]
            top = sorted(set(answers), key=answers.count, reverse=True)[:3]
            frames = [save_frame(Image.open(os.path.join(DATA, "vizwiz", "val", x["image"])), mid + ".jpg")]
            out.append(moment(mid, cat, "vizwiz", frames,
                              f"user had asked: {x['question']} | answers: {' | '.join(top)}", mid))

    import pyarrow.parquet as pq
    for lang in ("hindi", "tamil", "kannada", "bengali", "telugu", "marathi"):
        path = os.path.join(DATA, "indicstr12", lang, "validation-00000-of-00001.parquet")
        if not os.path.exists(path):
            continue
        rows = pq.read_table(path).to_pylist()
        for r in random.Random(lang).sample(rows, min(4, len(rows))):
            mid = f"ist_{lang}_{r['id'][:8]}"
            frames = [save_frame(Image.open(io.BytesIO(r["image"]["bytes"])), mid + ".jpg")]
            out.append(moment(mid, "text", f"indicstr12-{lang}", frames,
                              f"{lang} words in the image (unordered): {' '.join(r['text'][:25])}", mid))

    bus = os.path.join(DATA, "tnbus", "data", "train-00000-of-00002.parquet")
    if os.path.exists(bus):
        skip = ("ticket", "map", "division", "logo", ".png", ".svg", "inside", "interior")
        rows = [r for r in pq.read_table(bus).to_pylist()
                if not any(s in (r["title"] or "").lower() for s in skip)]
        for r in random.Random("bus").sample(rows, min(14, len(rows))):
            mid = "bus_" + hashlib.md5(r["title"].encode()).hexdigest()[:8]
            frames = [save_frame(Image.open(io.BytesIO(r["image"]["bytes"])), mid + ".jpg")]
            out.append(moment(mid, "text", "tn-bus-wikimedia", frames,
                              f"photo title: {r['title']} | {(r['description'] or '')[:200]}", mid))

    short = {}
    sp = os.path.join(EVAL, "guidedog_short.jsonl")
    if os.path.exists(sp):
        short = {r["id"]: r.get("say") for r in map(json.loads, open(sp))}
    for sub, key in (("india", "gold_label"), ("india_silver", "silver_label")):
        folder = os.path.join(DATA, "guidedog", sub)
        labels = json.load(open(os.path.join(folder, "labels.json")))
        if sub == "india_silver":
            labels = random.sample(labels, 32)
        for x in labels:
            mid = f"gd_{sub}_{x['file'][:-4]}"
            frames = [save_frame(Image.open(os.path.join(folder, x["file"])), mid + ".jpg")]
            hint = "; ".join(short.get(f"{sub}/{x['file']}") or []) or (x[key] or "")[:300]
            out.append(moment(mid, "outdoor", f"guidedog-{sub}", frames,
                              f"{x['video_city']}: {hint}", f"{x['video_city']}-{x['file']}"))

    for m in out:                                   # keep gold and review across rebuilds
        if m["id"] in old:
            m.update({k: old[m["id"]][k] for k in ("gold", "gold_status", "why", "review_note")
                      if k in old[m["id"]]})
    write(out)
    counts = {}
    for m in out:
        counts[(m["category"], m["split"])] = counts.get((m["category"], m["split"]), 0) + 1
    for k in sorted(counts):
        print(*k, counts[k])


def b64(rel):
    import base64
    return "data:image/jpeg;base64," + base64.b64encode(open(os.path.join(OUT, rel), "rb").read()).decode()


def draft(limit=None, category=None, redo=None):
    """Drafts moments without gold. --redo=TEXT also redrafts unreviewed drafts whose gold
    contains TEXT ('*' = all unreviewed drafts in the category); reviewed gold is never touched."""
    moments = load()
    todo = [m for m in moments if (not category or m["category"] == category) and
            (not m.get("gold") or (redo and m["gold_status"] == "draft" and (redo == "*" or redo in m["gold"])))]
    todo = todo[:limit] if limit else todo
    print(f"drafting {len(todo)} gold lines with {DRAFTER}")

    def one(m):
        content = [{"type": "text", "text": DRAFT_PROMPT.format(cat=m["category"], rule=STYLE[m["category"]],
                                                                 hints=m["hints"])}]
        if len(m["frames"]) > 1:
            content[0]["text"] += "\nThe last image is now; earlier ones are the previous seconds."
        content += [{"type": "image_url", "image_url": {"url": b64(f)}} for f in m["frames"]]
        for _ in range(2):
            text, _, cost = call(content, DRAFTER, {"max_tokens": 1500, "reasoning": {"effort": "low"}},
                                 log={"moments": "draft", "id": m["id"]})
            s, e = text.find("{"), text.rfind("}")
            try:
                return m["id"], json.loads(text[s:e + 1])
            except ValueError:
                continue
        return m["id"], None

    by_id = {m["id"]: m for m in moments}
    with ThreadPoolExecutor(6) as ex:
        for mid, res in ex.map(one, todo):
            if res and res.get("gold"):
                by_id[mid].update(gold=res["gold"], why=res.get("why", ""), gold_status="draft")
                print(f"{mid:<40} {res['gold']}")
            else:
                print(f"{mid:<40} (no draft)")
    write(moments)


USER_LANG = "English"        # the listener's language; a per-user setting later

BILINGUAL_PROMPT = """An assistive camera for a blind person in India reads text aloud. The listener's
language is {lang}. Below is the current gold line for this photo, written or checked by a human:
keep every fact in it, including things that aren't text (people, places, directions).
Current gold: {gold}
Return JSON only:
{{"original": the scene text this line reads, exactly as written in its original script(s); "" if none,
  "gold": the spoken line in {lang}: same facts, short; translate any non-{lang} text (keep proper
  names like place names, transliterated: 'Tirupati'); if the sign already gives the same words in
  {lang}, say them once; keep read text in quotes}}
If the current gold is SILENT or says the text can't be read, return it unchanged with original "".
Add nothing that isn't in the current gold apart from translations."""


def bilingual(redo=False):
    """Text moments: split gold into the original text and a spoken line in USER_LANG."""
    moments = load()
    todo = [m for m in moments if m["category"] == "text" and m.get("gold") and (redo or "gold_original" not in m)]
    print(f"converting {len(todo)} text moments to {USER_LANG} + original")

    def one(m):
        content = [{"type": "text", "text": BILINGUAL_PROMPT.format(lang=USER_LANG, gold=m["gold"])},
                   {"type": "image_url", "image_url": {"url": b64(m["frames"][-1])}}]
        text, _, _ = call(content, DRAFTER, {"max_tokens": 1500, "reasoning": {"effort": "low"}},
                          log={"moments": "bilingual", "id": m["id"]})
        s, e = text.find("{"), text.rfind("}")
        try:
            return m["id"], json.loads(text[s:e + 1])
        except ValueError:
            return m["id"], None

    by_id = {m["id"]: m for m in moments}
    with ThreadPoolExecutor(6) as ex:
        for mid, res in ex.map(one, todo):
            m = by_id[mid]
            if not res or not res.get("gold"):
                print(f"{mid:<28} (failed, unchanged)")
                continue
            m.update(gold_before_bilingual=m["gold"], gold=res["gold"].strip(),
                     gold_original=(res.get("original") or "").strip())
            print(f"{mid:<28} {m['gold']}  ||  {m['gold_original'][:60]}")
    write(moments)


def review():
    moments = load()
    rows = []
    for m in moments:
        imgs = "".join(f'<img src="{html.escape(f)}" class="{"now" if k == len(m["frames"]) - 1 else "prev"}">'
                       for k, f in enumerate(m["frames"]))
        rows.append(f"""
<div class="m" data-id="{html.escape(m['id'])}" data-status="{m['gold_status']}">
  <div class="imgs">{imgs}</div>
  <div class="txt">
    <b>{html.escape(m['category'])}</b> · {html.escape(m['source'])} · {m['split']} · <code>{html.escape(m['id'])}</code>
    <div class="hint">hint: {html.escape(m['hints'])}</div>
    <div class="hint">drafter: {html.escape(m.get('why') or '')}</div>
    <textarea class="gold">{html.escape(m.get('gold') or '')}</textarea>
    {f'<div class="hint">original as written (what the scene text says, any script):</div><textarea class="orig">{html.escape(m.get("gold_original") or "")}</textarea>' if m["category"] == "text" else ""}
    <label><input type="radio" name="s_{html.escape(m['id'])}" value="approved"> ok</label>
    <label><input type="radio" name="s_{html.escape(m['id'])}" value="edited"> edited</label>
    <label><input type="radio" name="s_{html.escape(m['id'])}" value="rejected"> drop moment</label>
    <span class="was">{m['gold_status']}</span>
  </div>
</div>""")
    page = f"""<!doctype html><meta charset="utf-8"><title>sense moments review</title>
<style>
body{{font:15px -apple-system,sans-serif;margin:20px;max-width:1300px}}
.m{{display:flex;gap:16px;border-bottom:1px solid #ddd;padding:12px 0}}
.imgs{{width:560px;display:flex;flex-wrap:wrap;gap:4px;align-items:flex-start}}
.imgs img.now{{max-width:560px;max-height:380px}} .imgs img.prev{{height:70px;opacity:.8}}
.txt{{flex:1}} textarea{{width:100%;height:60px;font-size:17px;margin:8px 0}}
.hint{{color:#777;font-size:13px}} .was{{color:#aaa;margin-left:12px}}
#bar{{position:sticky;top:0;background:#fff;padding:8px 0;border-bottom:2px solid #333;z-index:1}}
</style>
<div id="bar">Edit the line the device should say; pick ok / edited / drop. Typing marks it edited;
comments in the box are fine. <b id="saved">connecting…</b> <span id="n"></span>
· filter: <select onchange="flt(this.value)"><option value="">all</option>
<option>currency</option><option>text</option><option>items</option><option>outdoor</option><option>home</option></select>
<label><input type="checkbox" onchange="todo(this.checked)"> only unreviewed</label></div>
{''.join(rows)}
<script>
const SRV='http://localhost:{SERVER_PORT}', K='sense-review-v2';
const st={{}}, pending={{}}; let timer=null;
const saved=document.getElementById('saved');
function count(){{document.getElementById('n').textContent=Object.keys(st).length+' reviewed this round'}}
function push(){{const body=JSON.stringify(pending);for(const k in pending)delete pending[k];
 fetch(SRV+'/save',{{method:'POST',body}}).then(r=>{{saved.textContent='saved to disk ✓';saved.style.color='green'}})
 .catch(e=>{{saved.textContent='NOT SAVED: start tools/review_server.py';saved.style.color='red';
   Object.assign(pending,JSON.parse(body))}})}}
function put(id,v){{st[id]=v;pending[id]=v;localStorage.setItem(K,JSON.stringify(st));count();
 saved.textContent='saving…';saved.style.color='#999';clearTimeout(timer);timer=setTimeout(push,400)}}
function show(id,v){{const d=document.querySelector(`.m[data-id="${{id}}"]`);if(!d)return;
 d.querySelector('textarea.gold').value=v.gold;const o=d.querySelector('textarea.orig');if(o&&v.original!=null)o.value=v.original;
 const r=d.querySelector(`input[value=${{v.status}}]`);if(r)r.checked=true}}
document.querySelectorAll('.m').forEach(d=>{{const id=d.dataset.id,ta=d.querySelector('textarea.gold'),o=d.querySelector('textarea.orig');
 const val=s=>{{const v={{gold:ta.value,status:s}};if(o)v.original=o.value;return v}};
 const was=d.dataset.status;if(was=='approved'||was=='edited'){{const r=d.querySelector(`input[value=${{was}}]`);if(r)r.checked=true}}
 [ta,o].filter(Boolean).forEach(t=>t.addEventListener('input',()=>{{d.querySelector('input[value=edited]').checked=true;put(id,val('edited'))}}));
 d.querySelectorAll('input[type=radio]').forEach(r=>r.addEventListener('change',()=>put(id,val(r.value))))}});
fetch(SRV+'/state').then(r=>r.json()).then(s=>{{for(const id in s){{st[id]=s[id];show(id,s[id])}}
 saved.textContent='saved to disk ✓';saved.style.color='green';count()}})
 .catch(e=>{{saved.textContent='NOT SAVED: start tools/review_server.py';saved.style.color='red'}});
function flt(c){{document.querySelectorAll('.m').forEach(d=>{{d.dataset.cat=(!c||d.querySelector('b').textContent==c)?'1':'0';apply()}})}}
let onlyTodo=false;function todo(v){{onlyTodo=v;apply()}}
function apply(){{document.querySelectorAll('.m').forEach(d=>{{const done=d.dataset.status=='approved'||d.dataset.status=='edited'||st[d.dataset.id];
 d.style.display=(d.dataset.cat!='0'&&!(onlyTodo&&done))?'':'none'}})}}
</script>"""
    path = os.path.join(OUT, "review.html")
    open(path, "w").write(page)
    print(path)


def import_review(path):
    st = json.load(open(path))
    moments = load()
    keep = []
    for m in moments:
        r = st.get(m["id"])
        if r and r["status"] == "rejected":
            continue
        if r:
            m.update(gold=r["gold"].strip(), gold_status=r["status"])
            if r.get("original") is not None:
                m["gold_original"] = r["original"].strip()
        keep.append(m)
    write(keep)
    if os.path.abspath(path) == os.path.abspath(os.path.join(OUT, "review_state.json")):
        import time    # applied edits must not be re-shown over newer gold
        os.replace(path, path.replace(".json", time.strftime(".applied-%Y%m%d-%H%M.json")))
    print({s: sum(1 for m in keep if m["gold_status"] == s) for s in ("approved", "edited", "draft", "none")},
          f"dropped {len(moments) - len(keep)}")


def sheet(cat=None, n=12, status=None):
    from PIL import ImageDraw, ImageFont
    import textwrap
    font = ImageFont.truetype("/System/Library/Fonts/Kohinoor.ttc", 16)
    ms = [m for m in load() if m.get("gold") and (not cat or m["category"] == cat)
          and (not status or m["gold_status"] == status)]
    random.seed(1)
    ms = random.sample(ms, min(n, len(ms)))
    W, H, C, COLS = 480, 300, 120, 3
    img = Image.new("RGB", (COLS * W, ((len(ms) + COLS - 1) // COLS) * (H + C)), "white")
    d = ImageDraw.Draw(img)
    for k, m in enumerate(ms):
        x, y = (k % COLS) * W, (k // COLS) * (H + C)
        im = Image.open(os.path.join(OUT, m["frames"][-1]))
        im.thumbnail((W - 10, H - 10))
        img.paste(im, (x + 5, y + 5))
        d.text((x + 6, y + H), f"{m['category']} · {m['gold_status']}", fill=(120, 120, 120), font=font)
        d.multiline_text((x + 6, y + H + 20), "\n".join(textwrap.wrap(m["gold"].replace("₹", "Rs "), 52)[:5]), fill="black", font=font)
    path = os.path.join(OUT, f"sheet_{cat or 'all'}{'_' + status if status else ''}.jpg")
    img.save(path, quality=85)
    print(path)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    {"build": build, "review": review}.get(args[0], lambda: None)()
    if args[0] == "draft":
        draft(int(opts["limit"]) if "limit" in opts else None, opts.get("category"), opts.get("redo"))
    elif args[0] == "bilingual":
        bilingual("redo" in sys.argv[2:] or "--redo" in sys.argv)
    elif args[0] == "import":
        import_review(args[1] if len(args) > 1 else os.path.join(OUT, "review_state.json"))
    elif args[0] == "sheet":
        sheet(args[1] if len(args) > 1 else None, int(opts.get("n", 12)), opts.get("status"))
