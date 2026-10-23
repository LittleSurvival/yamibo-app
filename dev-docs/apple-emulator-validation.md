# macOS and Apple Simulator validation

## One workflow, branch-owned scripts

Use **Apple validation** (`.github/workflows/apple-validate.yml`) as the permanent
manual entry point. Put each scenario in
`.github/scripts/apple-validate/<full-branch-name>-<NN>.sh` on the branch that
contains its validation code. Start numbering at `01`; use `02`, `03`, and so on
for additional validation variants on the same branch. Do not create a workflow for
each feature. The downloaded-content experiment's old workflow remains on its
validation branch and must not be merged.

Production keeps the manual workflow and this guide. Branch-specific scripts,
XCTest targets, fixture generators, QA application
adapters, and test dependencies stay on validation branches. Never merge those
branches into `main`; deliver product commits through a separate production PR.
See [test isolation](test-isolation.md).

The dropdown contains script basenames and `custom`. For this experiment, the
script is
`stable-v0.0.9-fix-downloaded-content-loading-d76edbf-9be0117-validation-01.sh`.
GitHub choice
options are declared in YAML, so checking out a new folder cannot add a dropdown
item at runtime. For a new script, choose `custom` and enter its basename without
the `.sh` extension;
promote frequently used names into the existing dropdown when useful.

## Run from GitHub Actions

1. Commit and push a validation branch containing the product revision and the
   selected script and supporting files. Prefer an exact commit SHA for reruns.
2. Open **Actions → Apple validation → Run workflow**. Keep **Use workflow from**
   on `main` to use the reviewed orchestration. Set `validation_ref` to the branch
   or SHA containing the code and script. These are separate selections.
3. Select `validation_script`, or choose `custom` and set `custom_script`. The runner checks
   out the whole validation ref; it does not overlay tests onto a different app
   revision. Only select repository code you intend to execute.
4. Read the run's environment, build, and test logs. Download its
   `apple-validation-<run-id>-<attempt>` artifact, including failed runs.
5. Record the validation SHA, corresponding production SHA, run URL, individual
   results, and limitations in the production PR. Review product-source
   equivalence; a test branch name alone does not establish it.

