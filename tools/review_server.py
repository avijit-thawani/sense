"""Serves the moments review page and saves every edit straight to disk.

Usage: review_server.py [PORT]   then open http://localhost:8765/review.html
Edits land in data/moments/review_state.json as you type; `moments.py import-state`
applies them. The page also pushes edits made earlier in the file:// copy (browser storage).
"""
import json
import os
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "moments")
STATE = os.path.join(OUT, "review_state.json")
lock = threading.Lock()


def read_state():
    return json.load(open(STATE)) if os.path.exists(STATE) else {}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=OUT, **kw)

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Methods", "GET, POST")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/state":
            body = json.dumps(read_state(), ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    def do_POST(self):
        if self.path != "/save":
            self.send_error(404)
            return
        edits = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with lock:
            state = read_state()
            state.update(edits)
            tmp = STATE + ".tmp"
            json.dump(state, open(tmp, "w"), ensure_ascii=False, indent=1)
            os.replace(tmp, STATE)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"review at http://localhost:{port}/review.html ; saving to {os.path.relpath(STATE)}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
