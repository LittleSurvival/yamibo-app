# Repository Instructions

## OpenSpec isolation

- Never merge any file or change under `openspec/` into the `main` branch.
- Before merging into `main`, run `git diff --name-only main...HEAD -- openspec/` and require empty output.
- If a feature branch contains both implementation and `openspec/` changes, exclude the `openspec/` changes from the commits or history merged into `main`.

## Branch naming isolation

- Before creating any branch, read [Branch Naming Rule Document](dev-docs/branch-naming.md).

## Main branch integration

- Integrate changes from any other branch into `main` only through a pull request; do not merge locally or push directly to `main` unless the user explicitly authorizes that specific exception.
- Treat the PR as a checkpoint. Its description must record the problem and root cause, implementation and design decisions, user-visible behavior, verification evidence, and known risks, limits, or rollback notes.
- Before opening or merging the PR, enforce the OpenSpec isolation guard above. Merge only after required checks pass, and prefer a merge commit to preserve branch lineage unless the user requests another strategy.

## Git identity guard

- Before every commit, run `python .github/scripts/validate_git_name.py` from the project root.
- Continue with the commit only when the validator exits successfully. Do not duplicate its identity prompt; its repository-local saved choice is authoritative.
- The validator script is located at [`.github/scripts/validate_git_name.py`](.github/scripts/validate_git_name.py).

## Architecture & development conventions

### i18n (compile-time generated)

- All new UI text must use `i18n("繁體中文 source text")`; never hard-code user-visible strings.
- `i18n/i18n.properties` sets `failOnMissingTerm=true`: every `i18n(...)` source text MUST have a matching row in `i18n/glossary.csv` (columns `source,en,zh-tw,zh-cn`), otherwise `generateI18nResources` fails the build.
- Add stable translations to `i18n/glossary.csv`; verify with `./gradlew checkI18n`.

### Module boundaries & visibility (shared vs composeApp)

- `internal` declarations in `shared` are NOT visible to `composeApp`. Any type used across the module boundary (e.g. `PanCloudApiClient`, `PanCloudAccountRepository`, `PanCloudBackupStorageProvider`, and their DTOs) must be `public`.
- A `public` function signature must not expose an `internal` type ("public function exposes internal type"). Keep AppSync engine interfaces (`AppSyncJournalRemote`, load/publish result types, `LoadedAppSyncJournal`, etc.) `internal` and expose them only through `public` methods such as `AppSyncService.attachPanCloud(...)`.
- `internal` types may be used inside a `public` function body as long as they never appear in its signature.

### Dependency injection (CompositionLocal)

- Repositories are exposed via `compositionLocalOf` in `composeApp/.../AppRepository.kt` and provided in `MainActivity` / `MainViewController` inside `remember { ... }`.
- Break construction cycles with deferred injection rather than reordering: `AppSyncService` needs `PanCloudAccountRepository`, which needs `AppSettingsRepository`, which derives from `AppSyncService` — resolve via `AppSyncService.attachPanCloud(...)` called after construction.

### Backup & sync (cloud drive)

- Snapshot backup: `BackupRepository` + `BackupStorageProvider`. Both the local backend (`AndroidBackupStorageProvider` / `IOSBackupStorageProvider`) and the cloud backend (`PanCloudBackupStorageProvider`) implement the same interface; `BackupRepositoryImpl` is storage-agnostic.
- Incremental sync: AppSync engine (`engine/`) depends only on `AppSyncJournalRemote`. The forum backend is `YamiboAppSyncJournalRemote`, the cloud backend is `PanCloudJournalRemote`, selected at runtime by `SwitchableAppSyncJournalRemote` via the `appsync.backend` setting.
- Cloud drive API: see root `API.md` — JWT auth (access 15min / refresh 7d, 401 auto-refresh+retry), unified `{success,data,message,error}`, upload ≤10MB direct / >10MB chunked, folder create idempotent via 409.
- Cloud sync file mapping: `index.json`, `journal-<replicaKey>.json`, `checkpoint-<id>.json`. Single-writer journals (each device writes only its own file) avoid lost updates, since the drive has no ETag/CAS. Switching backend resets the installation to Unbound (re-Seed/Join).

### Settings & credentials

- Settings use the `SettingsRegistry` DSL (`enumSetting`/`stringSetting`/`boolSetting`/`intSetting`); the storage key is `"$prefix.$propertyName.lowercase()"`.
- Sensitive/device-local settings (e.g. cloud drive `refresh_token`) must be added to the `BackupRepositoryImpl.shouldSkipSetting` blacklist so they are excluded from backups.
- Cloud `access_token` lives only in memory (`PanCloudApiClient`); the `refresh_token` is persisted in `SettingsStore`.

### Testing

- `commonTest` uses `ktor-client-mock` (`MockEngine`) + `kotlin.test` + `runBlocking`, with an in-memory `SettingsStore`.
- For cloud API tests the base URL includes `/api`, so assert `request.url.encodedPath` with the `/api` prefix (e.g. `/api/auth/register`, not `/auth/register`).
- Read a binary request body in `MockEngine` via `request.body` (do not cast to `ByteArrayContent`); respond to downloads with `respond(byteArray)` — `respond(String)` corrupts binary.

### Build & verification

- `./gradlew :shared:compileDebugKotlinAndroid --console=plain`
- `./gradlew :composeApp:compileDebugKotlinAndroid --console=plain`
- `./gradlew :shared:testDebugUnitTest --tests "<pattern>" --console=plain`
- `./gradlew checkI18n --console=plain`
