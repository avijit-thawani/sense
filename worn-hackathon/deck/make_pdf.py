"""Builds the pitch deck for Sighted (the project folder and app are still called Sense): draws each slide as an image, then saves
  sense-deck.pdf   one slide a page
  slides/NN.png    the same slides, shown by index.html (which adds the sound and the EchoVision clip)
Run from this folder:  ../../.venv-tools/bin/python make_pdf.py

Look: white ground for a projector in a lit room, black type, and one colour, the green of the glasses'
display, used as a highlighter. The typeface is Atkinson Hyperlegible Next, drawn by the Braille Institute
for low-vision readers. One idea a slide; the detail belongs in what is said aloud."""
import json
import os
from PIL import Image, ImageDraw, ImageFont

W, H, M = 1920, 1080, 120                      # slide size and outer margin, in pixels
INK, GREY, PAPER, GREEN = "#111411", "#6b716d", "#ffffff", "#5dff8f"
SESSION = "../../sessions-phone/glasses/20261122_023926"


def font(px, weight=400):
    f = ImageFont.truetype("fonts/AHN.ttf", int(px))
    f.set_variation_by_axes([weight])
    return f


def wrap(d, s, f, width):
    lines, cur = [], ""
    for word in s.split():
        t = (cur + " " + word).strip()
        if d.textlength(t, font=f) <= width or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = word
    return lines + [cur]


def text(d, x, y, s, px, weight=400, fill=INK, width=None, lead=1.12, mark=False, right=False):
    """Draws text from its top-left (or top-right) corner, wrapped to width. mark=True puts the green
    highlighter behind each line. Returns the y just under the last line."""
    f = font(px, weight)
    for line in (wrap(d, s, f, width) if width else [s]):
        lw = d.textlength(line, font=f)
        lx = x - lw if right else x
        if mark:
            d.rectangle([lx - px * 0.14, y + px * 0.08, lx + lw + px * 0.14, y + px * 1.16], fill=GREEN)
        d.text((lx, y), line, font=f, fill=fill)
        y += px * lead
    return y


def photo(name, w, h, focus=(0.5, 0.5), folder=None):
    """A photo (from the glasses unless a folder is given), cropped to fill w by h around the focus point."""
    im = Image.open(f"{folder or SESSION}/{name}.jpg").convert("RGB")
    s = max(w / im.width, h / im.height)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    x = round((im.width - w) * focus[0])
    y = round((im.height - h) * focus[1])
    return im.crop((x, y, x + int(w), y + int(h)))


def blank(colour=PAPER):
    im = Image.new("RGB", (W, H), colour)
    return im, ImageDraw.Draw(im)


rows = [json.loads(line) for line in open(f"{SESSION}/session.jsonl") if line.strip()]
boxes = {r["photo"][:3]: r["box"] for r in rows if r.get("mode") == "find" and r.get("box")}
slides = []          # (image, what it shows in words, recordings the HTML deck can play)
MOMENTS = "../../data/moments"
said = {}            # what the model said in the offline test runs (Oct 29-30), by moment id
for run in ("v4_1024__dev", "v4_1024__train"):
    for line in open(f"{MOMENTS}/runs/{run}.jsonl"):
        r = json.loads(line)
        said[r["id"]] = r["said"]


def tag(d, label):
    """A status label in the top-right corner: this slide is not from today's live take."""
    f = font(28, 700)
    w = d.textlength(label.upper(), font=f) + 44
    d.rectangle([W - M - w, 96, W - M, 156], fill=INK)
    d.text((W - M - w + 22, 108), label.upper(), font=f, fill=PAPER)


def add(im, alt, **play):
    slides.append((im, alt, play))


def boxed(name, w, h, width=9, focus=(0.5, 0.5)):
    """A find photo cropped to w by h, with the model's box for the phone drawn on it."""
    fr = photo(name, w, h, focus)
    iw, ih = Image.open(f"{SESSION}/{name}.jpg").size
    k = max(w / iw, h / ih)
    ox, oy = (iw * k - w) * focus[0], (ih * k - h) * focus[1]
    y0, x0, y1, x1 = [v / 1000 for v in boxes[name]]         # the model's box: top, left, bottom, right
    ImageDraw.Draw(fr).rectangle([x0 * iw * k - ox, y0 * ih * k - oy, x1 * iw * k - ox, min(h - 4, y1 * ih * k - oy)],
                                 outline=GREEN, width=width)
    return fr


