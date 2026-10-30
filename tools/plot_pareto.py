"""Score vs latency and score vs cost for the model benchmark (docs/evals.md, round 4), with the
Pareto frontier: docs/media/model-pareto.png.

  plot_pareto.py

Reads the dev runs in data/moments/runs; a config's score is its mean judge score.
"""
import json
import os
import statistics

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402
from matplotlib.ticker import NullFormatter, NullLocator  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RUNS = os.path.join(HERE, "..", "data", "moments", "runs")
OUT = os.path.join(HERE, "..", "docs", "media", "model-pareto.png")

# run name -> (label, thinking, provider)
CONFIGS = {
    "v4_1024": ("Gemini 3.1 Flash Lite", "none", "google"),
    "v4_1024_low": ("Gemini 3.1 Flash Lite", "low", "google"),
    "bm_gem35fl_low": ("Gemini 3.5 Flash Lite", "low", "google"),
    "bm_luna_none": ("GPT-6 Luna", "none", "openai"),
    "bm_luna_low": ("GPT-6 Luna", "low", "openai"),
    "bm_haiku_none": ("Claude Haiku 5.5", "none", "anthropic"),
    "bm_haiku_low": ("Claude Haiku 5.5", "low", "anthropic"),
    "bm_nano_low": ("GPT-5 nano", "low", "openai"),
    "bm_qwen_none": ("Qwen 3.8 Flash", "none", "qwen"),
    "bm_qwen_low": ("Qwen 3.8 Flash", "low", "qwen"),
    "bm_gemma_none": ("Gemma 4 31B", "none", "google"),
    "bm_gemma_low": ("Gemma 4 31B", "low", "google"),
    "bm_argon_low": ("Gemini 4 Argon", "low", "google"),
    "bm2_gem25fl": ("Gemini 2.5 Flash Lite", "none", "google"),
    "bm2_gemma26": ("Gemma 4 26B A4B", "none", "google"),
    "bm2_nemotron": ("Nemotron 3.5 Super VL", "none", "nvidia"),
    "bm2_qwenomni": ("Qwen 3.8 Omni Flash", "none", "qwen"),
    "bm2_qwenvl30": ("Qwen3-VL 30B A3B", "none", "qwen"),
    "bm2_mistral4": ("Mistral Small 4", "none", "mistral"),
    "bm2_ministral8": ("Ministral 8B", "none", "mistral"),
    "bm2_ministral14": ("Ministral 14B", "none", "mistral"),
    "bm2_scout": ("Llama 4 Scout", "none", "meta"),
    "bm2_maverick": ("Llama 4 Maverick", "none", "meta"),
}
SHIPPED = ("Gemini 3.1 Flash Lite", "none")
COLORS = {"google": "#E8710A", "openai": "#1A73E8", "anthropic": "#9334E6", "qwen": "#188038",
          "nvidia": "#76B900", "mistral": "#D93025", "meta": "#00897B"}
# where to put each frontier label, in data coordinates (x, y, alignment), per chart
LABELS = {
    "lat": {("Gemini 4 Argon", "low"): (4.75, 8.05, "right"), ("Gemini 3.5 Flash Lite", "low"): (0.1, 7.95, "left"),
            ("Gemma 4 26B A4B", "none"): (2.0, 7.7, "left"),
            SHIPPED: (0.1, 6.0, "left"), ("Gemma 4 31B", "none"): (0.1, 5.75, "left")},
    "usd": {("Gemini 4 Argon", "low"): (3.3, 8.05, "right"), ("Gemma 4 26B A4B", "none"): (0.075, 8.08, "left"),
            ("Gemini 3.5 Flash Lite", "low"): (0.5, 7.78, "right"),
            SHIPPED: (0.55, 5.8, "left"), ("Qwen 3.8 Flash", "none"): (0.105, 7.4, "left")},
}
# visible window: weaker or slower configs are listed under the chart instead
Y_MIN, LAT_MAX = 5.5, 5.0


