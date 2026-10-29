"""Model latency on one saved photo.  Usage: bench_models.py PHOTO [N]"""
import sys

from describe import PROMPT
from orclient import describe

MODELS = [
    "google/gemini-3.1-flash-lite",
    "google/gemini-3.5-flash-lite",
    "anthropic/claude-haiku-5.5",
    "qwen/qwen3.7-flash",
    "openai/gpt-5-nano",
]

jpeg = open(sys.argv[1], "rb").read()
n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
for model in MODELS:
    times = []
    for _ in range(n):
        try:
            text, secs, cost = describe(jpeg, PROMPT, model=model, log={"bench": True})
            times.append(secs)
        except Exception as e:
            text = f"ERROR {str(e)[:120]}"
    times.sort()
    med = f"{times[len(times) // 2]:.2f}s" if times else "-"
    print(f"{model:32} median {med}  {text}")