# 1  The gap: a race where smart glasses push the sighted runners ahead and a blind runner is left behind the start.
def runner(d, x, cy, blind=False, r=52):
    """A face: smiling with green smart glasses, or blind (dark glasses, white cane) and unhappy."""
    d.ellipse([x - r, cy - r, x + r, cy + r], fill=PAPER, outline=INK, width=7)
    for gx in (x - 19, x + 19):
        d.ellipse([gx - 15, cy - 27, gx + 15, cy + 3], fill=INK if blind else GREEN, outline=INK, width=5)
    d.line([x - 5, cy - 12, x + 5, cy - 12], fill=INK, width=5)
    if blind:
        d.arc([x - 20, cy + 20, x + 20, cy + 48], 200, 340, fill=INK, width=6)          # mouth turned down
        d.line([x + r + 4, cy - 20, x + r + 60, cy + 44], fill=INK, width=7)            # white cane, red tip
        d.line([x + r + 48, cy + 30, x + r + 60, cy + 44], fill="#d6332a", width=7)
    else:
        d.arc([x - 22, cy - 2, x + 22, cy + 32], 20, 160, fill=INK, width=6)            # smile


im, d = blank()
text(d, M, 110, "Smart glasses give sighted people a boost.", 72, 700)
top, lane, start = 290, 124, 520
for i in range(5):
    d.line([M, top + i * lane, W - M, top + i * lane], fill="#d5d8d5", width=3)
d.rectangle([start - 6, top, start + 6, top + 4 * lane], fill=INK)
for i, (label, x) in enumerate([("Directions", 1500), ("Translation", 1250), ("Video calls", 1700), (None, start - 240)]):
    cy = top + i * lane + lane // 2
    if label:
        d.rectangle([start + 6, cy - 30, x, cy + 30], fill=GREEN)
        text(d, start + 40, cy - 23, label, 38, 500)
    runner(d, x, cy, blind=not label)
text(d, M, 850, "Blind people are behind the start line.", 84, 800, mark=True)
add(im, "A race track. Three smiling runners wearing smart glasses are far ahead, boosted by directions, translation and "
    "video calls. A blind runner with a cane, unhappy, is behind the start line. Blind people are behind the start line.")

# 2  Title: the find run fills the right side, the phone boxed as the glasses saw it.
im, d = blank()
im.paste(boxed("047", 960, H, 14), (W - 960, 0))
text(d, M - 6, 320, "Sighted", 204, 800)
text(d, M + 8, 640, "Glasses that tell a blind person what is in front of them, in two seconds.", 54, 400, width=660, lead=1.25)
add(im, "Sighted. Glasses that tell a blind person what is in front of them, in two seconds.")

# 3  The question: one quote, the worry marked.
im, d = blank()
y = text(d, M, 200, "“Will it tell me a car or bike is coming or on my left or 2 feet away.", 116, 700, width=1640)
y = text(d, M, y + 30, "Or not fast enough”", 116, 700, mark=True)
text(d, M, H - 170, "Real query from a blind person in NYC, Nov 2026", 32, 400, GREY)
add(im, "Will it tell me a car or bike is coming or on my left or 2 feet away. Or not fast enough.")

# 4  The wait: two bars on one time scale.
im, d = blank()
text(d, M, 110, "The wait for an answer", 60, 700)
per_s, bh = 290, 250                              # pixels per second, bar height
for i, (name, secs, bar, ink) in enumerate([("EchoVision", 5.3, INK, PAPER), ("Sighted", 2.3, GREEN, INK)]):
    by = 270 + i * (bh + 40)
    d.rectangle([M, by, M + secs * per_s, by + bh], fill=bar)
    text(d, M + 50, by + 70, name, 90, 700, ink)
    text(d, M + secs * per_s - 50, by + 70, f"{secs} s", 90, 700, ink, right=True)
add(im, "The wait for an answer: EchoVision 5.3 seconds, Sighted 2.3 seconds.", video="echovision_ask", audio="sense_ask",
    label="Hear both waits")

# 5  Price: the glasses and a month of use, against EchoVision.
#     Sighted glasses: MicroLumin M6 Air list price in China, 1,399 yuan (no display needed for a blind wearer).
#     Sighted month, at today's API prices and measured use: 50 answers a day at 0.2 cents each (model 0.04 cents,
#     voice 41 characters at $0.04 per 1,000) is $3; 90 live minutes at 6.5 cents (2.7 photos a second) is $6.
#     The $1 on the slide is the planned setup (find on the glasses' own detector), not today's: the voice made on the glasses (answers $0.60) and
#     find on a cheaper model ($0.07 per 1,000 photos, about $1) or the detector on the glasses (free).
im, d = blank()
text(d, M, 110, "What it costs", 60, 700)
cx1, cx2 = 800, 1340
text(d, cx1, 270, "EchoVision", 54, 500, GREY)
text(d, cx2, 270, "Sighted", 54, 700)
for i, (head, theirs, ours) in enumerate([("Glasses", "$599", "$200"), ("Each month", "$9.99", "$1")]):
    y = 400 + i * 250
    d.line([M, y - 40, W - M, y - 40], fill="#d5d8d5", width=3)
    text(d, M, y + 26, head, 72, 800)
    text(d, cx1, y, theirs, 120, 400)
    text(d, cx2, y, ours, 120, 800, mark=True)
