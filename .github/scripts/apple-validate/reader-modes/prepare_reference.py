"""Build the unchanged tag-12 reader with the same QA-only iPhone runner and identifier."""
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

root=Path.cwd()
sha="6d07ed8ac348a2f06a0bcbafacb2b99ad3086e94"
sandbox=Path(os.environ["RUNNER_TEMP"])/"reader-baseline-12"
sandbox.mkdir(exist_ok=False)
subprocess.run(["git","fetch","--depth=1","https://github.com/LittleSurvival/yamibo-app.git",sha],check=True,timeout=120)
assert subprocess.check_output(["git","rev-parse","FETCH_HEAD"],text=True).strip()==sha
packed=subprocess.check_output(["git","archive",sha],timeout=120)
with tarfile.open(fileobj=io.BytesIO(packed)) as archive:
    archive.extractall(sandbox,filter="data")
qa=Path(".github/scripts/apple-validate/reader-modes")
shutil.copytree(root/qa,sandbox/qa,dirs_exist_ok=True)
identifier=Path("iosApp/Configuration/AppIdentifiers.xcconfig")
assert "com.yamibo.bbs" in (sandbox/identifier).read_text()
shutil.copy2(root/identifier,sandbox/identifier)
(Path(os.environ["APPLE_VALIDATE_OUTPUT"])/"reference-commit.txt").write_text(sha+"\n")
