"""OpenRouter vision calls for sense.

Key from learn/.env (never printed); attribution, model and spend cap from sense/.env.
Every call is checked against the cap, then logged to ledger.jsonl with its billed cost.
"""
import base64
import json
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "ledger.jsonl"


def _parse_env(path):
    out = {}
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                out[k.strip()] = v.strip().strip("'\"")
    return out


ENV = {**_parse_env(ROOT.parent / ".env"), **_parse_env(ROOT / ".env")}
MODEL = ENV.get("SENSE_MODEL", "google/gemini-3.1-flash-lite")
BUDGET_USD = float(ENV.get("SENSE_BUDGET_USD", "1"))


def spent_usd():
    if not LEDGER.exists():
        return 0.0
    return sum(json.loads(l).get("cost_usd") or 0 for l in LEDGER.read_text().splitlines() if l)


def describe(jpeg, prompt, model=MODEL, extra=None, log=None):
    """Returns (text, seconds, cost_usd). `jpeg` may be a list, oldest first, newest last.
    `extra` merges into the request body."""
    images = [{"type": "image_url",
               "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(j).decode()}}
              for j in (jpeg if isinstance(jpeg, list) else [jpeg])]
    body = {
        "max_tokens": 80,
        # Fastest provider first, and no hidden "thinking" before answering.
        "provider": {"sort": "latency"},
        "reasoning": {"effort": "none"},
        **(extra or {}),
    }
    return call([{"type": "text", "text": prompt}, *images], model, body, log, timeout=30)


def call(content, model, body=None, log=None, timeout=30):
    """One chat request with the spend cap and ledger. Returns (text, seconds, cost_usd)."""
    if not ENV.get("OPENROUTER_API_KEY", "").startswith("sk-or-"):
        raise RuntimeError("OPENROUTER_API_KEY missing: paste your OpenRouter key into .env "
                           "(see GETTING_STARTED.md, step 6e)")
    if spent_usd() >= BUDGET_USD:
        raise RuntimeError(f"spend cap ${BUDGET_USD} reached; raise SENSE_BUDGET_USD in sense/.env")
    body = {"model": model, "messages": [{"role": "user", "content": content}], **(body or {})}
    headers = {
        "Authorization": f"Bearer {ENV['OPENROUTER_API_KEY']}",
        "X-Title": ENV.get("OPENROUTER_PROJECT_ID", "sense"),
        "HTTP-Referer": ENV.get("OPENROUTER_APP_URL", "https://localhost/sense"),
    }
    t0 = time.time()
    r = requests.post("https://openrouter.ai/api/v1/chat/completions",
                      headers=headers, json=body, timeout=timeout)
    secs = time.time() - t0
    if not r.ok:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
    payload = r.json()
    text = (payload["choices"][0]["message"]["content"] or "").strip()
    usage = payload.get("usage") or {}
    cost = usage.get("cost") or 0
    cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens")
    row = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "model": model,
           "provider": payload.get("provider"), "latency_s": round(secs, 2), "cost_usd": cost,
           "tokens_in": usage.get("prompt_tokens"), "tokens_out": usage.get("completion_tokens"), "tokens_cached": cached,
           "reasoning_tokens": (usage.get("completion_tokens_details") or {}).get("reasoning_tokens"),
           **(log or {}), "text": text}
    with LEDGER.open("a") as f:
        f.write(json.dumps(row) + "\n")
    return text, secs, cost