text(d, M, H - 170, "50 questions a day, 90 live minutes a month", 36, 400, GREY)
add(im, "What it costs. Glasses: EchoVision 599 dollars, Sighted about 200. Each month: EchoVision 9.99, Sighted about 1, for 50 questions a day and 90 live minutes a month.")

# 6  The interface: type only, on the green.
im, d = blank(GREEN)
text(d, M - 10, 130, "Tap.", 380, 800)
text(d, M, 610, "Then speak, or stay quiet.", 104, 700)
text(d, M, H - 180, "No wake word.  No phone.  Double tap to stop.", 44, 500)
add(im, "Tap. Then speak, or stay quiet. No wake word, no phone, double tap to stop.")

# 7  How one tap becomes the right action: a cut-down version of the interface flowchart.
im, d = blank()
text(d, M, 110, "One tap. The model picks the tool.", 60, 700)
mid, up, down, line = 520, 300, 740, dict(fill=INK, width=5)
d.rounded_rectangle([M, mid - 70, M + 250, mid + 70], radius=70, fill=INK)
text(d, M + 62, mid - 38, "Tap", 64, 700, PAPER)
d.line([M + 250, mid, 470, mid], **line)
text(d, 490, mid - 34, "listens 2 s", 54, 500)
fork = 800
d.line([770, mid, fork, mid], **line)
d.line([fork, up, fork, down], **line)
d.line([fork, up, 1010, up], **line)                         # silence: describe, which also names money and signs
text(d, 830, up - 56, "silence", 34, 400, GREY)
text(d, 1030, up - 38, "Describe", 64, 700)
d.line([fork, down, 1010, down], **line)                     # speech: the model chooses
text(d, 830, down - 56, "speech", 34, 400, GREY)
text(d, 1030, down - 70, "the model", 54, 700, mark=True)
text(d, 1030, down, "picks a tool", 54, 700, mark=True)
for names, x0, y0, top, step in ((["The scene", "Money", "Signs", "What is close"], 1330, up, 186, 70),
                                 (["Answer", "Find", "Read", "Keep describing", "Recall earlier", "Settings"], 1370, down, 520, 76)):
    for i, name in enumerate(names):
        ty = top + i * step
        d.line([x0, y0, 1450, ty + 30], fill=INK, width=4)
        text(d, 1470, ty, name, 48, 700)
add(im, "One tap. The glasses listen for two seconds. Silence means describe: the scene, money, signs, what is close. "
    "After speech the model picks a tool: answer, find, read, keep describing, recall earlier or settings.")

# 8  Describe: the photo is the slide; the words it spoke sit on it.
im, d = blank()
im.paste(photo("001", W, H, (0.5, 0.75)), (0, 0))
d.rectangle([0, H - 330, 1240, H], fill=PAPER)
text(d, M, H - 280, "“Your laptop is open in front of you, displaying a terminal window.”", 60, 700, width=1060, lead=1.2)
text(d, M, H - 100, "One tap, two seconds.", 36, 400, GREY)
add(im, "Describe: Your laptop is open in front of you, displaying a terminal window.", audio="say_002", label="Hear it")

# 9  Ask: photo on the left, question and answer on the right.
im, d = blank()
im.paste(photo("002", 900, H, (0.55, 0.5)), (0, 0))
y = text(d, 1010, 300, "“What color is my watch?”", 60, 400, GREY, width=800)
text(d, 1010, y + 50, "“Your watch is silver with a white face.”", 92, 800, width=800, lead=1.1)
add(im, "Ask: What color is my watch? Your watch is silver with a white face.", audio="sense_ask", label="Hear it")

# 10  Find: a strip of what the glasses saw, edge to edge, the phone boxed in each frame.
im, d = blank()
text(d, M, 110, "“Can you help me find my phone?”", 84, 800)
frames = ["004", "030", "042", "052"]
fw = W // len(frames)
fh, top = round(fw * 3 / 4), 290
for i, name in enumerate(frames):
    im.paste(boxed(name, fw, fh), (i * fw, top))
under = top + fh + 70
text(d, M, under + 20, "52 photos in 19 seconds.", 44, 400, GREY)
text(d, W - M, under, "“You have it.”", 84, 800, mark=True, right=True)
add(im, "Find: Can you help me find my phone? Four photos with the phone boxed, ending with: You have it.",
    audio="q_003 say_006 say_007", label="Hear it")

