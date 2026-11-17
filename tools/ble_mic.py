"""The XIAO's mic over Bluetooth (firmware/xiao 'a'/'q' stream): records while the Mac speaks a
sentence, decodes the IMA ADPCM, checks for gaps and times start/stop, then transcribes it.
Usage: ble_mic.py [out.wav] [sentence]"""
import array
import asyncio
import os
import subprocess
import sys
import time
import wave

from bleak import BleakClient, BleakScanner

from mic import levels

CMD = "5e0e0002-7a2b-4c1e-9a8f-2b6d3c4e5f60"
AUDIO = "5e0e0004-7a2b-4c1e-9a8f-2b6d3c4e5f60"
WHISPER_MODEL = os.path.expanduser("~/.cache/whisper/ggml-base.en.bin")

INDEX = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8]
STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60,
        66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371,
        408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
        1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385,
        24623, 27086, 29794, 32767]


def decode(packet):
    """One packet: [seq][pred lo][pred hi][index] + nibbles, low first. Returns (seq, samples)."""
    pred = int.from_bytes(packet[1:3], "little", signed=True)
    index = packet[3]
    out = []
    for b in packet[4:]:
        for code in (b & 15, b >> 4):
            step = STEP[index]
            delta = step >> 3
            if code & 4: delta += step
            if code & 2: delta += step >> 1
            if code & 1: delta += step >> 2
            pred = max(-32768, min(32767, pred - delta if code & 8 else pred + delta))
            index = max(0, min(88, index + INDEX[code]))
            out.append(pred)
    return packet[0], out


async def main(out, sentence):
    dev = await BleakScanner.find_device_by_name("sense-xiao", timeout=10)
    if not dev:
        raise RuntimeError("sense-xiao not advertising (phone app still connected? force-stop it)")
    packets, done = [], asyncio.Event()
    first_at = end_at = None

    def on_audio(_, data):
        nonlocal first_at, end_at
        if len(data) == 1 and data[0] == 0xEE:
            end_at = time.perf_counter()
            done.set()
        else:
            first_at = first_at or time.perf_counter()
            packets.append(bytes(data))

    async with BleakClient(dev) as client:
        await client.start_notify(AUDIO, on_audio)
        start = time.perf_counter()
        await client.write_gatt_char(CMD, b"a", response=False)
        say = subprocess.Popen(["say", "-r", "170", sentence])
        await asyncio.get_running_loop().run_in_executor(None, say.wait)
        await asyncio.sleep(0.3)
        stop = time.perf_counter()
        await client.write_gatt_char(CMD, b"q", response=False)
        try:
            await asyncio.wait_for(done.wait(), 3)
        except asyncio.TimeoutError:
            raise RuntimeError(f"no end marker; got {len(packets)} packets") from None

    seqs = [p[0] for p in packets]
    gaps = sum(1 for a, b in zip(seqs, seqs[1:]) if (a + 1) & 0xFF != b)
    pcm = array.array("h")
    for p in packets:
        pcm.extend(decode(p)[1])
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(pcm.tobytes())
    _, peak, rms = levels(b"\0" * 44 + pcm.tobytes())
    print(f"{len(packets)} packets ({len(pcm) / 16000:.2f} s audio, {stop - start:.2f} s held), "
          f"{gaps} sequence gaps")
    print(f"first packet {1000 * (first_at - start):.0f} ms after start, "
          f"end marker {1000 * (end_at - stop):.0f} ms after stop")
    print(f"level: peak {peak:.1f} dBFS, RMS {rms:.1f} dBFS  -> {out}")
    if os.path.exists(WHISPER_MODEL):
        text = subprocess.run(["whisper-cli", "-m", WHISPER_MODEL, "-f", out, "-nt", "-np"],
                              capture_output=True, text=True).stdout.strip()
        print(f"heard: {text}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "ble_mic.wav",
                     sys.argv[2] if len(sys.argv) > 2 else
                     "What is in front of me? Read the label on this bottle."))
