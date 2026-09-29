import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("desktop_release", Path(__file__).with_name("desktop-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class DesktopReleaseTest(unittest.TestCase):
    def packages(self, directory):
        assets = []
        for os_name, abi, kind in sorted(release.EXPECTED):
            name = f"yamibo-stable-v0.0.8-{os_name}-{abi}.{kind}"
            data = {"msi": bytes.fromhex("d0cf11e0a1b11ae1"), "deb": b"!<arch>\n",
                    "rpm": bytes.fromhex("edabeedb") + b"1234", "dmg": b"koly" + bytes(508)}[kind]
            path = directory / name
            path.write_bytes(data)
            assets.append(dict(platform=os_name, abi=abi, type=kind, fileName=name,
                               sha256=release.digest(path), size=len(data)))
        (directory / "assets.json").write_text(json.dumps(assets), encoding="utf-8")
        return assets

    def test_complete_packages_produce_shared_tag_github_only_feed(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            self.packages(directory)
            assets = release.load_assets(directory)
            result = release.generate_manifest(dict(versionCode=9, versionName="0.0.8", channel="stable", isReady=False), "說明", assets)
            self.assertTrue(result["isReady"])
            self.assertEqual(result["releaseUrl"], f"https://github.com/{release.REPO}/releases/tag/9")
            self.assertEqual(result["releaseNotes"], "說明")
            self.assertEqual(len(result["assets"]), 5)
            for asset in result["assets"]:
                self.assertTrue(asset["url"].startswith(f"https://github.com/{release.REPO}/releases/download/9/"))

    def test_missing_duplicate_and_traversal_assets_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            assets = self.packages(directory)
            for broken in (assets[:-1], assets + [assets[0]], [dict(assets[0], fileName="../escape.msi")] + assets[1:]):
                (directory / "assets.json").write_text(json.dumps(broken), encoding="utf-8")
                with self.assertRaises(ValueError):
                    release.load_assets(directory)

    def test_modified_content_and_fake_package_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            assets = self.packages(directory)
            path = directory / assets[0]["fileName"]
            path.write_bytes(path.read_bytes() + b"changed")
            with self.assertRaises(ValueError):
                release.load_assets(directory)
            path.write_bytes(b"<html>error</html>")
            with self.assertRaises(ValueError):
                release.validate_package(path, assets[0]["type"])

    def test_version_regression_and_reuse_are_rejected_but_retry_is_noop(self):
        current = dict(versionCode=9, versionName="0.0.8")
        self.assertFalse(release.check_previous(None, current))
        self.assertTrue(release.check_previous(current, current))
        with self.assertRaises(ValueError):
            release.check_previous(dict(current, versionCode=10), current)
        with self.assertRaises(ValueError):
            release.check_previous(dict(current, versionName="different"), current)

    def test_remote_integrity_verification_rejects_wrong_digest_and_missing_file(self):
        assets = [dict(fileName="package.msi", size=100, sha256="a" * 64)]
        item = dict(name="package.msi", size=100, digest="sha256:" + "a" * 64)
        release.verify_remote(dict(assets=[item]), assets, "9")
        for items in ([], [dict(item, digest="sha256:" + "b" * 64)], [dict(item, size=99)]):
            with self.assertRaises(ValueError):
                release.verify_remote(dict(assets=items), assets, "9")

    def test_publish_checks_all_packages_before_external_calls(self):
        with patch.object(release, "metadata", return_value=({}, "notes")), \
                patch.object(release, "load_assets", side_effect=ValueError("incomplete")), \
                patch.object(release, "run") as run:
            with self.assertRaises(ValueError):
                release.publish()
            run.assert_not_called()

    def test_publish_orders_upload_verification_then_feed_and_preserves_published_retry(self):
        source = dict(versionCode=9, versionName="0.0.8", channel="stable")
        for already_published in (False, True):
            with self.subTest(already_published=already_published), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                assets = self.packages(directory)
                remote = dict(draft=False, assets=[dict(name=a["fileName"], size=a["size"], digest="sha256:" + a["sha256"]) for a in assets])
                commands = []
                feeds = []

                def fake_run(*args, cwd=None):
                    commands.append(args)
                    if args[:3] == ("git", "rev-parse", "HEAD"):
                        return "abc"
                    if args[:2] == ("gh", "api"):
                        return json.dumps(dict(sha="abc") if "/commits/" in args[2] else remote)
                    if args[:2] == ("git", "ls-remote"):
                        return "abc" if already_published else ""
                    if args[:2] == ("git", "clone"):
                        stable = Path(args[-1]) / "update/stable.json"
                        stable.parent.mkdir()
                        stable.write_text(json.dumps(release.generate_manifest(source, "notes", assets)), encoding="utf-8")
                    if args[:2] == ("git", "push"):
                        feeds.append(json.loads((Path(cwd) / "update/stable.json").read_text(encoding="utf-8")))
                    return ""

                with patch.object(release, "OUTPUT", directory), patch.object(release, "metadata", return_value=(source, "notes")), patch.object(release, "run", side_effect=fake_run):
                    release.publish()
                uploads = [c for c in commands if c[:3] == ("gh", "release", "upload")]
                if already_published:
                    self.assertEqual([], uploads)
                    self.assertEqual([], feeds)
                else:
                    self.assertEqual(6, len(uploads))  # Five packages and checksums.
                    self.assertEqual(5, len(feeds[0]["assets"]))
                    self.assertEqual(("git", "push", "origin", release.FEED), commands[-1])
                    self.assertTrue(feeds[0]["isReady"])

    def test_different_shared_tag_commit_prevents_upload(self):
        source = dict(versionCode=9, versionName="0.0.8", channel="stable")
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            self.packages(directory)
            with patch.object(release, "OUTPUT", directory), patch.object(release, "metadata", return_value=(source, "notes")), patch.object(release, "run", side_effect=["current", '{"sha":"other"}']) as run:
                with self.assertRaisesRegex(ValueError, "source commit"):
                    release.publish()
                self.assertEqual(2, run.call_count)


if __name__ == "__main__":
    unittest.main()
