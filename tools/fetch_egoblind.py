"""Fetch chosen EgoBlind videos without downloading the whole 53 GB archive.

The Hugging Face copy holds only the training split (videos 0-922); test videos are
on Google Drive, whose download quota is often exhausted.

Usage: fetch_egoblind.py [--list] [--sample N] [--ids 3,17] [--questions train.csv]
Default: every video referenced in data/egoblind/train.csv.
"""
import argparse
import csv
import os

from fetch_zip import extract, files, open_remote, spread

BASE = "https://huggingface.co/datasets/DogNeverSleep/EgoBlind/resolve/main/Video/"
PARTS = ["video_part_aa", "video_part_ab", "video_part_ac"]
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data", "egoblind")


def question_ids(path):
    with open(path) as f:
        return sorted({int(r["video_name"]) for r in csv.DictReader(f)})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", help="comma-separated video numbers (default: every video in --questions)")
    ap.add_argument("--questions", default=os.path.join(DATA, "train.csv"))
    ap.add_argument("--sample", type=int, help="N videos spread evenly through the chosen ids")
    ap.add_argument("--list", action="store_true", help="print sizes, download nothing")
    ap.add_argument("--out", default=os.path.join(DATA, "videos"))
    a = ap.parse_args()

    ids = [int(x) for x in a.ids.split(",")] if a.ids else question_ids(a.questions)
    ids = spread(ids, a.sample) if a.sample else ids
    raw, z = open_remote([BASE + p for p in PARTS])
    members = {int(os.path.splitext(os.path.basename(i.filename))[0]): i
               for i in files(z, r"\.mp4$")}
    chosen = [members[i] for i in ids if i in members]
    missing = [i for i in ids if i not in members]
    total = sum(i.file_size for i in chosen)
    print(f"archive: {len(members)} videos, {raw.size / 1e9:.1f} GB")
    print(f"chosen: {len(chosen)} videos, {total / 1e9:.2f} GB" + (f"; not in archive: {missing}" if missing else ""))
    if not a.list:
        extract(z, chosen, a.out, flat=True)


if __name__ == "__main__":
    main()
