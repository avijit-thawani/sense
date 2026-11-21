"""An audio model listens to the audition clips and scores them. A shortlist only: ears decide.

  python3 voice_judge.py score     # each clip alone: naturalness, robotic, clarity (1-10)
  python3 voice_judge.py rank      # blind line-ups: all voices of one model, and one voice across models
  python3 voice_judge.py report    # table from both
  python3 voice_judge.py page      # round2/index.html with players, top picks first (no paid calls)

Clips are unnamed in the line-ups and each line-up runs several times in different orders, so
agreement between runs shows how far to trust it. Paid calls go through tools/orclient.py
(spend cap and ledger). Results: ../notes/voices/round2/judge.jsonl.
"""

import base64
import collections
import json
import random
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "tools"))
import orclient  # noqa: E402

OUT = HERE.parent / "notes" / "voices" / "round2"
LOG = OUT / "judge.jsonl"
JUDGE = "google/gemini-3.8-flash"
BODY = {"temperature": 0, "max_tokens": 600, "reasoning": {"effort": "low"}}

SCORE = """Listen to this text-to-speech clip. It is the voice of smart glasses for a blind person: short
instructions, then a short description of what is in front of them. Judge only how it sounds.
Reply with JSON only:
{"naturalness": 1-10 (10 = indistinguishable from a person talking),
 "robotic": 1-10 (10 = clearly synthetic: flat, metallic, buzzy, odd pauses or stress),
 "clarity": 1-10 (every word easy to catch, also through a small speaker),
 "pace": "slow" | "good" | "fast",
 "notes": "under 20 words, concrete things you heard"}"""

RANK = """These {n} text-to-speech clips say the same words. They are candidates for the voice of smart
glasses for a blind person. Rank them from most natural and human-sounding to most robotic, judging
only the sound (prosody, pauses, artifacts), not gender or accent.
Reply with JSON only: {{"ranking": [clip numbers, best first], "notes": "under 30 words"}}"""


def audio(path: Path) -> dict:
    return {"type": "input_audio", "input_audio": {"data": base64.b64encode(path.read_bytes()).decode(), "format": "wav"}}


def ask(content: list, log: dict) -> dict:
    text, _, cost = orclient.call(content, JUDGE, BODY, {"source": "voice_judge"}, timeout=90)
    m = re.search(r"\{.*\}", text, re.S)
    row = {**log, **(json.loads(m.group(0)) if m else {"notes": text[:200]}), "cost_usd": cost}
    with LOG.open("a") as f:
        f.write(json.dumps(row) + "\n")
    return row


def clips() -> dict:
    return {tuple(p.stem.split("__")): p for p in sorted(OUT.glob("*__*.wav"))}   # (model, voice) -> path


def score():
    for (model, voice), path in clips().items():
        r = ask([{"type": "text", "text": SCORE}, audio(path)], {"kind": "score", "model": model, "voice": voice})
        print(f"  {model:10} {voice:8} natural {r.get('naturalness')} robotic {r.get('robotic')} clear {r.get('clarity')} "
              f"{r.get('pace')} | {r.get('notes', '')[:90]}")


def lineup(kind: str, group: str, names: list, paths: list, seed: int, rotate: bool = False):
    order = list(range(len(names)))
    if rotate:      # short line-ups: every clip takes every position once, since the judge favours early clips
        order = order[seed:] + order[:seed]
    else:
        random.Random(f"{group} {seed}").shuffle(order)
    content = [{"type": "text", "text": RANK.format(n=len(names))}]
    for n, i in enumerate(order, 1):
        content += [{"type": "text", "text": f"Clip {n}:"}, audio(paths[i])]
    r = ask(content, {"kind": kind, "group": group, "seed": seed, "order": [names[i] for i in order]})
    ranked = [names[order[int(n) - 1]] for n in r.get("ranking", []) if str(n).isdigit() and 0 < int(n) <= len(names)]
    with LOG.open("a") as f:
        f.write(json.dumps({"kind": kind + "_result", "group": group, "seed": seed, "ranked": ranked}) + "\n")
    print(f"  {group:10} run {seed}: {' > '.join(ranked)} | {r.get('notes', '')[:90]}")


def rank():
    c = clips()
    for model in sorted({m for m, _ in c}):          # which voice is best on each model
        voices = [v for m, v in c if m == model]
        for seed in (1, 2, 3, 4):
            lineup("voices", model, voices, [c[model, v] for v in voices], seed)
    for voice in sorted({v for _, v in c}):          # which model is best for each voice
        models = [m for m, v in c if v == voice]
        if len(models) > 1:
            for seed in range(len(models)):
                lineup("models", voice, models, [c[m, voice] for m in models], seed, rotate=True)


