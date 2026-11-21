"""A big running clock in the terminal for the frame-lag test (Lag.kt): this Mac's time as the
last two digits of the seconds, then milliseconds. Run in a wide terminal:  python3 lag_clock.py
Ctrl-C to stop."""
import sys, time
G = {"0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111", "4": "101101111001001",
     "5": "111100111001111", "6": "111100111101111", "7": "111001001001001", "8": "111101111101111", "9": "111001111001111",
     ".": "000000000000010"}
SX, SY = 5, 3     # each cell is 5 characters wide and 3 lines tall
sys.stdout.write("\x1b[2J\x1b[?25l")
try:
    while True:
        text = "%06.3f" % (time.time() % 100)
        rows = []
        for r in range(5):
            line = "  ".join("".join(("\u2588" if G[c][r * 3 + k] == "1" else " ") * SX for k in range(3)) for c in text)
            rows += [line] * SY
        sys.stdout.write("\x1b[H\n  LOOK AT THIS CLOCK THROUGH THE GLASSES\n\n" + "\n".join("  " + l for l in rows) + "\n")
        sys.stdout.flush()
        time.sleep(0.004)
except KeyboardInterrupt:
    sys.stdout.write("\x1b[?25h\n")
