# Update Workflow Guide

This directory stores the source update manifest, the published update feed contract, and per-release changelogs for the app updater.

## Files

- `manifest.json`: the manually maintained source manifest. Edit this file only.
- `stable.json`: generated from `manifest.json` by `./gradlew syncStableManifest` to avoid manual sync mistakes.
- `changelogs/{versionCode}.changelog`: the GitHub Release body for a specific release, for example `changelogs/1.changelog`.

`update/changelogs` is the single source of truth. During the app build, Gradle stages only
`1.changelog` as the fallback and the current version's changelog under generated Compose resources;
generated copies must not be committed under `composeApp/src`.

The source repository versions of `manifest.json` and `stable.json` must always keep:

```json
{
  "isReady": false,
  "assets": []
}
```

The source manifest must not contain `releaseUrl`. Ready manifests are generated from uploaded packages, not committed back to `main`. Desktop uses the same source version and changelog: no extra manually maintained JSON or signing credentials.

## Release Android and Desktop Workflow

Workflow file:

- `.github/workflows/release.yml`

Trigger:

- Run `Release Android and Desktop` manually from the GitHub Actions page.
- The workflow creates the tag named after `update/manifest.json` `versionCode`.
- If that tag already exists, it must point to the current commit.

Manual preparation before release:

1. Update `yamiboAppVersionCode` and `yamiboAppVersionName` in `composeApp/build.gradle.kts`.
2. Update `update/manifest.json`, keeping `isReady=false`, `assets=[]`, and no `releaseUrl`.
3. Add or update `update/changelogs/{versionCode}.changelog`.
4. Run locally:

```powershell
.\gradlew syncStableManifest validateUpdateManifest --console=plain
```

Trigger the release:

1. Push the prepared release commit to the source repository.
2. Open GitHub Actions.
3. Select `Release Android and Desktop`.
4. Click `Run workflow`.

The GitHub APK job creates the shared release first. The desktop workflow and Android mirror job then run independently; a mirror failure does not block desktop publication. The Android path remains:

1. Check out the selected source branch.
2. Validate the manifest, app version, and matching changelog.
3. Create and push the `versionCode` release tag, or verify that an existing tag points to the current commit.
4. Run `syncStableManifest validateUpdateManifest`, requiring the source manifest to remain `isReady=false`.
5. Build the release APK.
6. Zipalign, sign, and verify the APK with `mine.keystore`.
7. Create or update the GitHub Release.
8. Upload the APK asset.
9. Calculate the APK `sha256` and `size`.
10. Upload the same APK to the Gitee and Gitea release assets.
11. Generate target-specific published update folders in runner temp:
   - `isReady=true`
   - `releaseUrl`
   - APK asset `url`, `sha256`, and `size`
   - `releaseNotes` and `changelogs/{versionCode}.changelog` from the matching source changelog
   - identical `manifest.json` and `stable.json`
12. Run `validatePublishedUpdateManifest` for each target folder.
13. Force push the GitHub-targeted `update` folder to the GitHub `update-release` branch.
14. Force push the Gitee-targeted and Gitea-targeted `update` folders to their mirror repositories.

Important asset URL rule:

- GitHub `update-release` manifests must use the GitHub Release APK URL.
- Gitee mirror manifests must use the Gitee Release APK URL.
- Gitea mirror manifests must use the Gitea Release APK URL.
- Do not publish mirror manifests that point back to GitHub APK assets.

Client update source order:

1. GitHub `update-release` branch: `update/stable.json`
2. Gitee mirror repo: `update/stable.json`
3. Gitea mirror repo: `update/stable.json`

### Desktop packages and update feed

The reusable `.github/workflows/release-desktop.yml` builds Windows x64 MSI, macOS Intel/Apple Silicon DMG, and Linux x64 DEB/RPM on matching runners. Java and Chromium are bundled; users do not install Java or download Chromium separately. Package icons derive from the existing app icon. The installer version is automatically `1.0.{versionCode}`; the app still displays `versionName`. Windows uses a stable upgrade UUID and per-user installation.

