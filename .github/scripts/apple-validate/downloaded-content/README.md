# Downloaded-content validation (branch only)

Entry: `../stable-v0.0.9-fix-downloaded-content-loading-d76edbf-9be0117-validation-01.sh`.
Select that basename in **Apple validation** and a validation ref containing it.
On a local Apple Silicon Mac, invoke the same shell file with bash.

This script requires this branch's test sources, independent QA bundle ID,
MainViewController startup adapter and test-only Gradle wiring. Never merge this
validation branch. The shell entry follows `<full-branch-name>-<NN>.sh`; this
support directory contains its orchestration and fixtures, with no `run.sh`.

It runs 40 native regressions, builds the App/framework and an isolated XCTest
bundle, creates its own iPhone Simulator, installs the independent App, and seeds
six real manifest directories. Fixtures contain a 150 KB novel and 300 local cyan
images per manga chapter. Startup selects the current container's fixture root
through the App's settings API, checks a real page file exists, and disables
unrelated automatic launch-update checks.

Six serial UI cases cover novel, ordinary thread, thread manga, tag manga, RSS
manga and content management. Assertions require actual local text/image content
and scrolling/navigation. Inspect the management screenshot to verify expansion.
The script checks native counts and the XCTest summary, preserves XML and the
`.xcresult`, and deletes only its owned Simulator. The common workflow exports
attachments even on failure. No device or frame-time acceptance is implied.

The historical branch-only workflow is retained as an experiment record and a
temporary migration adapter. Future runs use the single permanent manual entry.
