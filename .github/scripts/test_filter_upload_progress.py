import queue
from pathlib import Path
import subprocess
import sys
import threading
import unittest


SCRIPT = Path(__file__).with_name("filter-upload-progress.py")


class FilterUploadProgressTest(unittest.TestCase):
    def test_reports_progress_before_input_stream_closes(self):
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "Test upload"],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self.assertIsNotNone(process.stdin)
        self.assertIsNotNone(process.stderr)
        reported = queue.Queue()
        reader = threading.Thread(
            target=lambda: reported.put(process.stderr.readline()), daemon=True
        )
        reader.start()

        process.stdin.write("progress 1.0%\r")
        process.stdin.flush()
        self.assertEqual("Test upload:   1.0%\n", reported.get(timeout=1.0))

        process.stdin.close()
        self.assertEqual(0, process.wait(timeout=2.0))
        process.stderr.close()

    def test_suppresses_duplicate_completion_and_reports_retry_reset(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "Test upload"],
            input="progress 10%\rprogress 20%\rprogress 5%\rprogress 100%\rprogress 100%\r",
            text=True,
            capture_output=True,
            check=True,
        )

        self.assertIn("Test upload: retry progress reset", result.stderr)
        self.assertEqual(1, result.stderr.count("Test upload: 100.0%"))


if __name__ == "__main__":
    unittest.main()
