import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("publisher", ROOT / ".github/scripts/publish-server-notice.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)
BASH = os.environ.get("VALIDATION_BASH", "C:/Program Files/Git/bin/bash.exe")


class PublishingTest(unittest.TestCase):
    def test_duration_format_and_ranges(self):
        for value, expected in [("00:00:01", 1), ("00:02:00", 120), ("01:00:00", 1440), ("99:23:59", 143999)]:
            self.assertEqual(expected, publisher.duration_minutes(value))
        for value in ["", "2h", "0:02:00", "00:00:00", "00:24:00", "00:00:60", "-1:00:00", "00:02:00\n"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                publisher.duration_minutes(value)

    def test_publish_and_cancel_contract(self):
        value = publisher.make_notice("publish", " 百合會正在設備升級 ", "00:02:00", 4, 100)
        self.assertEqual(7_200_100, value["expiresAt"])
        self.assertEqual("百合會正在設備升級", value["message"])
        cancel = publisher.make_notice("cancel", "ignored", "invalid", 5, 200)
        self.assertFalse(cancel["enabled"])
        self.assertEqual(cancel["startsAt"], cancel["expiresAt"])
        self.assertEqual("", cancel["message"])
        for args in [("other", "text", "00:01:00", 1, 100), ("publish", " ", "00:01:00", 1, 100),
                     ("publish", "x" * 1001, "00:01:00", 1, 100), ("publish", "x", "00:01:00", 0, 100)]:
            with self.assertRaises(ValueError): publisher.make_notice(*args)

    def test_cli_keeps_quotes_and_shell_text_literal(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            text = '設備 "升級"\n$(echo should-not-run); `literal`'
            env = dict(os.environ, NOTICE_OPERATION="publish", NOTICE_MESSAGE=text, NOTICE_DURATION="00:02:00",
                       GITHUB_RUN_NUMBER="23", GITHUB_RUN_ATTEMPT="2", NOTICE_OUTPUT_DIR=tmp)
            subprocess.run([sys.executable, str(ROOT / ".github/scripts/publish-server-notice.py")], env=env, check=True)
            result = json.loads((Path(tmp) / "status/forum-524.json").read_text(encoding="utf-8"))
            self.assertEqual(text, result["message"])
            self.assertEqual(23_000_002, result["revision"])
            self.assertEqual(7_200_000, result["expiresAt"] - result["startsAt"])

    def test_real_git_publication_preserves_feeds_and_rejects_rollback(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as tmp:
            tmp = Path(tmp)
            remote = tmp / "remote.git"
            self.git("init", "--bare", str(remote))
            update = tmp / "source/update"; status = tmp / "source/status"
            update.mkdir(parents=True); status.mkdir(parents=True)
            for name in ["manifest.json", "stable.json"]:
                (update / name).write_text('{"isReady":true,"versionCode":10}', encoding="utf-8")
            notice = publisher.make_notice("publish", "設備升級", "00:02:00", 10, 100)
            (status / "forum-524.json").write_text(json.dumps(notice), encoding="utf-8")
            helper = ROOT / ".github/scripts/publish-feed-directory.sh"

            def publish(source, target, success=True):
                result = subprocess.run([BASH, str(helper), remote.as_posix(), "main", source.as_posix(), target],
                                        cwd=ROOT, capture_output=True, text=True)
                if success: self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                else: self.assertNotEqual(0, result.returncode)

            publish(status, "status")  # Empty branch bootstrap.
            first = self.git("--git-dir", str(remote), "rev-parse", "main")
            publish(update, "update")
            self.assertEqual(notice, json.loads(self.git("--git-dir", str(remote), "show", "main:status/forum-524.json")))
            publish(status, "status")
            second = self.git("--git-dir", str(remote), "rev-parse", "main")
            self.git("--git-dir", str(remote), "merge-base", "--is-ancestor", first, second)
            notice["revision"] = 9
            (status / "forum-524.json").write_text(json.dumps(notice), encoding="utf-8")
            publish(status, "status", success=False)
            self.assertEqual(second, self.git("--git-dir", str(remote), "rev-parse", "main"))
            notice["revision"] = 10; notice["message"] = "same revision, changed text"
            (status / "forum-524.json").write_text(json.dumps(notice), encoding="utf-8")
            publish(status, "status", success=False)
            clone = tmp / "clone"
            self.git("clone", "--branch", "main", str(remote), str(clone))
            before = (clone / "status/forum-524.json").read_bytes()
            workflow = (ROOT / ".github/workflows/manual-stop-update.yml").read_text(encoding="utf-8")
            embedded = workflow.split('python3 - "$dir" <<\'PY\'\n', 1)[1].split("\n          PY", 1)[0]
            subprocess.run([sys.executable, "-", str(clone)], input=textwrap.dedent(embedded), text=True, check=True)
            self.assertEqual(before, (clone / "status/forum-524.json").read_bytes())
            for name in ["manifest.json", "stable.json"]:
                self.assertFalse(json.loads((clone / "update" / name).read_text())["isReady"])

    def test_workflows_preserve_notice_and_share_lock(self):
        for name in ["publish-server-notice", "sync-update-mirrors", "manual-ready", "release", "manual-stop-update"]:
            text = (ROOT / f".github/workflows/{name}.yml").read_text(encoding="utf-8")
            workflow = yaml.load(text, Loader=yaml.BaseLoader)
            self.assertIsInstance(workflow["jobs"], dict)
            for job in workflow["jobs"].values():
                for step in job.get("steps", []):
                    if "run" in step: self.assertIsInstance(step["run"], str)
            self.assertIn("group: mirror-publish-${{ github.repository }}", text)
            self.assertNotIn("push --force origin main", text)
            self.assertNotIn("push --force origin update-release", text)
            if name != "manual-stop-update": self.assertIn("publish-feed-directory.sh", text)
        subprocess.run([BASH, "-n", str(ROOT / ".github/scripts/publish-feed-directory.sh")], check=True)

    @staticmethod
    def git(*args):
        result = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
        if result.returncode: raise AssertionError(result.stdout + result.stderr)
        return result.stdout.strip()


if __name__ == "__main__": unittest.main(verbosity=2)
