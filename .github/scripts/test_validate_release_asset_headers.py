import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("validate-release-asset-headers.py")
SPEC = importlib.util.spec_from_file_location("validate_release_asset_headers", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ValidateReleaseAssetHeadersTest(unittest.TestCase):
    def write_headers(self, content_length="1234", content_type="application/zip"):
        temporary = tempfile.NamedTemporaryFile(mode="w", encoding="ascii", delete=False)
        temporary.write(
            "HTTP/1.1 302 Found\r\n"
            "Location: https://download.example/app.apk\r\n\r\n"
            "HTTP/1.1 200 OK\r\n"
            f"Content-Type: {content_type}\r\n"
            f"Content-Length: {content_length}\r\n"
            'Content-Disposition: attachment; filename="app.apk"\r\n\r\n'
        )
        temporary.close()
        path = Path(temporary.name)
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    def test_accepts_final_redirect_response(self):
        MODULE.validate(self.write_headers(), "app.apk", 1234)

    def test_rejects_size_mismatch(self):
        with self.assertRaisesRegex(ValueError, "Asset size mismatch"):
            MODULE.validate(self.write_headers(content_length="1233"), "app.apk", 1234)

    def test_rejects_unexpected_content_type(self):
        with self.assertRaisesRegex(ValueError, "Unexpected APK Content-Type"):
            MODULE.validate(
                self.write_headers(content_type="text/html"), "app.apk", 1234
            )


if __name__ == "__main__":
    unittest.main()
