"""Talk to HeyCyan-family camera glasses (Bapick / Prism Pro, M08) over BLE from the Mac.

Usage:
  glasses.py scan                 list nearby BLE devices that look like the glasses
  glasses.py info                 battery + firmware
  glasses.py listen [secs]        print every event (press the glasses' buttons)
  glasses.py photo [out.jpg] [n]  take a photo and pull the BLE preview, with timings
                                  (n = preview size byte, default 2: width = 256 + 64*n)
  glasses.py send 41:02010d ...   send raw commands (cmd:payload hex), print replies

Own implementation of the vendor protocol; no vendor code. Packet: bc <cmd> <len LE16>
<crc16/modbus LE16 of payload> <payload>, written to de5bf72a, replies notify on de5bf729.
"""
import asyncio
import sys
import time

from bleak import BleakClient, BleakScanner

SERVICE = "de5bf728-d711-4e47-af26-65e3012a5dc7"
TX = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
RX = "de5bf729-d711-4e47-af26-65e3012a5dc7"
UART_RX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

CONTROL, BATTERY, INFO, EVENT, AUDIO, THUMB = 0x41, 0x42, 0x43, 0x73, 0x59, 0xFD

EVENTS = {0x02: "photo taken", 0x03: "talk button", 0x0A: "talk ended", 0x08: "wifi ip"}


def crc16(data):
    c = 0xFFFF
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def packet(cmd, payload=b""):
    payload = bytes(payload)
    return bytes([0xBC, cmd]) + len(payload).to_bytes(2, "little") + crc16(payload).to_bytes(2, "little") + payload