The manual workflow must first exist on the default branch for its normal GitHub
UI entry point. A new workflow on an unmerged feature branch is not an available
production menu. This PR does not authorize merging it automatically. GitHub
documents the [manual trigger and choice input contract](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

After that entry point is available, the CLI equivalent is:

```bash
gh workflow run apple-validate.yml --ref main \
  -f validation_ref='<validation-commit-sha>' \
  -f validation_script=stable-v0.0.9-fix-downloaded-content-loading-d76edbf-9be0117-validation-01

gh workflow run apple-validate.yml --ref main \
  -f validation_ref='<validation-commit-sha>' \
  -f validation_script=custom -f custom_script='<full-branch-name>-02'
```

The workflow has read-only repository permissions, does not persist checkout
credentials, runs only on manual dispatch, and uploads evidence with `always()`.
Inputs reach shell commands through environment variables rather than being
inserted into shell source. Dependency caches include architecture, Xcode, and
Gradle/library inputs. A cache hit is not a passing test result. Concurrent
requests for the same scenario wait rather than cancel an active evidence run.

## Run on a local Mac

Use an Apple Silicon Mac with full Xcode, an installed iPhone Simulator runtime,
JDK 17, and the repository's Gradle wrapper. Windows cannot run Apple's iPhone
Simulator locally; a macOS Actions runner provides remote execution and artifacts.

```bash
xcode-select -p
xcodebuild -version
xcrun simctl list runtimes
java -version

# Check out the validation branch first. Use a fresh evidence directory.
export APPLE_VALIDATE_OUTPUT="$PWD/.tmp/qa/apple-validate/my-run"
mkdir -p "$APPLE_VALIDATE_OUTPUT"
set -o pipefail
validation_script='<full-branch-name>-01'
bash ".github/scripts/apple-validate/$validation_script.sh" \
  2>&1 | tee "$APPLE_VALIDATE_OUTPUT/validation.log"
```

Use a fresh output directory for each attempt. The common workflow writes
`environment.log`, `validation.log`, and `exit-code.txt`. It records the actual
commit and dirty state; use a clean checkout for reproducible evidence. The
script must state whether it compiles only, runs unit tests, or exercises actual
UI cases. An installed SDK or a successful Kotlin compile does not establish that the iOS
App, XCTest bundle, or a reader flow works.

## Script naming and contract

There is one clearly named shell entry for each validation variant. Use the full
branch name from the repository's [branch naming rules](branch-naming.md), then
a hyphen and a zero-padded sequence number. There is no generic `run.sh` and no
separate shell dispatcher. The shared Action selects the file directly.

Keep supporting files in a named sibling directory, referenced by the shell entry:

```text
.github/scripts/apple-validate/
  <full-branch-name>-01.sh   # shell entry, invoked with bash
  downloaded-content/
    README.md              # prerequisites, fixtures, assertions, expected cases
    validate.py            # optional orchestration helper
    configure_tests.rb     # only if an isolated Xcode target is needed
    MyScenarioUITests.swift
    generate_fixtures.py
```

Use `set -euo pipefail`, run from the repository root, and write portable evidence
to the absolute `$APPLE_VALIDATE_OUTPUT` directory. Preserve tool exit codes when
piping through `tee`. Return nonzero for failed assertions; never convert a test
failure into a successful run merely to upload screenshots. Keep derived data
outside the evidence directory, or remove it before uploading.

Never commit or force-add `.tmp/`, even on a validation branch. Commit the scripts
and fixture generators in the directory above; leave generated fixtures, XML,
screenshots, traces and run reports in ignored output directories. Publish results
through Actions artifacts and record the essential counts and run links in the PR.

Provide clear log groups for preparation, native checks, framework preparation,
App/XCTest build, installation/fixtures, UI execution, and cleanup. Set finite
timeouts; the common workflow allows 50 minutes for the script and 60 for the
job. Record cold-build timing before changing limits. Shut down/delete only
Simulators created by the script, using the exact recorded UDID; do not shut
down all devices on a developer's Mac.

If native unit tests are part of the script, copy their XML results into the
output directory and report executed/passed/failed/skipped counts. Keep `.xcresult`
bundles directly under the output directory. The common workflow exports their
test summaries and attachments even after a failed script. Local runs can use:

```bash
xcrun xcresulttool get test-results summary --path "$RESULT_BUNDLE"
xcrun xcresulttool export attachments --path "$RESULT_BUNDLE" \
  --output-path "$ATTACHMENTS_DIRECTORY"
```

## Recommended iPhone UI sequence

1. Print architecture, Xcode/build version, SDK/runtime, selected scheme and
   destination. Select an available iPhone runtime/UDID explicitly. Do not assume
   a fixed device name survives runner image updates.
2. Compile required native targets and run any unit regressions. Finish heavy
   Kotlin framework preparation before booting the UI Simulator to reduce memory
   contention. Use `--max-workers=2` initially and stop the Gradle daemon afterward.
3. Build an independent QA app and a UI-test target. Give the XCTest target its
   own `PRODUCT_NAME`, `PRODUCT_MODULE_NAME`, bundle identifier and test scheme;
   do not inherit the App's Swift module name.
4. Boot the chosen Simulator and wait for `simctl bootstatus <udid> -b`. Install
   the independent app. Seed fixtures into its current data container and verify
   expected manifests/files exist before launching a test.
5. Configure the QA app through its normal settings API before constructing the
   storage repository. Do not write a running Simulator's preferences plist from
   the host and assume CFPreferences has reloaded it. Resolve app-private paths
   inside the current app container. Keep this wiring on the validation branch.
6. Run serial XCTest flows with an explicit destination and result bundle. Verify
   actual content, navigation, and foreground state; a launched process or an
   empty-state label is not success. Handle observed first-launch dialogs and
   disable unrelated update checks in the QA configuration.
7. Export evidence, inspect relevant screenshots, summarize each test, and clean
   up owned resources even if assertions fail.

For this repository's direct Kotlin/Xcode integration, the normal Xcode build
phase calls `:composeApp:embedAndSignAppleFrameworkForXcode`. If a script prepares
that framework separately, preserve Xcode's environment contract. For an arm64
Simulator Debug build, the tested shape is:

```bash
export SDK_NAME="iphonesimulator$(xcrun --sdk iphonesimulator --show-sdk-version)"
export PLATFORM_NAME=iphonesimulator
export CONFIGURATION=Debug
export ARCHS=arm64
export BUILT_PRODUCTS_DIR="$DERIVED_DATA/Build/Products/Debug-iphonesimulator"
export TARGET_BUILD_DIR="$BUILT_PRODUCTS_DIR"
export FRAMEWORKS_FOLDER_PATH=yamibo_app.app/Frameworks
export UNLOCALIZED_RESOURCES_FOLDER_PATH=yamibo_app.app
export ENABLE_USER_SCRIPT_SANDBOXING=NO
mkdir -p "$BUILT_PRODUCTS_DIR"
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode --console=plain --max-workers=2
./gradlew --stop
```

`PLATFORM_NAME` is also needed by Compose's iOS resources task. Keep the derived
data path consistent with the subsequent `xcodebuild` invocation. Only after a
successful separate framework build, set
`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` for Xcode to skip the duplicate Gradle
phase. Do not skip Kotlin compilation without preparing its matching framework
and resources. See Kotlin's [direct integration contract](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).

Use `build-for-testing` first, then `test-without-building` with the same scheme,
derived data and destination. Pass `CODE_SIGNING_ALLOWED=NO` for Simulator-only
QA and `-parallel-testing-enabled NO` for sequential UI scenarios. Save results
with `-resultBundlePath`. Apple documents [result bundles](https://help.apple.com/xcode/mac/current/en.lproj/devc38fc7392.html)
and the current [xcresulttool commands](https://developer.apple.com/documentation/xcode-release-notes/xcode-16_3-release-notes).

## Acceptance and troubleshooting

| Observation | Interpretation / next check |
| --- | --- |
| Workflow step is running | Only orchestration progress; individual tests may not have started. |
| Kotlin compile succeeds | Native source compilation; App linking and UI acceptance remain separate. |
| `Executed 6 tests, with 6 failures` | Six failures, regardless of successful build or artifact upload. |
| Reader requests a synthetic thread from the server | Check selected download root, current container, seeded files and local validation before treating it as a performance result. |
| Management tab cannot be found | Inspect the accessibility tree and first-launch/update dialogs before changing labels. |
| Duplicate Swift module / type-check timeout | Give XCTest a distinct module; simplify expensive test-only Swift expressions. |
| Missing Compose resources / Xcode environment | Compare the actual environment with the installed plugin's task contract; include `PLATFORM_NAME`. |
| Long silent framework phase | Inspect the live log and cold-build duration; keep finite limits and avoid booting the UI Simulator during linking. |

Report per-case assertions and inspect rendered content. For local-image tests,
prove the fixture image rendered, not merely that a page counter appeared. For
management tests, prove the intended content group expanded. Describe fixture
size, network conditions, build type and hardware. Simulator timings are not
physical-device performance, and functional UI passes do not establish a jank
budget. Keep performance claims tied to the measured operation and sample.

When replacing an old branch-specific workflow, let its active run finish,
preserve its results, then disable its future runs. Retain the old YAML and run
links as historical evidence on that branch; do not merge the YAML or delete
failed evidence to make the Actions list appear cleaner.