def load():
    rows = []
    for run, (name, thinking, provider) in CONFIGS.items():
        rs = [json.loads(line) for line in open(os.path.join(RUNS, f"{run}__dev.jsonl"))]
        rows.append({"name": name, "thinking": thinking, "provider": provider,
                     "score": statistics.mean(r["score"] for r in rs),
                     "lat": statistics.median(r["model_s"] for r in rs),
                     "usd": 1000 * statistics.mean(r["cost_usd"] for r in rs)})
    return rows


def frontier(rows, key):
    best, out = -1.0, []
    for d in sorted(rows, key=lambda d: (d[key], -d["score"])):
        if d["score"] > best:
            out.append(d)
            best = d["score"]
    return out


def main():
    rows = load()
    plt.rcParams.update({"font.family": "Helvetica", "font.size": 11})
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))
    for ax, key, title, xlabel in [(axes[0], "lat", "Score vs latency", "median seconds, request to answer"),
                                   (axes[1], "usd", "Score vs cost", "$ per 1000 moments (log)")]:
        front = frontier(rows, key)
        xs = [d[key] for d in front] + [LAT_MAX if key == "lat" else 5.5]
        ys = [d["score"] for d in front] + [front[-1]["score"]]
        ax.step(xs, ys, where="post", color="#888", ls="--", lw=1.2, zorder=1)
        for d in rows:
            c = COLORS[d["provider"]]
            ax.scatter(d[key], d["score"], s=90 if d in front else 45, zorder=3, linewidth=2, clip_on=True,
                       facecolor=c if d["thinking"] == "none" else "white", edgecolor=c)
            place = LABELS[key].get((d["name"], d["thinking"]))
            if place:
                text = d["name"] + (", low" if d["thinking"] == "low" else "") + \
                    (" (shipped)" if (d["name"], d["thinking"]) == SHIPPED else "")
                ax.annotate(text, (d[key], d["score"]), xytext=place[:2], textcoords="data", fontsize=10,
                            ha=place[2], va="center", color="#222",
                            arrowprops=dict(arrowstyle="-", color="#aaa", lw=0.8, shrinkA=2, shrinkB=5))
        if key == "usd":
            ax.set_xscale("log")
            ax.set_xlim(0.055, 5.5)
            ax.xaxis.set_minor_formatter(NullFormatter())
            ax.xaxis.set_minor_locator(NullLocator())
            ax.set_xticks([0.07, 0.1, 0.2, 0.5, 1, 2, 4])
            ax.set_xticklabels(["$0.07", "$0.1", "$0.2", "$0.5", "$1", "$2", "$4"])
        else:
            ax.set_xlim(0, LAT_MAX)
        ax.set_ylim(Y_MIN, 8.2)
        hidden = [d for d in rows if d["score"] < Y_MIN or (key == "lat" and d[key] > LAT_MAX)]
        if hidden:
            ax.text(0.99, 0.02, "not shown (score < 5.5" + (" or > 5 s" if key == "lat" else "") + "):\n" + "\n".join(
                d["name"] + (", low" if d["thinking"] == "low" else "") for d in hidden),
                transform=ax.transAxes, ha="right", va="bottom", fontsize=8.5, color="#777")
        ax.set_title(title, loc="left", fontsize=13, fontweight="bold")
        ax.set_xlabel(xlabel)
        ax.set_ylabel("score (0–10)")
        ax.grid(alpha=0.25)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    handles = [Line2D([], [], marker="o", ls="", color=c, markersize=8, label=n) for n, c in
               [("Google", COLORS["google"]), ("OpenAI", COLORS["openai"]), ("Anthropic", COLORS["anthropic"]),
                ("Qwen", COLORS["qwen"]), ("NVIDIA", COLORS["nvidia"]), ("Mistral", COLORS["mistral"]),
                ("Meta", COLORS["meta"])]]
    handles += [Line2D([], [], marker="o", ls="", markerfacecolor="white", markeredgecolor="#555",
                       markeredgewidth=2, markersize=8, label="hollow = low thinking"),
                Line2D([], [], ls="--", color="#888", label="Pareto frontier")]
    fig.legend(handles=handles, loc="lower center", ncol=9, frameon=False, fontsize=10, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    fig.savefig(OUT, dpi=110, facecolor="white")
    print(OUT)


if __name__ == "__main__":
    main()
