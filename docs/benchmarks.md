# Benchmarks (ESP32-CAM, Oct 28 2026)

All numbers are medians unless noted. Tools: `tools/bench_usb.py`, `tools/bench_wifi.py`,
`tools/phone_bench.sh` (runs on the phone via adb), `tools/bench_models.py`.

## Photo transfer: USB vs Wi-Fi

USB = CH340 adapter at 460800 baud to the Mac (921600 fails). Wi-Fi = board on the Pixel 9a
hotspot (2.4 GHz, board RSSI ≈ −40 dBm), fetched by the phone itself with
`nc` (HTTP/1.0, new connection per photo). JPEG quality 10, board drops its stale frame first.

| Size | Bytes | USB to Mac | Wi-Fi to phone | Wi-Fi worst of 8 |
|---|---|---|---|---|
| 320×240 | 4.5 KB | 0.16 s | 0.19 s | 0.44 s |
| 640×480 | 11.8 KB | 0.36 s | 0.33 s | 0.60 s |
| 1024×768 | 26.5 KB | 0.85 s | 0.66 s | 0.82 s |
| 1600×1200 | 73 KB | 1.76 s | 1.14 s | 1.53 s |

- Phone ↔ board ping: 25–340 ms (avg 158), 0% loss.
- Home Wi-Fi with the Mac on 5 GHz: requests stalled (0–3 KB in 8 s, ping 100% loss).
- Live at 1600×1200 over USB the camera step took 2.95 s (dim room: the sensor slows down), and
  photos were motion-blurred. 640×480 is the sweet spot.

## Model (google/gemini-3.1-flash-lite, `reasoning: none`, `provider.sort: latency`)

- ~1.0–1.2 s per photo; 1,156 input tokens at every photo size (320×240 to 1600×1200), ~$0.0003.
- Others on the same photo: claude-haiku-5.5 0.80 s (ignores word limits), qwen3.7-flash 1.7 s.

## Speech

- `say` per phrase: ~0.95 s before audio starts. Persistent NSSpeechSynthesizer: 0.08–0.10 s.

## End to end (photo taken → voice heard)

| Setup | Median |
|---|---|
| 320×240, `say` per phrase | ~2.0 s |
| 640×480, `say` per phrase (session 210002) | ~2.9 s |
| 640×480, persistent voice (session 212213) | 1.7 s |
| 1600×1200, persistent voice (session 215458) | 4.5 s |
