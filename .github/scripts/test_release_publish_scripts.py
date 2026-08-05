import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
GITEA_SCRIPT = REPO / ".github/scripts/publish-gitea-release-asset.sh"
GITEE_SCRIPT = REPO / ".github/scripts/publish-gitee-release-asset.sh"


FAKE_CURL = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
with Path(os.environ["FAKE_CURL_LOG"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\n")

if "--fail-with-body" in args and any(arg == "-f" or arg.startswith("-f") for arg in args):
    raise SystemExit(90)

url = next((arg for arg in args if arg.startswith("https://")), "")
method = args[args.index("-X") + 1] if "-X" in args else "GET"
state_dir = Path(os.environ["FAKE_CURL_STATE"])

def next_count(name):
    path = state_dir / name
    count = int(path.read_text() if path.exists() else "0") + 1
    path.write_text(str(count))
    return count

if "/releases/tags/" in url and "--write-out" in args:
    count = next_count("lookup")
    mode = os.environ.get("LOOKUP_MODE", "404") if count == 1 else "200"
    if mode == "transport":
        raise SystemExit(7)
    if mode == "200":
        sys.stdout.write('{"id":7}\n200')
    else:
        sys.stdout.write(f'{{"message":"lookup"}}\n{mode}')
    raise SystemExit(0)

if method == "DELETE":
    raise SystemExit(0)

if method == "POST" and url.rstrip("/").endswith("releases"):
    sys.stdout.write('{"id":42}')
    raise SystemExit(0)

if "attach_files" in url:
    name = os.environ["APK_NAME"]
    sys.stderr.write("progress 1.0%\rprogress 100.0%\rprogress 100.0%\r")
    sys.stdout.write(json.dumps({"url": f"https://download.example/{name}"}))
    raise SystemExit(0)

if "/assets?name=" in url:
    name = os.environ["APK_NAME"]
    sys.stderr.write("progress 1.0%\rprogress 100.0%\rprogress 100.0%\r")
    sys.stdout.write(json.dumps({"browser_download_url": f"https://download.example/{name}"}))
    raise SystemExit(0)

if "--head" in args:
    headers = Path(args[args.index("--dump-header") + 1])
    expected_size = int(os.environ["APK_SIZE"])
    actual_size = expected_size + int(os.environ.get("GITEE_SIZE_DELTA", "0"))
    name = os.environ["APK_NAME"]
    headers.write_text(
        "HTTP/1.1 302 Found\r\nLocation: https://cdn.example/asset\r\n\r\n"
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/zip\r\n"
        f"Content-Length: {actual_size}\r\n"
        f'Content-Disposition: attachment; filename="{name}"\r\n\r\n',
        encoding="ascii",
    )
    raise SystemExit(0)

if "--range" in args:
    attempt = next_count("probe")
    failures = int(os.environ.get("GITEA_PROBE_FAILURES", "0"))
    output = Path(args[args.index("-o") + 1])
    output.write_bytes(b"bad!" if attempt <= failures else b"PK\x03\x04")
    raise SystemExit(0)

raise SystemExit(f"Unhandled fake curl call: {args}")
'''


class ReleasePublishScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        curl = self.bin_dir / "curl"
        curl.write_text(FAKE_CURL, encoding="utf-8")
        curl.chmod(0o755)
        sleep = self.bin_dir / "sleep"
        sleep.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        sleep.chmod(0o755)

        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"PK\x03\x04payload")
        self.changelog = self.root / "changelog.txt"
        self.changelog.write_text("Changes", encoding="utf-8")
        self.output = self.root / "github-output.txt"
        self.log = self.root / "curl.log"
        self.state = self.root / "state"
        self.state.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def environment(self, **overrides):
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin_dir}{os.pathsep}{env['PATH']}",
                "GITEA_TOKEN": "token",
                "GITEE_TOKEN": "token",
                "MIRROR_OWNER": "owner",
                "MIRROR_REPO": "repo",
                "TAG": "6",
                "TITLE": "stable-v0.0.5",
                "CHANGELOG": str(self.changelog),
                "APK": str(self.apk),
                "APK_NAME": "app.apk",
                "APK_SIZE": str(self.apk.stat().st_size),
                "RUNNER_TEMP": str(self.root),
                "GITHUB_OUTPUT": str(self.output),
                "FAKE_CURL_LOG": str(self.log),
                "FAKE_CURL_STATE": str(self.state),
            }
        )
        env.update(overrides)
        return env

    def run_script(self, script, **overrides):
        return subprocess.run(
            ["bash", str(script)],
            cwd=REPO,
            env=self.environment(**overrides),
            text=True,
            capture_output=True,
        )

    def curl_calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def assert_no_conflicting_fail_options(self):
        for call in self.curl_calls():
            self.assertFalse(
                "--fail-with-body" in call
                and any(arg == "-f" or arg.startswith("-f") for arg in call),
                call,
            )

    def test_gitea_handles_missing_release_and_retries_asset_probe(self):
        result = self.run_script(GITEA_SCRIPT, GITEA_PROBE_FAILURES="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("asset_url=https://download.example/app.apk", self.output.read_text())
        self.assertIn("retrying in 5s", result.stderr)
        self.assertNotIn("404", result.stderr)
        self.assertEqual(1, result.stderr.count("Gitea upload: 100.0%"))
        self.assert_no_conflicting_fail_options()

    def test_gitea_deletes_existing_release(self):
        result = self.run_script(GITEA_SCRIPT, LOOKUP_MODE="200")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any("DELETE" in call for call in self.curl_calls()))

    def test_gitea_rejects_non_zip_payload(self):
        result = self.run_script(GITEA_SCRIPT, GITEA_PROBE_FAILURES="3")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("validation failed after 3 attempts", result.stderr)

    def test_gitee_validates_redirected_asset_headers(self):
        result = self.run_script(GITEE_SCRIPT)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("asset_url=https://download.example/app.apk", self.output.read_text())
        self.assertEqual(1, result.stderr.count("Gitee upload: 100.0%"))
        self.assert_no_conflicting_fail_options()

    def test_gitee_rejects_size_mismatch(self):
        result = self.run_script(GITEE_SCRIPT, GITEE_SIZE_DELTA="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Asset size mismatch", result.stderr)

    def test_lookup_rejects_http_and_transport_errors(self):
        for script in (GITEA_SCRIPT, GITEE_SCRIPT):
            for mode, expected in (("401", "HTTP 401"), ("500", "HTTP 500"), ("transport", "Failed to query")):
                with self.subTest(script=script.name, mode=mode):
                    self.state.joinpath("lookup").unlink(missing_ok=True)
                    result = self.run_script(script, LOOKUP_MODE=mode)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