class Glasses:
    def __init__(self, client):
        self.client = client
        self.buf = bytearray()
        self.queue = asyncio.Queue()
        self.audio = asyncio.Queue()    # mic packets, kept apart so a photo can run meanwhile
        self.talk_ended = None          # when the glasses last said the talk ended

    def _on_notify(self, _, data):
        self.buf += data
        while len(self.buf) >= 6:
            if self.buf[0] != 0xBC:
                del self.buf[0]
                continue
            n = int.from_bytes(self.buf[2:4], "little")
            if len(self.buf) < 6 + n:
                return
            cmd, payload = self.buf[1], bytes(self.buf[6:6 + n])
            del self.buf[:6 + n]
            if cmd == EVENT and payload[:1] == b"\x0a":
                self.talk_ended = time.monotonic()
            (self.audio if cmd == AUDIO else self.queue).put_nowait((time.monotonic(), cmd, payload))

    async def start(self):
        await self.client.start_notify(UART_RX, lambda _, d: None)
        await self.client.start_notify(RX, self._on_notify)

    async def handshake(self):
        """Prism Pro's connect sequence, copied from a capture (button events need it)."""
        now = time.localtime()
        bcd = lambda v: (v // 10) << 4 | v % 10
        clock = bytes(bcd(v) for v in (now.tm_year % 100, now.tm_mon, now.tm_mday, now.tm_hour, now.tm_min, now.tm_sec))
        for cmd, payload in ((0x40, clock + bytes.fromhex("01290100000000000000")), (0x47, b"\x01\x00"),
                             (INFO, b"\x00\x00"), (CONTROL, b"\x02\x04"), (0x49, b"\x02\x01"),
                             (BATTERY, b"\x00\x00"), (0x51, b"\x01")):
            await self.send(cmd, payload)
            await asyncio.sleep(0.1)

    async def heartbeat(self):
        while self.client.is_connected:
            await self.send(0x45, b"\x04\x01")
            await asyncio.sleep(5)

    async def send(self, cmd, payload=b""):
        await self.client.write_gatt_char(TX, packet(cmd, payload), response=False)

    async def wait(self, cmd, timeout, match=lambda p: True):
        """Next reply with this command id; everything else is printed and dropped."""
        end = time.monotonic() + timeout
        while (left := end - time.monotonic()) > 0:
            try:
                t, c, p = await asyncio.wait_for(self.queue.get(), left)
            except asyncio.TimeoutError:
                break
            if c == cmd and match(p):
                return t, p
            if c != AUDIO:
                print("  (other) %s" % describe(c, p))
        return None, None


def describe(cmd, p):
    if cmd == EVENT and p:
        name = EVENTS.get(p[0], "event 0x%02x" % p[0])
        if p[0] == 0x08 and len(p) >= 5:
            return "%s %d.%d.%d.%d" % ((name,) + tuple(p[1:5]))
        return "%s  [%s]" % (name, p.hex(" "))
    return "cmd 0x%02x  [%s]" % (cmd, p[:40].hex(" ") + (" ..." if len(p) > 40 else ""))


def looks_like_glasses(d, a):
    return SERVICE in [u.lower() for u in a.service_uuids] or (d.name or "").startswith(("M0", "E0", "A0", "W6"))


async def find():
    devices = await BleakScanner.discover(timeout=12, return_adv=True)
    return sorted([(d, a) for d, a in devices.values() if looks_like_glasses(d, a)], key=lambda x: -x[1].rssi)


async def connect():
    d = await BleakScanner.find_device_by_filter(looks_like_glasses, timeout=20)
    if not d:
        raise RuntimeError("glasses not found: are they on, and disconnected from the phone's app?")
    print("connecting to %s (%s)" % (d.name, d.address))
    client = BleakClient(d)
    await client.connect()
    g = Glasses(client)
    await g.start()
    return g


async def cmd_scan():
    for d, a in await find():
        print("%-24s %s  rssi=%d" % (d.name, d.address, a.rssi))


async def cmd_info():
    g = await connect()
    try:
        await g.send(BATTERY, b"\x00\x00")
        _, p = await g.wait(BATTERY, 3)
        print("battery:", p.hex(" ") if p else "no reply")
        await g.send(INFO, b"\x00\x00")
        _, p = await g.wait(INFO, 3)
        print("info:", "".join(chr(b) if 32 <= b < 127 else "|" for b in p) if p else "no reply")
    finally:
        await g.client.disconnect()


async def cmd_listen(secs):
    g = await connect()
    await g.handshake()
    beat = asyncio.create_task(g.heartbeat())
    print("listening %ds; press the buttons on the glasses" % secs)
    end = time.monotonic() + secs
    try:
        while (left := end - time.monotonic()) > 0:
            try:
                t, c, p = await asyncio.wait_for(g.queue.get(), min(left, 1))
            except asyncio.TimeoutError:
                if g.audio.qsize():
                    print("  mic audio packets so far: %d" % g.audio.qsize())
                continue
            print("%7.2f  %s" % (secs - left, describe(c, p)))
    finally:
        beat.cancel()
        await g.client.disconnect()


async def cmd_send(steps, secs):
    """Each step 'cmd:payloadhex' (e.g. 41:02010d); prints every reply for `secs` after each."""
    g = await connect()
    try:
        for step in steps:
            c, _, pl = step.partition(":")
            print("-> %s %s" % (c, pl))
            await g.send(int(c, 16), bytes.fromhex(pl))
            end = time.monotonic() + secs
            while (left := end - time.monotonic()) > 0:
                try:
                    _, rc, rp = await asyncio.wait_for(g.queue.get(), left)
                except asyncio.TimeoutError:
                    break
                if rc != AUDIO:
                    print("   <- %s" % describe(rc, rp))
    finally:
        await g.client.disconnect()


async def pull_thumbnail(g):
    """Chunked: ask for chunk i with fd [01 i_lo i_hi]; reply [01 total(2) index(2) data...].
    The glasses serve a fixed-size buffer (16 KB, 32 KB for larger sizes); stop at the JPEG's end."""
    data, i, total = bytearray(), 0, None
    while total is None or i < total:
        await g.send(THUMB, bytes([1, i & 0xFF, i >> 8]))
        _, p = await g.wait(THUMB, 3)
        if p is None or len(p) < 5:
            print("  chunk %d: no reply" % i)
            return None
        total = int.from_bytes(p[1:3], "little")
        idx = int.from_bytes(p[3:5], "little")
        if total == 0:
            print("  no thumbnail available [%s]" % p.hex(" "))
            return None
        data += p[5:]
        i = idx + 1
        if (end := jpeg_end(data)) is not None:
            return bytes(data[:end]), i
    return bytes(data[:jpeg_end(data) or len(data)]), i


def jpeg_end(data):
    """End of the JPEG: the first ffd9 after the start-of-scan marker. Inside scan data every ff
    is followed by 00 or a restart marker, so this is the real end even when the rest of the
    buffer holds 0xaa padding or leftovers of an earlier, larger photo."""
    sos = data.find(b"\xff\xda")
    end = data.find(b"\xff\xd9", sos) if sos >= 0 else -1
    return end + 2 if end >= 0 else None


async def preview(g, size=2, verbose=False):
    """Take a photo and return the BLE preview JPEG (width 256 + 64*size), or None."""
    t0 = time.monotonic()
    await g.send(CONTROL, bytes([2, 1, 6, size, size]))
    t, p = await g.wait(CONTROL, 3, lambda p: p[:3] == b"\x02\x01\x06")
    if verbose:
        print("%.2fs  ai-photo ack  %s" % ((t or time.monotonic()) - t0, p.hex(" ") if p else "none"))
    t, p = await g.wait(EVENT, 6, lambda p: p[0] == 0x02)
    if verbose:
        print("%.2fs  event  %s" % ((t or time.monotonic()) - t0, describe(EVENT, p) if p else "none"))
    res = await pull_thumbnail(g)
    return res and res[0], res and res[1]


async def cmd_photo(out, size=2):
    g = await connect()
    try:
        t0 = time.monotonic()
        jpeg, chunks = await preview(g, size, verbose=True)
        t1 = time.monotonic()
        if not jpeg:
            return
        open(out, "wb").write(jpeg)
        print("%.2fs  saved %s  (%d bytes, %d chunks, complete=%s)"
              % (t1 - t0, out, len(jpeg), chunks, jpeg.startswith(b"\xff\xd8") and jpeg.endswith(b"\xff\xd9")))
    finally:
        await g.client.disconnect()


def main():
    args = sys.argv[1:] or ["scan"]
    if args[0] == "scan":
        asyncio.run(cmd_scan())
    elif args[0] == "info":
        asyncio.run(cmd_info())
    elif args[0] == "listen":
        asyncio.run(cmd_listen(int(args[1]) if len(args) > 1 else 30))
    elif args[0] == "photo":
        asyncio.run(cmd_photo(args[1] if len(args) > 1 else "glasses.jpg", int(args[2]) if len(args) > 2 else 2))
    elif args[0] == "send":
        asyncio.run(cmd_send(args[1:], 3))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
