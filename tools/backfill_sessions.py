"""Rebuilds sessions/<time>/ folders for runs before describe.py recorded them,
from ledger.jsonl and photos/desc-*.jpg. Free: no model calls.

Photo files and ledger rows are stamped to the whole second, so capture time is the
midpoint of the window both stamps allow (about ±0.5 s).
"""
import datetime as dt
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(__file__), "..")
DAY = dt.datetime(2026, 10, 28)
# (first answer, last answer, who, note). Pauses alone can't split runs: the user paused
# 15 s between Space presses, and agent tests started seconds after user runs.
RUNS = [
    ("20:02:30", "20:04:50", "user", "first version: sequential, 3 photos sent per answer (~3 s camera)"),
    ("20:16:15", "20:16:25", "agent test", "2 answers in flight, speech muted"),
    ("20:17:20", "20:18:30", "user", "2 answers in flight; only ~1 in 3 was spoken (which ones not logged)"),
    ("20:19:25", "20:19:40", "agent test", "paced to speech, speech muted"),
    ("20:22:30", "20:22:58", "user", "paced to speech, no context: every answer spoken"),
    ("20:23:00", "20:23:45", "agent test", "context modes none / text / text+photos at a dark wall"),
    ("20:24:00", "20:25:30", "user", "text context (last 3 phrases): hallucinated a story across dark frames"),
]


def sec_of_day(iso):
    return (dt.datetime.fromisoformat(iso) - DAY).total_seconds()


def stamp(sec):
    return (DAY + dt.timedelta(seconds=sec)).strftime("%H%M%S")


def iso(sec):
    return (DAY + dt.timedelta(seconds=sec)).isoformat(timespec="milliseconds")


def main():
    rows = [json.loads(l) for l in open(os.path.join(ROOT, "ledger.jsonl"))]
    rows = [r for r in rows if not r.get("bench") and not r.get("replay") and "photo" not in r]
    for first, last, who, note in RUNS:
        group = [r for r in rows if f"2026-10-28T{first}" <= r["ts"] <= f"2026-10-28T{last}"]
        events, used = [], set()
        for r in group:
            answered = sec_of_day(r["ts"])
            lag = (r.get("camera_s") or 0) + r["latency_s"]
            lo, hi = answered - lag, answered + 1 - lag
            # Each photo belongs to exactly one answer: take the nearest unused one.
            for cand in sorted(range(int(lo) - 3, int(lo) + 4), key=lambda c: abs(c + 0.5 - (lo + hi) / 2)):
                path = os.path.join(ROOT, "photos", f"desc-20261028-{stamp(cand)}.jpg")
                if os.path.exists(path) and path not in used:
                    used.add(path)
                    captured = min(max((lo + hi) / 2, cand), cand + 0.999)
                    events.append((r, path, captured, captured + lag))
                    break
        events.sort(key=lambda e: e[2])
        if not events:
            continue
        name = stamp(events[0][2])
        folder = os.path.join(ROOT, "sessions", f"20261028-{name}")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "session.jsonl"), "w") as f:
            for r, path, captured, answered in events:
                photo = os.path.basename(path)[-10:]
                shutil.copy2(path, os.path.join(folder, photo))
                text = r["text"]
                silent = text.strip(" .\"'").upper() == "SILENT"
                status = ("silent" if silent else "unknown" if "1 in 3" in note else "spoken")
                f.write(json.dumps({
                    "photo": photo, "mode": r.get("mode") or "none", "size": r.get("size", "?"),
                    "captured_at": iso(captured), "answered_at": iso(answered),
                    "camera_s": r.get("camera_s"), "model_s": r["latency_s"],
                    "cost_usd": r["cost_usd"], "status": status, "text": text,
                    "backfilled": True}) + "\n")
        with open(os.path.join(folder, "NOTE.txt"), "w") as f:
            f.write(f"{who}: {note}\nBackfilled from ledger.jsonl + photos/; times are ±0.5 s.\n")
        print(f"sessions/20261028-{name}: {len(events)}/{len(group)} matched  ({who}) {note}")


if __name__ == "__main__":
    main()