Desktop assets are attached to the **same numeric tag and GitHub Release as Android**. Only APKs go to mirrors. After every desktop package passes validation and GitHub confirms its size/hash, `.github/scripts/desktop-release.py` publishes generated `update/manifest.json`, `update/stable.json` and the shared changelog to `desktop-update-release`. That branch is created and maintained automatically. Its assets include `platform`, `abi`, `type`, `fileName`, GitHub `url`, `sha256` and `size`; its `releaseUrl` is the shared release. `desktop-SHA256SUMS.txt` is attached to that release too. Neither the source JSON nor version/changelog preparation gains another manual step.

Incomplete matrix builds leave the desktop feed unchanged. Publication rejects older versions or a shared tag pointing to another source commit. A retry of an already-published desktop version verifies and preserves its existing assets rather than replacing them with a potentially non-reproducible rebuild. Pending/unpublished assets can be replaced during retries. Feed pushes are non-force and serialized; the Android feed cannot overwrite the separate desktop feed.

Desktop checks only GitHub, with a 20-second manifest timeout. Automatic check/download failures prompt for manual download with a copyable GitHub releases URL; the update page also always provides these controls, not a mirror fallback. Download connections and idle reads are bounded, with a 30-minute total download limit and cancellation. Size/SHA-256 must match before the selected destination is replaced. Installation asks for confirmation, stops background work/browser, releases the app lock, opens the installer and exits. This is **installer handoff**, not silent installation or automatic restart. Data stays outside the installation directory.

User installation/update:

- Windows: download the `windows-x86_64.msi`, open it and follow the installer; updating uses the same installer path. Reopen the app afterward.
- macOS: select `macos-arm64.dmg` (Apple Silicon) or `macos-x86_64.dmg` (Intel), open it and copy/replace Yamibo in Applications after quitting the app; relaunch afterward.
- Linux: use `linux-x86_64.deb` for Debian/Ubuntu or `.rpm` for RPM-based distributions, through the system package manager. If no graphical handler is available, install the downloaded file manually. Other distributions/architectures are not supplied by this matrix.

These desktop packages are **unsigned and not notarized**. Windows/macOS may warn or block opening them; packaging does not guarantee friction-free installation. No instructions to disable OS security are part of this flow. Installation may still require OS approval or administrator authorization. Keep Android signing secrets unchanged; no desktop certificate secrets are needed.

For an optional CI packaging check, dispatch `Desktop packages` with `publish=false` (default): it uploads workflow artifacts without changing any tag, Release or feed. Normal releases invoke publishing automatically; `publish=true` on a desktop-only retry requires the existing shared tag/Release at the same source commit. This optional diagnostic run is not an extra normal release step.

Local Windows checks:

```powershell
.\gradlew.bat :composeApp:createDistributable :composeApp:packageMsi --console=plain
python .github/scripts/desktop-release.py smoke
python .github/scripts/desktop-release.py collect --platform windows --abi x86_64
python -m unittest discover -s .github/scripts -p test_desktop_release.py
```

The smoke command starts only an isolated packaged-runtime check (SQLite, icon, bundled Chromium), not a signed-in app session. It does not prove installer upgrades, native UI behavior or successful GitHub publication. macOS/Linux runtime acceptance and the first actual multi-platform CI/release run must be tracked separately from Windows results.

