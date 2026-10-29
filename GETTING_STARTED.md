# Getting started

From unboxing to hearing the camera describe your room. Takes about an hour. You do the
physical steps; Claude does the typing. Each step says exactly what to do and what you
should see.

## 1. What you need

- **The camera kit:** "ESP32-CAM-MB with OV2640 camera". In India:
  [OLE Electronics](https://olelectronics.in/product/esp32-cam-mb-wifi-bluetooth-board/) (~₹620)
  or search Amazon.in for `ESP32-CAM-MB OV2640`. It must include all three: the camera board,
  the black "MB" adapter board with a USB socket, and the small camera on a ribbon.
- **A micro-USB cable that carries data.** Many cables only charge. A cable that came with an
  older Android phone and could copy photos to a computer is a good bet. If your laptop only
  has USB-C ports, use a USB-C to micro-USB cable, or a USB-C adapter.
- **A laptop** (Mac or Windows) with internet, and **Claude** with access to a terminal on that
  laptop (Claude Code, or Claude in Cursor).
- **An OpenRouter key** for the vision model. Ask Avi; it costs about ₹1 per minute of use.
- Later, optional: an Android phone and Bluetooth earphones.

## 2. Nothing you do here can permanently break the board

This matters, so before anything else:

- **Uploading a program can't damage it.** The part of the chip that accepts programs is
  permanent and can't be overwritten. If a program misbehaves, you upload another one.
- **The USB cable is always safe.** It gives the board exactly the power it needs.
- There are only three things to avoid:
  1. Powering it from anything except a USB cable (no batteries or adapters wired to its pins).
  2. Letting metal (keys, foil, loose wires) touch the pins while it is plugged in.
  3. Unplugging or plugging the camera's ribbon while it is plugged in.

And the kit has two boards, so there is always a spare.

## 3. Open the box, but leave it assembled

The kit arrives ready. Inside you'll find two boards **stacked on top of each other**:

- the **camera board** on top, labelled "ESP32-CAM", with the camera folded over it and a
  microSD slot (you won't need a card);
- the **black adapter board** underneath, with the micro-USB socket and two tiny buttons
  marked **IO0** and **RST**.

**Don't pull the boards apart and don't touch the camera's orange ribbon.** It is already
clipped in. If there is a small coloured tab or film on the lens, you can peel it off; it
doesn't matter if you don't.

If your kit arrived as separate pieces, stop here and send Avi a photo of everything.

## 4. Get the project onto your laptop

Accept the GitHub invite from Avi, then ask Claude:

> Clone the private GitHub repo avijit-thawani/sense into a folder on my computer and open it.
> Then read README.md and GETTING_STARTED.md.

## 5. Plug it in

1. Put the **small end** of the micro-USB cable into the socket on the **black adapter board**
   (the bottom one), not the camera board.
2. Put the other end into your laptop.
3. Look at the adapter board: **a small red light should come on.** That means it has power.

No light? Try another USB port, then another cable. Leave it plugged in from here on.

## 6. Let Claude set it up, one step at a time

Copy each message below to Claude, one at a time, and wait for it to finish. Claude will
install what it needs (this takes a few minutes the first time). If it asks for permission
to run commands, that is expected.

**6a. Check the board is connected**

> Follow "For Claude" at the end of GETTING_STARTED.md: install the tools, then check that my
> board is connected and tell me what chip it reports. Don't ask me to touch the hardware
> unless a check fails.

You should hear back that it found an "ESP32" chip and its address. If Claude says the board
isn't found, the cable is almost always the reason: try another one.

**6b. Blinking light**

> Upload firmware/blink to my board and confirm it's running.

Turn the board over and look for a **tiny red light blinking about twice a second** on the
camera board. That is a program you just put on it. It keeps running every time the board gets
power, even without the laptop, until another program replaces it.

**6c. Camera check**

> Upload firmware/camcheck and tell me whether the camera works.

You should hear back "CAMERA OK".

**6d. First photo**

> Upload firmware/snap, take a photo, and show it to me.

Point the camera at something first. The photo is small (640×480) and may look dim; that is
normal for this camera indoors.

**6e. Hear it talk**

> Set up .env from .env.example with this OpenRouter key: (paste the key Avi gave you). Then
> open a terminal window where I can type and start tools/describe.py in it.

In that window, press **l** (lowercase L) to start. Point the camera around the room: every
second or two you'll hear a few words, like "Laptop screen ahead. Water bottle right." Press
**l** again to pause and **q** to quit.

**6f. Share what happened**

> Make a video of my latest session with tools/session_video.py, review it with
> tools/judge_video.py, and tell me where the video is.

Send that video to Avi. Your recordings stay on your laptop; they are not uploaded anywhere.

## 7. If something seems wrong

| What you see | What to do |
|---|---|
| No red light on the adapter | Another USB port, then another cable. |
| Claude can't find the board | Almost always a charge-only cable. On Windows, ask Claude to install the "CH340" driver. |
| Upload stuck on "Connecting…" | Ask Claude to retry. If it still sticks: hold **IO0**, tap **RST**, let go of IO0, retry. |
| "CAMERA NOT FOUND" | Unplug the USB cable first, then check the orange ribbon is pushed in flat. Send Avi a photo before doing anything more. |
| It talks about things that aren't there | The room may be too dark. Turn on a light. |
| It's quiet for a while | It only speaks about new things. Point it somewhere else. |

## 8. Words you'll see

- **Board:** the camera board plus adapter. **Firmware / program / sketch:** what runs on it.
- **Upload / flash:** copying a program onto the board (about 45 seconds).
- **Port:** how the laptop sees the board (`/dev/cu.usbserial-…` on a Mac, `COM3`-like on Windows).
- **Session:** one run of the live loop, saved under `sessions/`.

---

## For Claude

Board: AI-Thinker ESP32-CAM with OV2640 on an ESP32-CAM-MB adapter (CH340 USB-serial, vendor
0x1A86). Read the "Rules for agents" in README.md first: probe in software before asking for
any physical step, and never ask the user to take boards apart.

```bash
# tools (Mac: brew; Windows: winget or the arduino-cli installer)
brew install arduino-cli
arduino-cli config init
arduino-cli config add board_manager.additional_urls \
  https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core update-index && arduino-cli core install esp32:esp32
python3 -m venv .venv-tools && .venv-tools/bin/pip install -r requirements.txt

# find the port, check the chip (read-only)
.venv-tools/bin/python -c "import sys; sys.path.insert(0,'tools'); import snap; print(snap.find_port())"
.venv-tools/bin/esptool --port <PORT> chip-id

# compile and upload (115200: faster upload speeds corrupt on this adapter)
arduino-cli compile -b esp32:esp32:esp32cam firmware/blink
arduino-cli upload -b esp32:esp32:esp32cam -p <PORT> --upload-property upload.speed=115200 firmware/blink
arduino-cli monitor -p <PORT> -c baudrate=115200      # blink prints "hello from ESP32-CAM"
```

- `firmware/camcheck` prints `CAMERA OK: sensor id 0x26` at 115200 baud.
- `firmware/snap` talks at **460800** baud: `tools/snap.py out.jpg` takes one photo.
- `tools/describe.py` needs an interactive terminal (it reads single keys): open one for the
  user rather than running it yourself. On a Mac it speaks through `tools/speak_server.py`;
  on Windows it uses the built-in PowerShell voice.
- Optional, before the first upload: back up the factory program with
  `esptool --port <PORT> -b 115200 read-flash 0 ALL backups/factory.bin` (~6 minutes).
- Hardware details and gotchas: [docs/hardware.md](docs/hardware.md).
