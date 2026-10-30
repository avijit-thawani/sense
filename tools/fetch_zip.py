"""Download chosen files from a remote zip without downloading the whole zip.

Zip keeps its index at the end, so we read the index with HTTP range requests,
then fetch only the members we want. A zip split into parts (part_aa, part_ab...)
is passed as several URLs in order.

Usage:
  fetch_zip.py URL [URL...] --list [--match REGEX]
  fetch_zip.py URL [URL...] --out DIR [--match REGEX] [--sample N] [--names a.jpg,b.jpg]
"""
import argparse
import io
import os
import re
import zipfile

import requests


class RemoteParts(io.RawIOBase):
    """One or more URLs as a single seekable, read-only file, fetched by HTTP range."""

    def __init__(self, urls):
        self.s = requests.Session()
        self.urls, self.sizes = [], []
        for u in urls:  # a 1-byte GET follows redirects (e.g. figshare's signed S3 links) and gives the true size
            r = self.s.get(u, headers={"Range": "bytes=0-0"}, timeout=60)
            r.raise_for_status()
            if "content-range" not in r.headers:
                raise RuntimeError(f"{u}: no byte-range support (status {r.status_code}); "
                                   "the host may be blocking scripts, try a browser download")
            self.urls.append(r.url)
            self.sizes.append(int(r.headers["content-range"].rsplit("/", 1)[1]))
        self.size = sum(self.sizes)
        self.pos = 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=0):
        self.pos = {0: off, 1: self.pos + off, 2: self.size + off}[whence]
        return self.pos

    def readinto(self, buf):
        n = min(len(buf), self.size - self.pos)
        got = 0
        while got < n:
            start, i = self.pos, 0
            while start >= self.sizes[i]:
                start -= self.sizes[i]
                i += 1
            end = min(start + (n - got), self.sizes[i]) - 1
            r = self.s.get(self.urls[i], headers={"Range": f"bytes={start}-{end}"}, timeout=120)
            r.raise_for_status()
            chunk = r.content
            buf[got:got + len(chunk)] = chunk
            got += len(chunk)
            self.pos += len(chunk)
        return got


def open_remote(urls):
    raw = RemoteParts(urls)
    # small buffer: each member costs one range request; big reads bypass it anyway
    return raw, zipfile.ZipFile(io.BufferedReader(raw, buffer_size=256 << 10))


def files(z, match=None):
    """Real files, skipping folders and macOS metadata (__MACOSX/, ._name)."""
    out = []
    for i in z.infolist():
        base = os.path.basename(i.filename)
        if i.is_dir() or base.startswith("._") or "__MACOSX" in i.filename:
            continue
        if match and not re.search(match, i.filename):
            continue
        out.append(i)
    return out


def spread(items, n):
    """n items evenly spaced through the list (keeps variety without randomness)."""
    if n >= len(items):
        return items
    step = len(items) / n
    return [items[int(k * step)] for k in range(n)]


def extract(z, infos, out, flat=False):
    os.makedirs(out, exist_ok=True)
    for k, info in enumerate(infos, 1):
        dst = os.path.join(out, os.path.basename(info.filename) if flat else info.filename)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(dst) and os.path.getsize(dst) == info.file_size:
            continue
        with z.open(info) as src, open(dst + ".part", "wb") as f:
            while chunk := src.read(8 << 20):
                f.write(chunk)
        os.replace(dst + ".part", dst)
        print(f"{k}/{len(infos)} {info.filename} {info.file_size / 1e6:.1f} MB", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("urls", nargs="+")
    ap.add_argument("--list", action="store_true", help="print a summary, download nothing")
    ap.add_argument("--match", help="regex on the path inside the zip")
    ap.add_argument("--names", help="comma-separated exact basenames to fetch")
    ap.add_argument("--sample", type=int, help="fetch N files spread evenly through the matches")
    ap.add_argument("--out")
    ap.add_argument("--flat", action="store_true", help="drop folders inside the zip")
    a = ap.parse_args()

    raw, z = open_remote(a.urls)
    infos = files(z, a.match)
    if a.names:
        want = set(a.names.split(","))
        infos = [i for i in infos if os.path.basename(i.filename) in want]
    if a.sample:
        infos = spread(infos, a.sample)
    total = sum(i.file_size for i in infos)
    print(f"zip {raw.size / 1e9:.2f} GB; selected {len(infos)} files, {total / 1e9:.3f} GB")
    if a.list:
        for i in infos[:20]:
            print("  ", i.filename, i.file_size)
        return
    extract(z, infos, a.out, a.flat)


if __name__ == "__main__":
    main()
