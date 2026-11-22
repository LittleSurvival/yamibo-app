"""Negative controls for the waterfall checker, using real local HTTP exchanges."""
import copy
import http.client
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys
import threading
import time
from network_contract import validate

def main():
    server_events = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            code = 302 if self.path == "/redirect" else 200
            server_events.append(dict(id=self.headers["X-QA-Attempt"], method="GET",
                                      resource=self.path, status=code))
            self.send_response(code)
            if code == 302:
                self.send_header("Location", "/image")
            self.end_headers()
            self.wfile.write(b"x")

        def log_message(self, *_):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    attempts = []
    def get(path):
        conn = http.client.HTTPConnection(*server.server_address, timeout=3)
        serial = str(len(attempts) + 1)
        begin = time.monotonic_ns() / 1e6
        conn.request("GET", path, headers={"X-QA-Attempt": serial})
        response = conn.getresponse()
        size = len(response.read())
        attempts.append(dict(id=serial, method="GET", resource=path, status=response.status,
                             component="images", trigger="control", phase="first-frame", sent=True,
                             bytes=size, start_ms=begin, elapsed_ms=time.monotonic_ns() / 1e6 - begin))
        dest = response.getheader("Location")
        conn.close()
        if dest:
            get(dest)

    fixtures = dict(fixture="observer-control-v1", mode="comic")
    def expect(path, status=200):
        return dict(phase="first-frame", method="GET", resource=path, status=status)
    results = {}
    raw = {}
    try:
        for name, paths, allowed in (
            ("one", ["/image"], [expect("/image")]),
            ("duplicate", ["/image", "/image"], [expect("/image")]),
            ("unobserved-transport", ["/image"], [expect("/image")]),
            ("unexpected-redirect", ["/redirect"], [expect("/image")]),
            ("declared-redirect", ["/redirect"], [expect("/redirect", 302), expect("/image")]),
        ):
            attempts.clear()
            server_events.clear()
            for path in paths:
                get(path)
            recording = dict(**fixtures, attempts=copy.deepcopy(attempts), server=copy.deepcopy(server_events),
                             instrumented=dict(forum=True, images=True, downloader=True, server=True), pending=0)
            if name == "unobserved-transport":
                recording["attempts"].clear()
            raw[name] = (dict(**fixtures, attempts=allowed), recording)
        # All five experiments are now idle. Measure the required quiet period together.
        idle = time.monotonic_ns()
        time.sleep(5.01)
        elapsed = (time.monotonic_ns() - idle) / 1e6
        for name, (contract, recording) in raw.items():
            recording["observe_after_idle_ms"] = elapsed
            results[name] = validate(contract, recording)
        out = Path(sys.argv[1])
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(dict(results=results, raw=raw), indent=2), encoding="utf-8")
        assert not results["one"] and not results["declared-redirect"], results
        assert all(results[n] for n in ("duplicate", "unobserved-transport", "unexpected-redirect")), results
        print("2 positive controls passed and 3 negative controls detected")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()

if __name__ == "__main__":
    main()
