"""Host witnesses for one normal reader download on a fresh Simulator. Never writes to the app container."""
import hashlib
import json
import plistlib
import sqlite3
import subprocess
import sys
import threading
import zipfile
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from replay_origin import BOARD, HOME, ORIGIN, PROFILE, SEARCH, html

PIDS = (1401, 1402, 1403)
AUTHORS = (7, 7, 8)
PHASES = {"issue": "initial", "downloaded": "issue", "block": "downloaded",
          "cold-opened": "block", "saved-position": "cold-opened", "verified": "saved-position"}
# Give the later anchors enough ordinary text above them to distinguish paragraph restoration from opening the post at its top.
WALK = ("清晨我從車站出發，沿著河岸慢慢走。巷口的早餐店剛開門，老闆正在把一籠饅頭從蒸鍋裡提出來。"
        "過了石橋，路旁有一排剛修剪過的樹，樹下的長椅上放著一本沒寫完的旅行筆記。"
        "午後雲層漸厚，我在一間小書店避雨。店主拿來兩張不同年代的地圖，我們花了些時間才找到同一條街。"
        "地圖上曾經畫著市場的位置，現在已經改成公園，只有轉角那口井還留在原地。"
        "等雨停了，我沿著排水溝旁的小路走回河邊，記下了沿途路牌的名字，也畫了幾處下次可以停留的地方。"
        "回程經過石橋時，橋下有人在收釣具。太陽落下之前，我趕上了回城的車，將筆記好好收進背包。")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def thread_html(author):
    posts = []
    for i, (pid, uid) in enumerate(zip(PIDS, AUTHORS), 1):
        if author and author != str(uid):
            continue
        extra = {2: '<div class="quote"><p>PID1402_PARA2</p></div>', 3: '<div class="quote"><p>PID1403_COMMENT</p></div>'}.get(i, "")
        if i > 1:
            extra = f'<div class="quote"><p>{WALK if i == 2 else WALK[:108]}</p></div>' + extra
        posts.append(f'<div class="plc" id="pid{pid}"><div class="authi"><span class="mtit"><i class="y">{i}</i></span>'
                     f'<span class="z"><a href="home.php?mod=space&amp;uid={uid}">{"QA Author" if uid == 7 else "QA Guest Reply"}</a></span>'
                     f'<i class="mtime">2026-10-24</i></div><div class="message"><div id="postmessage_{pid}">'
                     f'<p>READER_FLOOR_{i}</p>{extra}<img src="{ORIGIN}/marker-{i}.png"></div></div></div>')
    return html('<link rel="canonical" href="forum.php?mod=viewthread&amp;tid=140"><div class="header"><h2>'
                '<a href="forum.php?mod=forumdisplay&amp;fid=30">QA Forum</a></h2></div><div class="view_tit">QA Three Readers</div>' + ''.join(posts))