# 11  Find, the sound: the beep comes from the side the thing is on.
im, d = blank()
text(d, M, 110, "It beeps in the ear on the phone's side.", 84, 800)
pw, ph, py = 760, 570, 300
px = (W - pw) // 2
im.paste(boxed("030", pw, ph, 11), (px, py))
cy = py + ph // 2
lx, rx, r = px // 2, W - px // 2, 100
for ring in (230, 185, 140):                                  # sound, left ear only: the phone is on the left
    d.ellipse([lx - ring, cy - ring, lx + ring, cy + ring], outline=GREEN, width=18)
d.ellipse([lx - r, cy - r, lx + r, cy + r], fill=INK)
text(d, lx - 26, cy - 56, "L", 96, 800, PAPER)
d.ellipse([rx - r, cy - r, rx + r, cy + r], outline="#b9beba", width=6)
text(d, rx - 30, cy - 56, "R", 96, 800, "#b9beba")
text(d, M, H - 150, "Faster as it nears the centre of view.  Higher in pitch when it is higher up.", 44, 500)
add(im, "The beep plays in the left or right ear, on the side the phone is on; faster near the centre of view, higher "
    "in pitch when it is higher up.")

# 12  Tested offline, before the glasses: money, signs, streets, each with what the model said.
im, d = blank()
tag(d, "Tested offline")
text(d, M, 110, "Money, signs, streets.", 84, 800)
tiles = [("cur_500_IMG20221010133520", 360, 480, (0.3, 0.2)), ("ist_telugu_76bd734d", 600, 319, (0.5, 0.5)),
         ("gd_india_silver_174_Mysore", 640, 320, (0.5, 0.5))]
x, base = M, 770
for name, tw, th, focus in tiles:
    im.paste(photo(name, tw, th, focus, folder=f"{MOMENTS}/frames"), (x, base - th))
    words = said[name].replace('"', "").split("; ")[0]
    if len(words) > 80:
        words = words[:words.rindex(",")] + "…"
    text(d, x, base + 28, f"“{words}”", 38, 700, width=tw, lead=1.22)
    x += tw + 40
text(d, M, H - 100, "35 of 37 Indian banknotes named exactly. Photos from public datasets.", 26, 400, GREY)
add(im, "Tested offline: a 500 rupee note named, a station sign read, a market street described.")

# 13  Memory and people: work in progress, shown as a mock-up on a real photo from the glasses (Nov 21, 11:05).
im, d = blank()
face = photo("004", 1180, H, (0.45, 0.5), folder="../notes/sessions/20261121_110534")
ImageDraw.Draw(face).rectangle([660, 350, 990, 690], outline=GREEN, width=12)
im.paste(face, (0, 0))
tag(d, "Work in progress")
y = text(d, 1250, 300, "“Who is that?”", 54, 400, GREY, width=560, lead=1.2)
y = text(d, 1250, y + 44, "“Bon. He founded a YC fintech startup in Indonesia.”", 76, 800, width=580, lead=1.1)
text(d, 1250, H - 210, "It remembers the people you know. For a new face it asks you, “Who was that?”", 36, 400, GREY, width=560, lead=1.3)
add(im, "Work in progress, memory and people: Who is that? Bon. He founded a YC fintech startup in Indonesia.")

# 14  Where it stands: four plain rows.
im, d = blank()
for i, (head, rest, mark) in enumerate([("Works", "describe, ask, find", False), ("Tested offline", "money, signs, streets", False),
                                        ("In progress", "memory, people", False), ("Next", "blind testers", True)]):
    y = 130 + i * 225
    if i:
        d.line([M, y - 50, W - M, y - 50], fill="#d5d8d5", width=3)
    text(d, M, y, head, 84, 800)
    text(d, 820, y, rest, 84, 400, mark=mark)
add(im, "Works: describe, ask, find. Tested offline: money, signs, streets. In progress: memory, people. Next: blind testers.")

os.makedirs("slides", exist_ok=True)
for old in os.listdir("slides"):
    os.remove(f"slides/{old}")
manifest = []
for i, (im, alt, play) in enumerate(slides, 1):
    im.save(f"slides/{i:02d}.png")
    manifest.append(dict(src=f"slides/{i:02d}.png", alt=alt, **play))
open("slides/manifest.js", "w").write("window.SLIDES = " + json.dumps(manifest, indent=1, ensure_ascii=False) + ";\n")
slides[0][0].save("sense-deck.pdf", save_all=True, append_images=[s[0] for s in slides[1:]], resolution=144)
print("sense-deck.pdf and slides/:", len(slides), "slides")