Implementation verification (2026-09-30): Windows MSI generation and packaged SQLite/icon/Chromium smoke passed. Shared tests: desktop 312 (2 skipped), Android 473; Compose tests: desktop 231 (1 skipped), Android 225; zero failures/errors. Desktop publication helper: 8 tests passed; existing manifest/header helpers: 5 tests passed. The header-test fixture explicitly disables Windows newline translation, avoiding doubled CRs without changing its production validator. Actionlint 1.7.12 passed for both release workflows (shellcheck/pyflakes not installed). No live release/feed write, installed-version upgrade/uninstall, native updater dialog acceptance, macOS/Linux execution or multi-runner CI execution was performed. The runner matrix follows [GitHub's supported labels](https://docs.github.com/en/actions/reference/runners/github-hosted-runners); configuration validation is not runtime acceptance.

## Sync Update Folder To Mirrors Workflow

Workflow file:

- `.github/workflows/sync-update-mirrors.yml`

Trigger:

- Run `Sync Update Folder To Mirrors` manually from the GitHub Actions page.

Input:

- `use_latest_release_asset=false`: default. Syncs the source `update/manifest.json` and `stable.json`, meaning `isReady=false`. Use this to pause public updates or reset the public feed to a not-ready state.
- `use_latest_release_asset=true`: reads the GitHub Release APK matching the current `manifest.versionCode`, regenerates an `isReady=true` published manifest, and syncs it to the GitHub `update-release` branch plus Gitee and Gitea mirrors.

Default sync flow:

1. Check out the source repo.
2. Run `syncStableManifest validateUpdateManifest`.
3. If `stable.json` was regenerated, commit it back to the source repo.
4. Copy source `update/manifest.json` and `stable.json` into runner temp.
5. Force push the temp `update` folder to the GitHub `update-release` branch.
6. Force push the temp `update` folder to the Gitee and Gitea mirror repositories.

`use_latest_release_asset=true` flow:

1. Check out the source repo.
2. Run `syncStableManifest validateUpdateManifest`.
3. Read `manifest.versionCode`, `versionName`, and `channel`.
4. Download the matching APK from GitHub Releases:

```text
yamibo-{channel}-v{versionName}.apk
```

5. Calculate the APK `sha256` and `size`.
6. Upload/copy that APK into Gitee and Gitea release assets.
7. Generate target-specific `isReady=true` published manifests:
   - GitHub manifest uses the GitHub release asset URL.
   - Gitee manifest uses the Gitee release asset URL.
   - Gitea manifest uses the Gitea release asset URL.
8. Run `validatePublishedUpdateManifest` for each ready folder.
9. Force push the ready GitHub `update` folder to the GitHub `update-release` branch.
10. Force push the ready Gitee/Gitea `update` folders to their mirror repositories.

## Manual Ready Update Manifests Workflow

Workflow file:

- `.github/workflows/manual-ready.yml`

Trigger:

- Run `Manual Ready Update Manifests` manually from the GitHub Actions page.

Purpose:

- Use this when APK releases already exist but the published update feeds need to be marked ready.
- The workflow reads the current `update/manifest.json` version, downloads the matching GitHub Release APK, uploads/copies it into Gitee and Gitea release assets, then publishes `isReady=true` manifests to GitHub, Gitee, and Gitea.
- Like the release and sync workflows, mirror manifests must point to their own mirror release asset URL rather than GitHub's APK URL.

## Which Workflow To Use

- Normal release: run `Release Android and Desktop` manually from GitHub Actions.
- Update feed did not sync correctly after a release: run `Sync Update Folder To Mirrors` manually with `use_latest_release_asset=true`.
- APK assets already exist and only the public feeds should become ready: run `Manual Ready Update Manifests`.
- A released APK has a problem and app-side update prompts should be paused: run `Sync Update Folder To Mirrors` manually with `use_latest_release_asset=false`.

## Release Safety Rules

- Do not manually set source `manifest.json` or `stable.json` to `isReady=true`.
- Do not manually add APK assets to the source manifest.
- Do not manually add `releaseUrl` to the source manifest.
- `isReady=true` should exist only in the published update folder on the GitHub `update-release` branch and the Gitee/Gitea mirror repositories.
- The app client shows an available update and downloads the APK only when it reads `isReady=true` and the remote version is newer.
