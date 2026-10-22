"""Loopback-only fixture server for the dedicated .noticeqa emulator build."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import time

ROOT = Path(__file__).resolve().parents[2]
CONTROL = ROOT / "build/server-notice-fixture.json"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/forum"):
            self.send_response(524)
            self.end_headers()
            self.wfile.write(b"Cloudflare: origin timed out (local QA fixture)")
            return
        if self.path.startswith("/notice/"):
            config = json.loads(CONTROL.read_text(encoding="utf-8"))
            mode = config.get("mode", "active")
            if mode == "unavailable":
                self.send_response(503); self.end_headers(); return
            now = int(time.time() * 1000)
            cancelled = mode == "cancelled" and "/3" in self.path
            value = dict(schemaVersion=1, httpStatus=524, revision=config["revision"] + int(cancelled),
                         enabled=not cancelled, startsAt=config.get("startsAt", now - 1000),
                         expiresAt=config.get("expiresAt", now + 3600_000),
                         message="" if cancelled else "百合會正在設備升級")
            if cancelled: value["expiresAt"] = value["startsAt"]
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        self.send_response(404); self.end_headers()


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8768), Handler).serve_forever()
