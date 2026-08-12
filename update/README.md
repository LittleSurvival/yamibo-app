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

The source manifest must not contain `releaseUrl`. The ready manifest consumed by app clients is generated temporarily by the workflow only after APK build, signing, and upload complete. It is not committed back to `main`.

## Release Android APK Workflow

Workflow file:

- `.github/workflows/release.yml`

Trigger:

- Run `Release Android APK` manually from the GitHub Actions page.
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
3. Select `Release Android APK`.
4. Click `Run workflow`.

Workflow steps:

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

- Normal release: run `Release Android APK` manually from GitHub Actions.
- Update feed did not sync correctly after a release: run `Sync Update Folder To Mirrors` manually with `use_latest_release_asset=true`.
- APK assets already exist and only the public feeds should become ready: run `Manual Ready Update Manifests`.
- A released APK has a problem and app-side update prompts should be paused: run `Sync Update Folder To Mirrors` manually with `use_latest_release_asset=false`.

## Release Safety Rules

- Do not manually set source `manifest.json` or `stable.json` to `isReady=true`.
- Do not manually add APK assets to the source manifest.
- Do not manually add `releaseUrl` to the source manifest.
- `isReady=true` should exist only in the published update folder on the GitHub `update-release` branch and the Gitee/Gitea mirror repositories.
- The app client shows an available update and downloads the APK only when it reads `isReady=true` and the remote version is newer.