class RealDownloadOrigin:
    def __init__(self, simulator, bundle, output, downloads_root):
        self.simulator, self.bundle, self.output, self.downloads_root = simulator, bundle, output, downloads_root
        self.phase = "initial"
        self.events = []
        self.saved = None
        self.first_pid = None
        self.first_cold_pid = None
        self.blocked = False

    def __enter__(self):
        self.assets = self.output / "origin-images"
        subprocess.run([sys.executable, str(Path(__file__).with_name("generate_fixtures.py")), str(self.assets),
                        "--origin-images-only"], check=True, timeout=30)
        self.images = {p.name: p.read_bytes() for p in sorted(self.assets.glob("marker-*.png"))}
        assert len(self.images) == 3
        root = self.downloads_root() / "YamiboDownloads"
        files = sorted(str(p.relative_to(root)) for p in root.rglob("*") if p.is_file())
        (self.output / "initial-download-files.json").write_text(json.dumps(files))
        assert not files, "Newly installed sandbox already contains downloads"
        self.http = HTTPServer(("127.0.0.1", 18928), DownloadHandler)
        self.http.origin = self
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, exc_type, exc, traceback):
        if exc_type is not None:
            try:
                self.capture_failure_positions()
            except Exception as diagnostic:
                print(f"Could not write failure reading positions: {diagnostic}", file=sys.stderr)
        self.http.shutdown()
        self.thread.join(timeout=5)
        self.http.server_close()

    def capture_failure_positions(self):
        report = {"threadId": 140, "phase": self.phase}
        try:
            db, = self.downloads_root().parent.parent.rglob("yamibo.db")
            with closing(sqlite3.connect(db.as_uri() + "?mode=ro", uri=True, timeout=1)) as conn:
                conn.row_factory = sqlite3.Row
                conn.execute("BEGIN")  # Read all three sets at the same SQLite snapshot.
                for name, sql in (
                    ("text", """SELECT threadId, threadType, authorId, historyOrigin, page, postId, anchorPostId,
                                anchorPostRatio, anchorBlockId, anchorBlockType, anchorBlockRatio, globalScrollY,
                                viewportHeight, firstVisibleItemIndex, firstVisibleItemOffset, lastVisitTime
                                FROM ReadingHistory WHERE threadId = ? ORDER BY lastVisitTime DESC"""),
                    ("textAnchors", """SELECT threadType, authorId, origin, observedAt, floorAuthor, imageSource, occurrence
                                       FROM ReaderTextAnchor WHERE threadId = ? ORDER BY observedAt DESC"""),
                    ("images", """SELECT h.threadId, h.postId, h.pageIndex, h.totalPages, h.firstVisibleItemIndex,
                                  h.firstVisibleItemOffset, h.lastVisitTime, a.observedAt, a.floorPid, a.sourceId,
                                  a.occurrence, a.page AS forumPage, a.authorId AS scopeAuthor
                                  FROM ImageReadingHistory h LEFT JOIN ReaderImageAnchor a ON a.historyPostId = h.postId
                                  WHERE h.threadId = ? ORDER BY h.lastVisitTime DESC, a.observedAt DESC"""),
                ):
                    report[name] = [dict(row) for row in conn.execute(sql, (140,))]
        except Exception as diagnostic:
            report["read_error"] = str(diagnostic)
        (self.output / "real-download-failure-positions.json").write_text(json.dumps(report, indent=2), encoding="utf8")

    def data(self, path, query, method):
        mod = query.get("mod", [""])[0]
        if path in ("/", "/forum.php") and mod in ("", "index"):
            return 200, html(HOME), "home"
        if path == "/forum.php" and mod == "forumdisplay" and query.get("fid") == ["30"]:
            return 200, html(BOARD), "board"
        if path == "/forum.php" and mod == "viewthread" and query.get("tid") == ["140"] and query.get("page", ["1"]) == ["1"]:
            author = query.get("authorid", [""])[0]
            scope = author if author in ("7", "8") else ("other" if author else "all")
            return 200, thread_html(author), "thread-" + scope
        if path == "/home.php":
            return 200, html(PROFILE), "profile"
        if path == "/search.php":
            return (302, b"", "search-submit") if method == "POST" else (200, html(SEARCH), "search-results")
        if path in {"/" + name for name in self.images}:
            return 200, self.images[path[1:]], path[1:]
        if path == "/forum.php" and mod in ("viewthread", "redirect"):
            return 404, b"unmapped fixture resource", "thread-" + mod
        return 404, b"unmapped fixture resource", "unknown"

    def store(self):
        base = self.downloads_root() / "YamiboDownloads/library/7"
        manifest = base / "threads/140/manifest.json"
        raw = manifest.read_bytes()
        state = json.loads(raw)
        claim, = state["claims"]
        key = claim["item"]["key"]
        assert (key["tid"], key["page"], key.get("authorId"), claim["item"]["owner"]) == (140, 1, None, 7)
        assert [p["pid"] for p in claim["floors"]] == list(PIDS) and not claim["item"].get("missingImageCount")
        claimed = {i["sourceUrl"]: i["digest"] for i in claim["item"]["images"]}
        expected = {f"{ORIGIN}/{n}": digest(blob) for n, blob in self.images.items()}
        assert claimed == expected, "Image claim does not match the served originals"
        blobs = {p.name: digest(p.read_bytes()) for p in (base / "images").glob("*/*") if p.is_file()}
        assert blobs == {v: v for v in expected.values()}, "Raw downloaded image mismatch"
        packed = {}
        for pid, uid in zip(PIDS, AUTHORS):
            batch = state.get("batches", {}).get(str(pid), state.get("generation"))
            with zipfile.ZipFile(manifest.parent / f"posts-{batch}.zip") as z:
                body = z.read(f"{pid}.json")
            parsed = json.loads(body)
            assert parsed["pid"] == pid and parsed["author"]["uid"] == uid and f"READER_FLOOR_{pid-1400}" in parsed["contentHtml"]
            packed[str(pid)] = digest(body)
        assert packed == state["hashes"]
        return {"manifest_sha256": digest(raw), "key": key, "post_sha256": packed, "image_sha256": claimed}

    def pid(self):
        app = Path(subprocess.check_output(["xcrun", "simctl", "get_app_container", self.simulator, self.bundle, "app"], text=True).strip())
        binary = (app / plistlib.loads((app / "Info.plist").read_bytes())["CFBundleExecutable"]).resolve()
        rows = subprocess.check_output(["ps", "-axo", "pid=,comm="], text=True).splitlines()
        found = []
        for row in rows:
            parts = row.strip().split(maxsplit=1)
            if len(parts) == 2 and parts[1].endswith("/" + binary.name) and Path(parts[1]).resolve() == binary:
                found.append(int(parts[0]))
        assert len(found) <= 1
        return next(iter(found), None)

    def cache(self):
        container = self.downloads_root().parent.parent
        db, = container.rglob("yamibo.db")
        with closing(sqlite3.connect(db.as_uri() + "?mode=ro", uri=True, timeout=3)) as conn:
            entries = [r[0] for r in conn.execute("SELECT cacheKey FROM DiskCacheEntry WHERE namespace='thread_page' AND cacheKey GLOB '140_*'")]
            floors = [r[0] for r in conn.execute("SELECT pid FROM ReaderFloorPage WHERE threadId=140")]
        caches = container / "Library/Caches"
        page_paths = [p.name for p in (caches / "yamibo_cache/thread_page").glob("140_*.json")]
        sizes = {len(b) for b in self.images.values()}
        originals = {digest(b) for b in self.images.values()}
        image_files = []
        for path in caches.rglob("*"):
            try:
                if path.is_file() and path.stat().st_size in sizes and digest(path.read_bytes()) in originals:
                    image_files.append(str(path.relative_to(caches)))
            except FileNotFoundError:
                continue  # A cache writer can atomically rename an entry during the online snapshot.
        return {"keys": sorted(entries), "pids": sorted(floors), "page_files": sorted(page_paths),
                "original_images_in_ordinary_cache": sorted(image_files)}

    def offline_requests(self):
        rows = [r for r in self.events if r["phase"] == self.phase and not r["resource"].startswith("control/")]
        witness = [r for r in rows if r["witness"]]
        assert all(r["blocked"] and r["status"] == 503 for r in rows), "Content gate did not stay closed"
        assert all(r["resource"] == "marker-1.png" and r["method"] == "GET" for r in witness), "Invalid offline witness"
        if self.phase == "block":
            assert witness, "Missing independent 503 witness before cold open"
        # Home/profile may refresh in the background. Catalog reads during a
        # mode switch are reported separately from thread/attachment requests.
        content = [r for r in rows if not r["witness"] and
                   (r["resource"].startswith("thread-") or r["resource"] in self.images)]
        assert not content, f"{self.phase}: offline reader tried to fetch content: {content}"
        unknown = [r for r in rows if not r["witness"] and r["resource"] not in
                   ("home", "profile", "board", "search-submit", "search-results")]
        assert not unknown, f"{self.phase}: unclassified traffic prevents verifying zero content requests: {unknown}"
        return rows

    def control(self, action):
        assert PHASES.get(action) == self.phase, f"Unexpected transition {self.phase} -> {action}"
        pid = self.pid()
        record = {"app_pid": pid}
        if action == "issue":
            assert pid and not list((self.downloads_root() / "YamiboDownloads").rglob("manifest.json"))
            self.first_pid = pid
        elif action == "downloaded":
            assert pid == self.first_pid
            record["library"] = self.store()
            record["cache"] = self.cache()
            cached = record["cache"]
            assert any(k.startswith("140_1_all_") and k + ".json" in cached["page_files"] for k in cached["keys"])
            assert set(PIDS).issubset(cached["pids"]), "Normal online thread was never indexed"
            self.saved = record["library"]
        elif action == "block":
            assert pid is None, "Reader process still running"
            record["cache"] = self.cache()
            assert not any(record["cache"].values()), "Normal thread cache still present"
            assert self.store() == self.saved
            self.blocked = True
        elif action == "cold-opened":
            assert pid and pid != self.first_pid, f"First offline launch not confirmed: online={self.first_pid}, now={pid}"
            record["requests"] = self.offline_requests()
            self.first_cold_pid = pid
        elif action == "saved-position":
            assert pid is None, f"Reader still running after saving position: {pid}"
            record["requests"] = self.offline_requests()
            assert self.store() == self.saved, "Mode switches changed canonical offline files"
        elif action == "verified":
            assert pid and pid != self.first_cold_pid, f"Second cold launch not confirmed: previous={self.first_cold_pid}, now={pid}"
            record["requests"] = self.offline_requests()
            record["library"] = self.store()
            assert record["library"] == self.saved, "Cold open changed canonical offline files"
        (self.output / f"real-download-{action}.json").write_text(json.dumps(record, indent=2))
        self.phase = action

    def verify(self):
        assert self.phase == "verified" and self.blocked
        online = {row["resource"] for row in self.events if row["status"] == 200 and not row["blocked"]}
        assert {"thread-all", *self.images}.issubset(online), online
        assert any(r["resource"] == "marker-1.png" and r["witness"] and r["status"] == 503 for r in self.events)
        assert all(r["status"] == 503 for r in self.events if r["blocked"] and not r["resource"].startswith("control/"))


class DownloadHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def handle_request(self):
        from time import monotonic_ns
        origin = self.server.origin
        parsed = urlsplit(self.path)
        action = parsed.path.removeprefix("/__offline/")
        query = parse_qs(parsed.query)
        row = {"host_ns": monotonic_ns(), "phase": origin.phase, "blocked": origin.blocked,
               "method": self.command, "witness": self.command == "GET" and parsed.path == "/marker-1.png" and
               not parsed.query and self.headers.get("X-Reader-Control") == "offline-witness"}
        payload = b""
        if parsed.path.startswith("/__offline/") and action in PHASES and self.command == "POST":
            resource = "control/" + action
            try:
                origin.control(action)
                status = 204
            except Exception as exc:
                status, payload = 409, repr(exc).encode()
        else:
            status, payload, resource = origin.data(parsed.path, query, self.command)
            if origin.blocked and resource == "profile" and (
                    self.command != "GET" or query.get("mod", ["space"]) != ["space"] or query.get("do", ["profile"]) != ["profile"]):
                # The same PHP endpoint also serves private messages and other actions.
                resource = "unknown"
            if resource in ("unknown", "thread-viewthread", "thread-redirect"):
                # Do not log query values, headers or arbitrary URL paths.
                row["path"] = parsed.path if parsed.path in ("/", "/forum.php", "/home.php", "/search.php") else "unmapped"
                row["route_keys"] = sorted({"mod", "goto", "do", "tid", "ptid", "pid", "page", "authorid", "fid"}.intersection(query))
            if origin.blocked:
                status, payload = 503, b"fixture origin offline"
        row.update(resource=resource, status=status, bytes=len(payload))
        if status == 409:
            row["reason"] = payload.decode(errors="replace")
        origin.events.append(row)
        with (origin.output / "real-download-requests.ndjson").open("a", encoding="utf8") as out:
            out.write(json.dumps(row) + "\n")
        self.send_response(status)
        if status == 302:
            self.send_header("Location", "/search.php?mod=forum&searchid=7001")
        self.send_header("Content-Type", "image/png" if status == 200 and resource.endswith(".png") else "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    do_GET = do_POST = handle_request
