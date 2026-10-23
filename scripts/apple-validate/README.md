# Apple validation profiles

Use `bash scripts/apple-validate/run.sh <profile>` on macOS, or the single
**Apple validation** workflow. See the [validation guide](../../dev-docs/apple-emulator-validation.md).

`native/run.sh` is the permanent Kotlin compilation baseline; it does not run tests.
Feature profiles use `<name>/run.sh` on their validation branch. They can also keep
their README, XCTest source, fixture generator and target-configuration script in
that directory. Write evidence to `$APPLE_VALIDATE_OUTPUT`, return a nonzero exit
code on failure, and preserve results when a test fails.

The workflow's `downloaded-content` option resolves the profile on the selected
`validation_ref`; its feature-specific implementation is intentionally absent
from production. Choose `custom` for any other profile folder. GitHub dropdowns
cannot be populated dynamically from the checked-out filesystem.