def report():
    rows = [json.loads(l) for l in LOG.read_text().splitlines()]
    scores = {(r["model"], r["voice"]): r for r in rows if r["kind"] == "score"}
    place = collections.defaultdict(list)            # (model, voice) -> places in the voice line-ups
    wins, last, runs, first_clip = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    for r in rows:
        if r["kind"] == "voices_result":
            for i, v in enumerate(r["ranked"], 1):
                place[r["group"], v].append(i)
        if r["kind"] == "models_result" and r["ranked"]:
            wins[r["ranked"][0]] += 1
            last[r["ranked"][-1]] += 1
            runs.update(r["ranked"])
        if r["kind"] == "models" and r.get("ranking"):
            first_clip[r["ranking"][0]] += 1
    print("model      voice    natural robotic clear pace  places among voices on that model")
    for (model, voice), s in sorted(scores.items(), key=lambda kv: (kv[0][0], -(kv[1].get("naturalness") or 0))):
        print(f"{model:10} {voice:8} {s.get('naturalness')!s:>7} {s.get('robotic')!s:>7} {s.get('clarity')!s:>5} "
              f"{s.get('pace')!s:5} {place.get((model, voice), '')}")
    print("\nmodel picked as most natural, same voice across models:",
          ", ".join(f"{m} first {wins[m]}, last {last[m]} of {runs[m]}" for m in sorted(runs)))
    print("which clip position won those line-ups (even = no position bias):", dict(sorted(first_clip.items())))
    print(f"judge cost so far: ${sum(r.get('cost_usd') or 0 for r in rows):.4f}")


ABOUT = {  # ElevenLabs' own labels
    "sarah": "female, young, American", "jessica": "female, young, American", "alice": "female, middle-aged, British",
    "river": "neutral, middle-aged, American", "will": "male, young, American", "chris": "male, middle-aged, American",
    "eric": "male, middle-aged, American", "roger": "male, middle-aged, American", "brian": "male, middle-aged, American",
}
CURRENT = ("flash_v2_5", "sarah")
SKIP = {"brian"}   # judged 5/10 on every model: slow, voice changes mid-clip
PAGE_CSS = """
body{font:14px/1.45 system-ui,sans-serif;margin:0;background:#f6f7f9;color:#222}
main{max-width:1000px;margin:0 auto;padding:24px 16px 64px}
h1{margin:0 0 4px} .sub{color:#666;margin:0 0 20px}
section{background:#fff;border:1px solid #e3e6ea;border-radius:12px;margin:18px 0;padding:14px 16px}
section h2{margin:0;font-size:17px} section .desc{margin:6px 0 10px;color:#555}
.box{overflow:auto;border:1px solid #eef0f2;border-radius:8px}
table{border-collapse:collapse;width:100%;font-size:13px}
th{background:#f1f3f5;text-align:left;white-space:nowrap}
td,th{padding:6px 8px;border-bottom:1px solid #eef0f2;vertical-align:middle}
td.n{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap} td.top{font-weight:700}
tr.now td{background:#fff8e6}
audio{height:30px;width:220px;display:block} td.name{min-width:150px} td.wide{min-width:240px} small{color:#777}
"""


