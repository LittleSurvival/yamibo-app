# Apple validation scripts

Use the single **Apple validation** workflow, or run a selected script on macOS.
See the [validation guide](../../../dev-docs/apple-emulator-validation.md).

Name each script `<full-branch-name>-<NN>.sh`, starting at `01`. Keep supporting
files in a named sibling directory referenced by that entry. Scripts and their XCTest
sources, fixtures, QA adapters and dependencies stay on the validation branch.
This production directory contains the convention only; it has no generic
`run.sh` or feature-specific executable.

Select the script basename in the workflow menu and a `validation_ref` containing
it. Choose `custom` to enter a new basename without editing the menu. GitHub
dropdowns are static. Write evidence to `$APPLE_VALIDATE_OUTPUT`, return nonzero
on failure, and preserve results when a test fails.
