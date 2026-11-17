"""Turn the glasses' own Wi-Fi on/off over Bluetooth and probe what it answers.

Usage: glasses_wifi.py on [hold_seconds] | off | probe <ip>

'on' sends the vendor command that starts their access point (docs/glasses.md: 41: 02 01 04 02),
waits for the "wifi ip" event if one comes, and prints the address; 'off' stops it (02 01 09).
'probe' checks that address for the ports the vendor app uses (HTTP, RTSP, and a few common
ones) and tries a few obvious paths. Everything is our own code; no vendor library.
"""
import asyncio
import socket
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import glasses as G

PORTS = [80, 8000, 8080, 8554, 554, 8888, 8889, 81, 5000, 3000]


async def send_wifi(g, on):
    cmd, payload = (0x41, bytes([0x02, 0x01, 0x04, 0x02])) if on else (0x41, bytes([0x02, 0x01, 0x09]))
    await g.send(cmd, payload)
    end = time.monotonic() + 20
    ip = None
    while time.monotonic() < end:
        try:
            _, c, p = await asyncio.wait_for(g.queue.get(), 1)
        except asyncio.TimeoutError:
            continue
        print("  %s" % G.describe(c, p))
        if c == G.EVENT and p and p[0] == 0x08 and len(p) >= 5:
            ip = "%d.%d.%d.%d" % tuple(p[1:5])
            break
        if c == G.CONTROL and p[:2] == b"\x02\x01":
            break
    return ip


def try_port(ip, port, timeout=1.5):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((ip, port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def http_get(ip, port, path, timeout=4):
    s = socket.create_connection((ip, port), timeout)
    try:
        s.sendall(("GET %s HTTP/1.0\r\nHost: %s\r\n\r\n" % (path, ip)).encode())
        out = b""
        while len(out) < 4096:
            b = s.recv(4096)
            if not b:
                break
            out += b
        return out
    finally:
        s.close()


def probe(ip):
    print("probing %s" % ip)
    open_ports = []
    for p in PORTS:
        if try_port(ip, p):
            print("  port %d open" % p)
            open_ports.append(p)
    if not open_ports:
        print("  no common ports open")
    for p in [p for p in open_ports if p in (80, 8000, 8080, 8888, 81, 5000, 3000)]:
        for path in ("/", "/photo.jpg", "/capture", "/DCIM", "/index.html", "/api/photo"):
            try:
                r = http_get(ip, p, path)
            except OSError as e:
                print("  %d%s: %s" % (p, path, e))
                continue
            head = r.split(b"\r\n\r\n", 1)[0].decode(errors="replace").splitlines()
            print("  %d%s -> %s" % (p, path, (head[0] if head else "empty")[:80]))


async def main(mode, ip=None, hold=0):
    if mode == "probe":
        if not ip:
            print(__doc__)
            return
        probe(ip)
        return
    g = await G.connect()
    await g.handshake()
    beat = asyncio.create_task(g.heartbeat())
    try:
        ip = await send_wifi(g, mode == "on")
        print("wifi %s, address: %s" % (mode, ip or "not reported"))
        if ip and mode == "on":
            time.sleep(6)  # give the access point a moment to come up
            probe(ip)
        if hold and mode == "on":
            # The access point goes away soon after this Bluetooth session ends, so stay
            # connected (the heartbeat keeps running) while another device joins and fetches.
            print("holding the Bluetooth session %d s" % hold, flush=True)
            await asyncio.sleep(hold)
    finally:
        beat.cancel()
        await g.client.disconnect()


if __name__ == "__main__":
    args = sys.argv[1:]
    mode = args[0] if args else "probe"
    if mode == "on":  # on [seconds to keep the Bluetooth session open]
        asyncio.run(main(mode, hold=int(args[1]) if len(args) > 1 else 0))
    else:
        asyncio.run(main(mode, args[1] if len(args) > 1 else None))
