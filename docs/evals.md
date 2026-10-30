# Evals: datasets, gold, and prompt hill-climbing (Oct 29-30 2026)

How the prompts on the phone were chosen. All data lives in `data/` (gitignored, ~5 GB); the
tools below re-download it. Every paid call goes through `tools/orclient.py`.

## Public datasets (samples, fetched file by file)

`tools/fetch_zip.py` reads a remote zip's index with HTTP range requests and pulls only chosen files.

| Dataset | Sample | What it gives us |
|---|---|---|
| [EgoBlind](https://github.com/doc-doc/egoblind) (NeurIPS 2025) | 107 videos, 424 questions | Blind users' own first-person video, with the question they asked at each moment. Chinese footage; burned-in captions cropped |
| [VizWiz](https://vizwiz.org) | 300 photos | Photos blind people took, with their spoken question; a third unanswerable |
| [GuideDog](https://huggingface.co/datasets/kjunh/GuideDog) | 476 India frames (18 human-checked) | Street hazards by clock direction, from walking videos in 10 Indian cities |
| [WalkVLM](https://openaccess.thecvf.com/content/ICCV2025/html/Yuan_WalkVLM_Aid_Visually_Impaired_People_Walking_by_Vision_Language_Model_ICCV_2025_paper.html) | 200 labels | Label format only (no images public) |
| [Indian Currency](https://data.mendeley.com/datasets/8ckhkssyn3/1) (Mendeley) | 56 notes, ₹10-₹2000 | Real phone photos of notes, folded and partial |
| [IndicSTR12](https://huggingface.co/datasets/darknight054/indicstr12-full) (IIIT-H) | 24 images, 6 languages | Signboards in Hindi, Tamil, Kannada, Bengali, Telugu, Marathi |
| [TN bus images](https://huggingface.co/datasets/RathnavelRahul/tamil-nadu-government-bus-images) | 14 photos | Route boards and bus notices (Wikimedia, open licences) |

## Round 1: questions on public data (`tools/eval_sets.py`)

Train/dev/test split by video or image; a judge model sees the image and scores whether the
reply carries the essential fact (not wording). EgoBlind dev, 81 questions:

| System | Useful | Wrong | Words |
|---|---|---|---|
| Live prompt, button = describe | 23% | 63% | 5.0 |
| Ask (user's question + photo), v3 | **71%** | 14% | 7.6 |
| Same + last 3 frames | 64% | 22% | 8.5 |

Knowing the question roughly triples usefulness; sending earlier frames didn't help and cost 3.5×.
Hence push-to-talk on the phone.

## Round 2: our own "moments" set with human gold (`tools/moments.py`)

A moment = a frame where the user asked nothing, and the one line the device should say.
232 moments in 5 categories; a model drafts gold from a sharper copy, a person approves or edits
it in a local review page (`tools/review_server.py`, edits save as you type).

| Category | Moments | Human-checked gold | Gold rule |
|---|---|---|---|
| currency | 56 | 56 | "₹500 note."; several: "₹500 and ₹100, total ₹600" |
| text | 66 (38 Indian) | 24 | key text in English (translated), original stored separately; menus decoded |
| outdoor | 50 | 13 | orient a walker: kind of place, path and its shape, sides, traffic direction, footing |
| items | 32 | 8 | the thing plus its identifying detail, nothing more |
| home | 28 | 0 (drafts) | mined from our own sessions: people close, doors, head-height obstacles |

Lessons from the human edits: drafts named one hazard instead of orienting; said "traffic jammed"
when it was moving; missed path shape, dead ends and wet floors; padded items with trivia.

## Round 3: hill-climbing the device prompt (`tools/climb.py`)

One prompt with no category given (the device must notice a note, a sign or a street itself),
judged against the human gold (Claude Haiku 5.5; agrees with Sonnet 5.5 on 19/21 critical calls).
Excluding SILENT-gold rows:

| Prompt (`tools/prompts/`) | Train score | Dev score | Critical errors (dev) |
|---|---|---|---|
| `moment_v0_live` (old live prompt) | 6.3 | 6.2 | 6% |
| `moment_v3` at 1024 px | 8.5 | 8.2 | 0% |
| `moment_v4` at 1024 px (shipped) | 8.5 | 7.8 | 6% (one case) |

v3 was silent on our own indoor frames (a man in front, a rod at head height, a closed door).
`tools/mine_sessions.py` ran it on all 240 session frames: 28 serious misses, now the "home"
category. v4 adds an indoor branch and "close obstacle first"; it fixes 22 of the 28.
Known v4 issues: says "Too dark to see" too readily; outdoor replies run long (~25 words).

## Shipped on the phone (Oct 30)

- Tap: `moment_v4.txt` at 1024×768, ~1.5 s to voice on the hotspot.
- Hold: `ask_app_v1.txt` (ask v3 adapted: English, direct answer first, longer when reading).

## Tools

| Tool | Does |
|---|---|
| `fetch_zip.py`, `fetch_egoblind.py` | sample remote zips file by file |
| `eval_sets.py`, `eval_sheets.py` | round 1 runs and side-by-side sheets |
| `moments.py`, `review_server.py` | build moments, draft gold, review page, import |
| `climb.py` | run a prompt on human gold, judge, report, sheets, re-judge with another model |
| `try_prompts.py` | a prompt on chosen session frames → HTML page, raw and brightened |
| `mine_sessions.py` | find frames where a prompt fails, add them as moments |
