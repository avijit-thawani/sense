"""Photo speed over Bluetooth LE from the XIAO running firmware/xiao.  Usage: ble_check.py [outdir]"""
import asyncio
import os
import statistics
import sys
import time

from bleak import BleakClient, BleakScanner

CMD = "5e0e0002-7a2b-4c1e-9a8f-2b6d3c4e5f60"
DATA = "5e0e0003-7a2b-4c1e-9a8f-2b6d3c4e5f60"
SIZES = {"s": "320x240", "v": "640x480", "x": "800x600", "g": "1024x768", "u": "1600x1200"}


class Photos:
    def __init__(self):
        self.want, self.buf, self.done = None, bytearray(), asyncio.Event()

    def on_data(self, _, data):
        if self.want is None:
            text = data.decode(errors="replace")
            if not text.startswith("JPG "):
                raise RuntimeError(text)
            self.want = int(text.split()[1])
        else:
            self.buf += data
        if self.want is not None and len(self.buf) >= self.want:
            self.done.set()

    async def grab(self, client):
        self.want, self.buf = None, bytearray()
        self.done.clear()
        await client.write_gatt_char(CMD, b"p", response=False)
        try:
            await asyncio.wait_for(self.done.wait(), 10)
        except asyncio.TimeoutError:
            raise RuntimeError(f"timed out: {len(self.buf)} of {self.want} bytes (notifications dropped?)")
        data = bytes(self.buf)
        if not (data.startswith(b"\xff\xd8") and data.rstrip(b"\x00").endswith(b"\xff\xd9")):
            raise RuntimeError(f"corrupt JPEG ({len(data)} bytes)")
        return data


async def main(out):
    os.makedirs(out, exist_ok=True)
    dev = await BleakScanner.find_device_by_name("sense-xiao", timeout=10)
    if not dev:
        raise RuntimeError("sense-xiao not advertising (is firmware/xiao flashed?)")
    async with BleakClient(dev) as client:
        print(f"connected, MTU {client.mtu_size}")
        photos = Photos()
        await client.start_notify(DATA, photos.on_data)
        for cmd, name in SIZES.items():
            await client.write_gatt_char(CMD, cmd.encode(), response=True)
            await asyncio.sleep(0.5)
            await photos.grab(client)
            times, sizes = [], []
            for _ in range(5):
                t = time.perf_counter()
                data = await photos.grab(client)
                times.append(time.perf_counter() - t)
                sizes.append(len(data))
            with open(os.path.join(out, f"ble_{name}.jpg"), "wb") as f:
                f.write(data)
            kb = statistics.mean(sizes) / 1024
            ms = statistics.median(times) * 1000
            print(f"{name:>9}: {kb:6.1f} KB  median {ms:5.0f} ms  ({kb / ms * 1000:.0f} KB/s)")
        await client.write_gatt_char(CMD, b"v", response=True)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "xiao_check"))