def page():
    """round2/index.html: top picks first, then every clip, to compare by ear."""
    import html
    rows = [json.loads(l) for l in LOG.read_text().splitlines()]
    scores = {(r["model"], r["voice"]): r for r in rows if r["kind"] == "score"}
    timing = {(r["model"].removeprefix("eleven_"), r["voice"]): r
              for r in map(json.loads, (OUT / "results.jsonl").read_text().splitlines())}
    place = collections.defaultdict(list)
    model_first = collections.Counter()
    for r in rows:
        if r["kind"] == "voices_result":
            for i, v in enumerate(r["ranked"], 1):
                place[r["group"], v].append(i)
        if r["kind"] == "models_result" and r["ranked"]:
            model_first[r["ranked"][0], r["group"]] += 1

    def mean(k):
        return sum(place[k]) / len(place[k]) if place[k] else 99

    def table(keys, numbered=False):
        h = ["<div class=box><table><tr>" + "".join(f"<th>{c}</th>" for c in (
            "#" if numbered else "", "voice", "model", "", "first audio", "places of 9", "best model for voice",
            "alone", "what the judge heard")) + "</tr>"]
        for n, k in enumerate(keys, 1):
            model, voice = k
            s, t = scores.get(k, {}), timing.get(k, {})
            first = f"{t['first_audio_s']:.2f} s" if t else ""
            h.append(f"<tr{' class=now' if k == CURRENT else ''}><td class='n top'>{n if numbered else ''}</td>"
                     f"<td class=name><b>{voice.title()}</b>{' (on the glasses now)' if k == CURRENT else ''}"
                     f"<br><small>{ABOUT.get(voice, '')}</small></td>"
                     f"<td>{model}</td><td><audio controls preload=none src='{model}__{voice}.wav'></audio></td>"
                     f"<td class=n>{first}</td>"
                     f"<td class=n>{', '.join(map(str, place[k]))}</td><td class=n>{model_first[k]} of 3</td>"
                     f"<td class=n>{s.get('naturalness', '')}/10</td>"
                     f"<td class=wide><small>{html.escape(s.get('notes', ''))}</small></td></tr>")
        return "\n".join(h) + "</table></div>"

    c = clips()
    picks = sorted((k for k in c if k[0] == "v4_turbo" and k[1] not in SKIP), key=mean)[:5]
    extra = [k for k in [("turbo_v2_5", "jessica"), CURRENT] if k in c]
    models = sorted({m for m, _ in c}, key=["v4_turbo", "turbo_v2_5", "flash_v2_5"].index)
    wins = {m: sum(n for (mm, _), n in model_first.items() if mm == m) for m in models}
    h = ["<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
         f"<title>sense voice lab</title><style>{PAGE_CSS}</style><main><h1>sense voice lab</h1>",
         "<p class=sub>ElevenLabs stock voices for the glasses, 24 kHz. Every clip says: <i>Tap to describe, swipe "
         "forward to ask, swipe back to find, double tap to stop. Laptop on the right, books on the left. The mug is "
         "ahead, close.</i> The judge is a Gemini audio model and is weak evidence: trust your ear.</p>",
         "<section><h2>Top picks</h2><div class=desc>The five best-placed voices on <b>v4_turbo</b>, the model the "
         f"judge preferred (most natural in {wins.get('v4_turbo')} of 27 same-voice line-ups, against "
         f"{wins.get('turbo_v2_5')} for turbo_v2_5 and {wins.get('flash_v2_5')} for flash_v2_5). The order within the "
         "five is loose: in line-ups of nine, the clip played first usually won. Below them, Jessica on turbo_v2_5 "
         "(placed first four times, but was also played first four times) and the current voice for reference."
         "</div>" + table(picks + extra, numbered=True) + "</section>"]
    if (OUT / "on_glasses_help.wav").exists():   # adb exec-out run-as com.sense.glasstest cat files/voice/28c38c04.pcm
        h.append("<section><h2>What the glasses actually have stored</h2><div class=desc>The instructions sentence, "
                 "copied off the glasses as stored (16 kHz, before the loudness boost and the small speakers). "
                 "Compare with Sarah on flash_v2_5 above: the code asks for that voice and model.</div>"
                 "<audio controls preload=none src='on_glasses_help.wav' style='width:320px'></audio></section>")
    for m in models:
        h.append(f"<section><h2>All voices on {m}</h2><div class=desc>Sorted by average place in four blind line-ups "
                 f"of the nine voices. First audio: {min(t['first_audio_s'] for k, t in timing.items() if k[0] == m):.2f}–"
                 f"{max(t['first_audio_s'] for k, t in timing.items() if k[0] == m):.2f} s from the Mac, connection "
                 "already open.</div>" + table(sorted((k for k in c if k[0] == m), key=mean)) + "</section>")
    h.append("<section><h2>Columns</h2><div class=desc><b>places of 9</b>: rank among the nine voices on that model, "
             "four runs in different orders. <b>best model for voice</b>: how often this model was judged the most "
             "natural of the three for this voice. <b>alone</b>: naturalness when the judge heard only this clip; "
             "nearly every clip got 7, so it separates little.</div></section></main>")
    (OUT / "index.html").write_text("\n".join(h))
    print("page", OUT / "index.html", "| picks:", ", ".join(v for _, v in picks))


if __name__ == "__main__":
    {"score": score, "rank": rank, "report": report, "page": page}[sys.argv[1] if len(sys.argv) > 1 else "report"]()
