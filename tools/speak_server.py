"""Keeps one macOS speech engine loaded. Reads phrases on stdin, one per line; for each
prints "audio <unix time>" when the first word starts and "done" when it finishes.

NSSpeechSynthesizer only speaks and calls back on a thread with a running main run loop,
so it lives in its own process rather than a thread of describe.py.
"""
import sys
import threading
import time

from AppKit import NSSpeechSynthesizer
from Foundation import NSDate, NSObject, NSRunLoop

RATE = int(sys.argv[1]) if len(sys.argv) > 1 else 230
lines = []
lock = threading.Lock()


def read_stdin():
    for line in sys.stdin:
        with lock:
            lines.append(line.strip())
    with lock:
        lines.append(None)


class Events(NSObject):
    def speechSynthesizer_willSpeakWord_ofString_(self, syn, rng, text):
        if not self.reported:
            self.reported = True
            print(f"audio {time.time():.3f}", flush=True)

    def speechSynthesizer_didFinishSpeaking_(self, syn, ok):
        self.done = True


syn = NSSpeechSynthesizer.alloc().initWithVoice_(None)
syn.setRate_(RATE)
events = Events.alloc().init()
syn.setDelegate_(events)


def pump(seconds):
    NSRunLoop.currentRunLoop().runUntilDate_(NSDate.dateWithTimeIntervalSinceNow_(seconds))


def speak(text):
    events.reported, events.done = False, False
    syn.startSpeakingString_(text)
    while not events.done:
        pump(0.005)


events.reported = True
speak("ready")  # loads the voice; heard once at startup
print("ready", flush=True)
threading.Thread(target=read_stdin, daemon=True).start()
while True:
    with lock:
        text = lines.pop(0) if lines else ""
    if text is None:
        break
    if text:
        speak(text)
        print("done", flush=True)
    else:
        pump(0.01)
