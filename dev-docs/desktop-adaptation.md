# Desktop adaptation (in progress)

Branch checkpoint (2026-09-29): the user now authorizes grouped commits and pushing to the existing desktop-parity branch, superseding the earlier no-commit/no-push instruction for this checkpoint only. Standard regression rerun passed: shared desktop 308 cases (2 optional native skips), shared Android 469, Compose desktop 229 (1 optional native-browser skip), Compose Android 225; zero failures/errors in all four suites. This also compiles the latest browser regression assertions, but the opt-in native-browser test and live CF sign-in flow were not rerun. Existing user-reported live write acceptance is complete; sleep/wake testing is waived/unverified; macOS/Linux runtime and the documented cleanup remain unverified/unresolved. OpenSpec, generated build outputs and QA temporary data are excluded from commits. Release workflows are unchanged; no merge or release is authorized.

Sign-in follow-up (2026-09-29): user reports item 1 (their live cloud/forum write acceptance) complete. Separately, they report CF succeeds in WebView but the returning sign-in request displays an uncleared-CF snackbar. Inspection of the actual Maven Central 1.1.28 source JAR confirms SignFactory uses the Android Chrome 120 user agent; the desktop ordinary sign-in browser used its native Chromium user agent. Desktop sign-page navigation now aligns with that API user agent, without changing other initial destinations or explicit WAF-session overrides. The failure message and existing glossary now distinguish an HTTP request being blocked from browser verification being incomplete. Existing policy/native-browser tests were extended (no new files), including HTTP-header and navigator.userAgent assertions. First regression run stopped on the missing glossary entry, now supplied; rerun and live CF acceptance remain pending. Computer Use encountered concurrent user input while trying to exit the old app, so input stopped and the user was asked to exit with Ctrl+Shift+Q before rebuilding/restarting. No claim is made that the live report is fully resolved; no commit/push/shutdown performed.

User acceptance scope update (2026-09-29): live cloud/forum write acceptance is handed to the user; no result is claimed yet. The user explicitly waived sleep/wake testing, which remains unverified rather than passed. Cleanup of the three previously listed isolated QA directories was explicitly approved and retried with exact-path checks, but execution policy rejected the command again; all remain pending, with no alternate deletion route attempted. App launch is requested for the user's checks; leave it running and do not shut down during this handoff. No commit or push is authorized.

Handoff audit (2026-09-28): the 97 documented tree leaves were checked for existence and compared against `git diff --no-renames --diff-filter=A main` plus non-ignored untracked files, excluding the documented OpenSpec scope; sets match exactly. Diff whitespace, OpenSpec integration guard and release-workflow comparison are empty. Published API 1.1.28 remains configured, without local-source or mavenLocal substitution. Live cloud/forum writes still await the user's test scope choice; real sleep/wake needs a human to wake/unlock the machine. Policy-denied temporary-directory cleanup remains unresolved and must not be bypassed. These are not accepted passes, and shutdown remains conditional on completing the agreed verification.

Native hidden-window background acceptance (2026-09-28): launched the normal DesktopMainKt entry with only LOCALAPPDATA redirected to `.tmp/qa/background-window-20260928`, empty synthetic data and a local backup deadline. Before the deadline there was no backup. Clicking the window X removed it from the native window list; process 44404 remained alive. At the deadline, while the window list still contained no Yamibo window, one 7,970-byte schema-1 backup appeared with 53 settings and zero notes. This verifies a real scheduled backup after close-to-tray, not just an isolated worker. The original user settings SHA-256 remained 9952DC57531C70BF1B99856AD402205ABB02C042C9CEB7864B57E76D725D4BBE. The automation could not target the hidden window to exit normally, so only the verified isolated PID was terminated; Gradle exit -1 was intentional cleanup, not an app crash. No normal-exit/tray-reopen claim is made from this case (earlier user acceptance covers tray reopening). The temporary Gradle initializer was removed. Recursive cleanup of the isolated data directory was rejected by execution policy and was not bypassed; that directory remains, containing only synthetic test data. Sleep/wake, visible notification delivery and live cloud/forum mutations remain separate acceptance.

Native opt-in regression (2026-09-28): after normal app exit, Compose desktop ran with desktopNativeBrowserTest=true: 228 cases, zero failures/errors/skips. Native Chromium loaded local synthetic Chinese content and isolated HttpOnly cookies between independent browser contexts. Shared desktop ran with desktopNativeKeyringTest=true: 308 cases, zero failures/errors, one optional font skip (the explicit font case has separate earlier evidence). The native OS-keyring test used a unique synthetic service/account, restored an encrypted session and cleaned up its entry. This is not a live CF/NOX challenge or cloud-provider acceptance. No new files, production changes, commits or pushes were needed for these checks.

Native clipboard acceptance (2026-09-28): on the rebuilt normal desktop run 60984, opened downloaded thread 577047 through history, right-clicked image 0003.jpg (printed page 112), and chose Copy. ShareX's local clipboard viewer displayed the complete matching page, with PNG/JFIF/bitmap formats available. No upload, clipboard clearing, file save or forum mutation was performed. The read-only viewer was closed and ShareX's original window size restored. This confirms cross-application Windows image clipboard transfer for the thread reader; it does not claim all image formats or native sharing integrations. No implementation/test files were changed or added.

Final forced desktop regression for the Windows directory-move follow-up passed in 55 seconds (34 tasks executed, Gradle 88995). This supersedes the pending forced-run sentence below; native sleeping/hidden-window delivery and authorized live cloud/forum write acceptance remain pending. No commits, pushes or computer shutdown have been performed.

## 新增與搬移檔案樹（2026-09-28）

Windows download write follow-up (2026-09-28): the full shared desktop suite twice failed moving a freshly written staging directory to a tag/RSS chapter with AccessDeniedException; the same offline test passed in isolation. The exact external holder was not identified, so this is not attributed conclusively to OneDrive or antivirus. DesktopDownloadStorageProvider now retries only Windows AccessDeniedException during replacement/rollback directory moves, at most three attempts with 100 ms between attempts; it does not replace existing targets or swallow persistent errors. No new file or dependency. The combined shared desktop/Android tests and Android APK gate then succeeded; unchanged Android tasks were up-to-date. Both failed tests cleaned their isolated directories. A forced full desktop regression is also being run; do not treat the initial successful retry as proof of universal filesystem recovery.

Scheduler restart verification (2026-09-28): added one case to the existing DesktopJobsTest, without production changes or new files. A persisted overdue deadline executes once; after cancel-and-join, newly constructed settings/jobs/process scope reload the next deadline from disk and do not execute a catch-up burst. The fresh job remains registered, shutdown clears it, and failure callbacks remain empty. Full Compose desktop tests executed successfully after normal app shutdown. This verifies scheduler persistence/reconstruction, not actual OS sleep/wake or hidden-window task completion. Live cloud/forum-mutating acceptance requires a user-provided test destination/account or explicit exclusion; confirmation was requested before any such action.

Resolved positive image-save acceptance (2026-09-28): the failure below was reproduced with the same downloaded image in both readers. Thread-reader save succeeded; standalone gallery save approved the chooser but failed with IllegalStateException. ThreadReaderScreen's double-tap navigation incorrectly prefixed local file URIs with the forum origin. Coil repaired that malformed source for rendering, masking the invalid source passed to image actions. Navigation now reuses the existing normalizeImageUrl function for both the list and selected image; no new helper or file. Existing ImageRequestTest covers Windows file-URI preservation/repair. Compose desktop and Android unit suites both executed and passed (Gradle 39294). After a normal exit and clean relaunch without diagnostics (77964), gallery image 4/12 saved successfully: SHA-256 B82E694B21C27D68B8C620EEC00A2D3777FAD75F1680FBC44D99440F3A567B7E matched the original 0003.jpg exactly, and the UI returned to image 4/12. Both test output copies were removed; original downloaded data was preserved. Temporary diagnostic logging was removed. Diff whitespace, OpenSpec integration guard and release-workflow comparison were empty. No commit or push. This closes this local-image save defect, not all image/clipboard/cloud/lifecycle acceptance.

Outstanding positive image-save acceptance: with standalone reader image 5/12 in thread 577047, entered the previously absent absolute destination `.tmp/qa/desktop-image-save-20260928.jpg` into the real Save chooser. The chooser closed but that file was absent after both a Save-button attempt and a fresh filename/Enter attempt. No replacement target or temporary file was found in the QA directory; no file was deleted. The menu remains usable and the app stays running. Do not count this as successful saving: distinguish chooser input/cancellation from an image-read/write failure before changing code. The source uses local file URIs for these downloaded images, awaits the action before dismissing, and returns exceptions through feedback rather than logging them. No success/error snackbar was captured. Source reference for expected image: `C:/Yamibo/YamiboDownloads/thread_577047_page_1_author_all/images/0004.jpg`, SHA-256 `41E43C045F214336A1EFD88BA8C709F2999DE2493087DE8F3048B72C83FEFC16`.

User handoff instruction (2026-09-28): finish all verification, then shut down the computer; do not commit or push; provide the complete file tree. Shutdown remains conditional on completing verification, not on reaching a partial checkpoint.

Native image-action follow-up: right-click on a thread image initially did nothing because only long-press was wired. Added a secondary-click modifier inside the existing ImageContextMenu file, reused by ImageViewer and ImagesReaderScreen; no new file. The existing desktop focus test now covers primary-click exclusion, secondary-click activation and disabled-state guarding. Native retest opened both menus; thread-image Save opened the owned, branded chooser with the expected image filename, and Cancel returned to the reader without writing a file or changing the clipboard. In the standalone gallery, Page Down initially changed the underlying image while its menu remained open; the desktop input enable condition now includes the context-menu state. After rebuilding, Page Down with the menu open retained image 4/12, closing the menu retained 4/12, and Page Down then advanced to 5/12. Final Compose desktop and Android tests passed (Gradle run 96612); no release workflow changes. Positive save/clipboard transfer remain separate acceptance, not established by the cancel test.

Latest native reader follow-up (2026-09-28): wheel-scrolled thread 577047, exited with Ctrl+Shift+Q, restarted and reopened through history. The first run returned to the first image. A scoped read-only database query while wheel-scrolled showed stale index/offset 0/0: the existing pointer-down/up persistence path does not cover wheel or keyboard scrolling. ThreadReaderScreen now observes the settled continuous-list viewport and submits through its existing coalescing persistence coordinator, without a new helper file; this observer skips initial/history-restoration states. On the rebuilt app, wheel scrolling wrote index 4 / offset 1307 before exit, and Page Down subsequently wrote index 5 / offset 647 without leaving the screen. Restart then exposed a separate roughly 200 px shift: when the first visible image differs from the center anchor, the existing restore guard rejects the saved index and fell back to the center block's top, ignoring its saved ratio. The same existing restore function now converts that ratio back to the viewport center using the measured block height (including negative offsets across block boundaries). After normal shutdown, rebuild and restart, history reopened the cross-image viewport with the same visible boundary around y=195 and index 4 / offset 1350; it no longer jumped to the center image's top. Final Compose desktop and Android unit suites both executed and passed (Gradle run 79778). This accepts the reproduced continuous-manga wheel/keyboard persistence and cross-image restart case, not every reader mode or resize-dependent restoration. No temporary/source/test files were added for this follow-up; the tree below is unchanged.

`<pkg>` = `kotlin/me/thenano/yamibo/yamibo_app`。依本次工作樹的新路徑列出，共 96 個檔案；其中 15 個 `[moved]` 是原有 Android／reader 檔案搬移或改名，不是新造一套實作。未列既有檔案的就地修改、建置產物及隔離的 OpenSpec 文件。6 個已合併刪除的短檔不再列入；2026-09-29 依使用者要求，將 commonMain 的 `PlatformImageAnimation.kt` 兩個 expect 宣告併入既有 `ImageViewer.kt`，平台 actual 實作與行為不變。Desktop／Android 編譯及 diff whitespace 檢查通過；沒有重啟或操作 App。

```text
├── composeApp
│   └── src
│       ├── androidMain
│       │   └── <pkg>
│       │       ├── components
│       │       │   └── controls
│       │       │       └── AppScrolling.android.kt
│       │       └── thread
│       │           ├── image
│       │           │   └── PlatformImageAnimation.android.kt
│       │           └── reader
│       │               └── components
│       │                   └── ReaderDesktopInput.android.kt
│       ├── commonMain
│       │   └── <pkg>
│       │       ├── components
│       │       │   └── controls
│       │       │       └── AppScrolling.kt
│       │       └── thread
│       │           └── reader
│       │               └── components
│       │                   ├── ReaderDesktopInput.kt
│       │                   ├── ReaderDownloadSheet.kt [moved]
│       │                   └── manga
│       │                       └── ImageZoomBounds.kt
│       ├── commonTest
│       │   └── <pkg>
│       │       ├── profile
│       │       │   ├── settings
│       │       │   │   └── backup
│       │       │   │       └── BackupStorageSnapshotTest.kt
│       │       │   └── sign
│       │       │       └── SemiAutomaticSignCheckTest.kt
│       │       └── thread
│       │           └── reader
│       │               └── components
│       │                   └── manga
│       │                       └── ImageZoomBoundsTest.kt
│       ├── desktopMain
│       │   └── <pkg>
│       │       ├── DesktopAppContent.kt
│       │       ├── DesktopMain.kt
│       │       ├── components
│       │       │   ├── controls
│       │       │   │   └── AppScrolling.desktop.kt
│       │       │   ├── font
│       │       │   │   └── FontFamilyResolver.desktop.kt
│       │       │   └── systembars
│       │       │       └── SystemBarsEffect.desktop.kt
│       │       ├── desktop
│       │       │   ├── DesktopAppUpdatePlatform.kt
│       │       │   ├── DesktopBackgroundServices.kt
│       │       │   ├── DesktopIcon.kt
│       │       │   └── DesktopJobs.kt
│       │       ├── favorite
│       │       │   └── FavoriteShareFileActions.desktop.kt
│       │       ├── i18n
│       │       │   └── AppLocale.desktop.kt
│       │       ├── performance
│       │       │   └── FavoriteHistoryLoadPerf.desktop.kt
│       │       ├── profile
│       │       │   └── settings
│       │       │       ├── access
│       │       │       │   └── BackgroundAccessPlatformSupport.desktop.kt
│       │       │       ├── backup
│       │       │       │   └── BackupFileActions.desktop.kt
│       │       │       └── bound
│       │       │           └── FontFilePicker.desktop.kt
│       │       ├── thread
│       │       │   ├── image
│       │       │   │   ├── ImageActionHandler.desktop.kt
│       │       │   │   └── PlatformImageAnimation.desktop.kt
│       │       │   └── reader
│       │       │       ├── components
│       │       │       │   ├── ReaderDesktopInput.desktop.kt
│       │       │       │   └── post
│       │       │       │       └── impl
│       │       │       │           └── HtmlDefaultFontFamily.desktop.kt
│       │       │       └── debug
│       │       │           └── ThreadReaderPerfDebug.desktop.kt
│       │       ├── util
│       │       │   ├── DesktopFiles.kt
│       │       │   ├── ShareUtils.desktop.kt
│       │       │   ├── ToastUtils.desktop.kt
│       │       │   └── WindowsFolderPicker.kt
│       │       └── webview
│       │           ├── DesktopBrowser.kt
│       │           ├── DesktopBrowserPolicy.kt
│       │           ├── DesktopBrowserRuntime.kt
│       │           ├── DesktopWafBrowser.kt
│       │           └── PlatformWebView.desktop.kt
│       ├── desktopTest
│       │   └── <pkg>
│       │       ├── components
│       │       │   └── controls
│       │       │       ├── AppScrollingTest.kt
│       │       │       └── RefreshRequestTest.kt
│       │       ├── desktop
│       │       │   ├── DesktopClipboardImageTest.kt
│       │       │   ├── DesktopIconTest.kt
│       │       │   ├── DesktopJobsTest.kt
│       │       │   └── DesktopUpdateAssetTest.kt
│       │       ├── home
│       │       │   └── HomeRefreshBoxTest.kt
│       │       ├── thread
│       │       │   ├── image
│       │       │   │   └── DesktopImageAnimationTest.kt
│       │       │   └── reader
│       │       │       └── components
│       │       │           ├── DesktopReaderFocusTest.kt
│       │       │           └── DesktopReaderInputTest.kt
│       │       └── webview
│       │           ├── DesktopBrowserPolicyTest.kt
│       │           ├── DesktopNativeBrowserTest.kt
│       │           └── DesktopWafPanelTest.kt
│       ├── iosMain
│       │   └── <pkg>
│       │       ├── components
│       │       │   └── controls
│       │       │       └── AppScrolling.ios.kt
│       │       └── thread
│       │           ├── image
│       │           │   └── PlatformImageAnimation.ios.kt
│       │           └── reader
│       │               └── components
│       │                   └── ReaderDesktopInput.ios.kt
│       └── jvmSharedMain
│           └── <pkg>
│               └── thread
│                   └── reader
│                       └── components
│                           └── thread
│                               └── PlatformTextBoundarySegmenter.jvm.kt [moved]
├── dev-docs
│   └── desktop-adaptation.md
└── shared
    └── src
        ├── commonMain
        │   └── <pkg>
        │       └── repository
        │           ├── DefaultFavoriteRepository.kt [moved]
        │           ├── DefaultForumRepository.kt [moved]
        │           ├── DefaultNovelThreadCacheRepository.kt [moved]
        │           ├── DefaultReadHistoryRepository.kt [moved]
        │           ├── DefaultTagRepository.kt [moved]
        │           ├── DefaultThemeRepository.kt [moved]
        │           └── DefaultThreadRepository.kt [moved]
        ├── commonTest
        │   └── <pkg>
        │       └── repository
        │           └── download
        │               └── DownloadImageFetcherTest.kt
        ├── desktopMain
        │   └── <pkg>
        │       ├── Logger.desktop.kt
        │       ├── Platform.desktop.kt
        │       ├── db
        │       │   └── DatabaseFactory.desktop.kt
        │       ├── desktop
        │       │   └── DesktopDirectories.kt
        │       ├── repository
        │       │   ├── DesktopAuthRepository.kt
        │       │   ├── backup
        │       │   │   └── DesktopBackupStorageProvider.kt
        │       │   ├── download
        │       │   │   ├── DesktopDownloadRepository.kt
        │       │   │   └── DesktopDownloadStorageProvider.kt
        │       │   └── font
        │       │       └── DesktopFontPlatform.kt
        │       ├── store
        │       │   ├── DesktopForumFavoriteStore.kt
        │       │   ├── DesktopSecretStore.kt
        │       │   ├── DesktopSessionStores.kt
        │       │   └── settings
        │       │       └── DesktopSettingsStore.kt
        │       └── util
        │           └── time
        │               └── TimeUtils.desktop.kt
        ├── desktopTest
        │   └── <pkg>
        │       └── desktop
        │           ├── DesktopAuthSessionTest.kt
        │           ├── DesktopBackupIntegrationTest.kt
        │           ├── DesktopBackupStorageTest.kt
        │           ├── DesktopDirectoriesTest.kt
        │           ├── DesktopDownloadRepositoryTest.kt
        │           ├── DesktopDownloadStorageTest.kt
        │           ├── DesktopFontPlatformTest.kt
        │           ├── DesktopNativeSecretStoreTest.kt
        │           ├── DesktopOfflineRepositoryTest.kt
        │           ├── DesktopSecretStoreTest.kt
        │           └── DesktopStorageTest.kt
        ├── jvmSharedMain
        │   └── <pkg>
        │       ├── factory
        │       │   └── HttpClientFactory.jvm.kt [moved]
        │       └── repository
        │           ├── AndroidChineseConversionRepository.kt [moved]
        │           └── chineseconversion
        │               └── ChineseConversionRepositoryFactory.jvm.kt [moved]
        └── jvmSharedTest
            └── <pkg>
                └── repository
                    ├── appsync
                    │   ├── AppSyncLocalMutationRoutingTest.kt [moved]
                    │   └── AppSyncProductionTwoDeviceConvergenceTest.kt [moved]
                    └── backup
                        └── BackupRepositoryImplTest.kt [moved]
```


### Follow-up fixes (2026-09-28)

User acceptance: the user confirmed native folder selection/cancellation works ("2. yes it ok"). At the user's request, the old Gradle-launched process was stopped and the current sources rebuilt with `:composeApp:run`; the new branded window appeared and its runtime classpath contained published yamibo-api-jvm 1.1.28, not the adjacent API checkout. The user then confirmed the sign-return fix works ("ok it good now"). This establishes the reported CF-to-sign-return flow, not challenge cancellation/timeout/network recovery or complete desktop parity. Further UI acceptance remains manual-only.

- All targets now consume Maven Central yamibo-api 1.1.28. Local composite substitution was removed; desktop dependencyInsight confirms the published JVM variant. Shared desktop/Android tests and Compose desktop/Android compilation passed with this dependency.
- Windows folder selection now uses the native Explorer IFileOpenDialog on a dedicated COM STA, preserving the Swing event loop and cancellation. Compilation passed; the user confirmed native selection/cancel acceptance.
- Authenticated desktop browsers now permit the exact HTTPS `challenges.cloudflare.com` child-frame origin while retaining trusted-only main-frame navigation and cookie exchange. Navigation-policy tests pass; this is not proof of complete Cloudflare compatibility.
- Semi-automatic sign-in now accepts a successfully parsed current WebView sign page as clearance evidence instead of requiring a second HTTP fetch to succeed. Page-load events alone no longer trigger that fetch. Result-page fallback remains, and a late request cannot trigger a second return. Regression tests cover valid HTML without HTTP, pending-request races, and recovery after a failed check. Manual mode remains user-controlled.
- Final isolated Compose desktop and Android unit suites passed after the sign-return change (59s); the temporary init script was removed. User requested manual-only native acceptance: do not operate or restart their window. Ask them to verify the rebuilt version returns after CF and proceeds with sign-in; also verify native folder selection/cancel. The currently open process has not been replaced by these tests.
- Follow-up return-path review unified browser maintenance/load-error exits with the same completion gate, preventing a late successful request or overlapping error from popping another screen. The four sign-completion regression cases passed on desktop and Android; the isolated build passed in 1m 2s and its temporary init script was removed. No native window operation or restart was performed.

### Remaining manual Windows acceptance

Superseding authorization (2026-09-28): the user has now requested Computer Use acceptance again. Agent-operated local/read-only feature checks and scoped restart are allowed; authentication, CAPTCHA and forum mutations remain user hand-offs. The earlier manual-only restriction below is historical.

### File-layout review and native follow-up (2026-09-28)

Additional native download attempts: page 1 of threads 577045 and 577047 each completed 12/12 images via the actual catalog/download sheet in the selected `C:/Yamibo` folder. Both finished before the minimize observation (577045 completion timestamp 1790541512105, observed minimized at 04:38:40 +08; 577047 completion 1790541645886, observed minimized at 04:40:54 +08). Neither is counted as download completion while hidden. Restore remained functional. No source/test files were added for these attempts, no user downloads were deleted, and background-download native acceptance remains open.

Merged five unnecessarily separate production files into their existing owners: `RefreshRequest.kt` into `AppScrolling.kt`, `BackupStorageSnapshot.kt` into `BackupSettingsScreen.kt`, `SemiAutomaticSignCheck.kt` into `SignWebView.kt`, `DesktopBackgroundAccess.kt` into `BackgroundAccessPlatformSupport.desktop.kt` in the access-settings package, and `ImageContextMenu.desktop.kt` into `ImageActionHandler.desktop.kt`. Reader input expect/actual files now live under `thread/reader/components`; the existing download sheet was moved there too, with caller imports and source-contract tests updated. Zoom-bound geometry and its test moved under `components/manga`; desktop reader input tests now mirror the component package. Small platform actuals, COM dialog implementation and browser security policy remain separate for their platform/security ownership, not one helper per file.

The isolated combined Compose desktop/Android and shared desktop/Android test command passed (42s; unchanged shared tasks were up-to-date). Normal desktop compilation then passed and launched the reorganized build. This is structural cleanup without intended behavior changes.

Native acceptance: through the real reader catalog/download sheet, downloaded page 1 of thread 576991 into the user's selected `C:/Yamibo` folder. Its queue reached Downloaded 27/27 and 27 image files totaled 9,563,144 bytes. Download completed before minimize, so this is NOT evidence of hidden-window completion. After normal Ctrl+Shift+Q exit, launched a new JVM with a temporary localhost-only `jdk.net.hosts.file`; an independent Java probe confirmed forum DNS failure and working localhost, and process arguments confirmed the restriction. History reopened the thread, double-click opened the 27-image reader, and Page Down rendered image 2 offline. Only images 1 and 2 were visually checked, not all 27 or standalone image-position restoration. The app then exited normally and was relaunched with no offline override using Maven Central API 1.1.28. All four temporary verification/launch/probe files from this round were deleted. The downloaded manga remains in the user's selected folder; user data was not deleted. Older policy-blocked artifacts remain untouched.

Latest user-confirmed passes: logout/relogin refresh, tray reopen, native folder selection/cancel, and CF-to-sign-return. Do not repeat these solely to fill the broader matrix. The checks below remain open; automated adapter tests are supporting evidence, not substitutes. Record each result separately with the tested content/mode and any error. Computer Use acceptance is authorized again; authentication and forum mutations still require hand-off.

1. **Offline images and progress:** download a small manga, close the app completely, disconnect the network manually, then reopen from Downloads. Confirm every downloaded image loads and the saved reading position is restored. Tag/RSS downloads need their own offline reopen checks; a text-thread pass does not cover them.
2. **Hidden-window background work:** start a small download, close the window to the tray before it finishes, wait, then reopen. Confirm it completed without reopening first. Separately confirm an enabled notification/check reaches the user while hidden; restore after sleep and verify work resumes without duplicate jobs/notifications.
3. **Reader and local UI:** verify remaining reader modes and fast page transitions, imported-font rendering, image save/copy, and Chinese IME composition in an editor without submitting a post. Confirm selection and shortcuts do not interfere with typing. Native font parsing alone is not a rendered-font pass.
4. **Storage/backup/sync:** use disposable folders for A/B/A switching and confirm each retains its own download list. Test Android backup interchange only against disposable data, never restore over the user's live database for QA. Live cloud-provider authentication/sync must be performed by the user with a disposable dataset; synthetic convergence tests are already available.
5. **Browser recovery:** on an encountered challenge, test Cancel, timeout and subsequent retry/network recovery separately. Successful sign return proves none of these failure paths. Do not change site security settings or force server mutations for QA.

Platform boundaries: macOS/Linux remain explicitly runtime-unverified as agreed. No-tray behavior also lacks native evidence. Final cleanup still has the two previously documented policy-blocked QA directories; do not bypass that block through another deletion mechanism. Packaging/release automation remains excluded.

Scope: Windows/macOS/Linux feature parity with Android. Closing the window keeps background work alive; explicit tray exit shuts it down. Automated packaging, signing and release workflows are excluded.

The current implementation launches on Windows with real application services and background-service wiring. Both modules have desktop JVM targets and use yamibo-api 1.1.28 from Maven Central; local API substitution has been removed. Shared storage and repositories, UI platform actuals, encrypted session adapters and an isolated Chromium browser adapter are implemented. Full feature acceptance is still in progress; a working entry point does not establish complete desktop parity.

## Local verification

Current completion audit (2026-09-27; details/evidence below):

| Requirement | Implementation / verified evidence | Remaining acceptance |
| --- | --- | --- |
| Forum parity | DesktopAppContent supplies real common forum/thread/tag/user-space/blog/sign repositories and the common App UI; native home/forum/profile/reader loading, Chinese search, public user-space topics and refresh passed | Editor/OS IME composition, nonempty blog detail and permission/error flows; authorized server mutations have not been submitted |
| Authentication/WAF | Encrypted OS-keyring storage, native browser isolation tests, user-confirmed login and repeated restart retention; user confirmed logout/relogin refreshes home immediately | Full challenge/retry/cancel/timeout UI |
| Readers/input | Native novel navigation/selection, manga paging/zoom/resize; novel offline restart and continuous-manga wheel/key progress/restart; local-image save byte match and cross-application clipboard; menu input isolation | Remaining modes, font rendering and native sharing integration |
| Local data/sync | Real SQLite repositories; backup round-trip and synthetic two-device convergence; native backup write/picker/cancel/failure and folder change; real text/manga downloads and offline restart; reconstructed thread/tag/RSS local-file reads with no remote calls | Native tag/RSS download flows, Android-generated backup interchange and live cloud-provider acceptance |
| Lifecycle/background | Process-owned schedulers, lock exclusion, native minimize/restore and normal exit, cancellation/deadline tests; user-confirmed tray close/reopen; isolated real App produced scheduled backup after its window was closed | Visible notification delivery, sleep/resume and no-tray native behavior |
| Platform/scope | Desktop/Android compile/tests, macOS/Linux source matrix, local OpenSpec isolation | macOS/Linux runtime explicitly unavailable; final integration/cleanup audit still pending |

This matrix is not a completion claim. API/repository tests do not establish native UI behavior, and source wiring is not evidence of a successful server mutation.

User acceptance update (2026-09-27): after the rebuilt desktop application was launched from Gradle, the user replied "all ok" to all three questions: logout/relogin immediately refreshes home, the tray reopens the window, and agent-operated Windows acceptance may resume. These are user-reported passes, not agent-recorded authentication actions. Subsequent native inspection found the running branded window, loaded home/profile, and the background settings page displaying the available system tray with close/reopen instructions. This also verifies the `remember(trayAvailable)` fix in the real UI. No notification/security settings were changed. Earlier pending-login/tray statements below are superseded by this update.

Offline repository follow-up (2026-09-27): expanded `DesktopOfflineRepositoryTest` passed through the actual `DesktopDownloadRepository` facade with newly loaded settings and fresh delegates. It verifies downloaded status and manifests for thread/tag/RSS content, separate local image URIs, byte preservation and PNG decoding, and a combined count of three downloads, with zero remote thread calls and no background failures. Fixtures are synthetic and written through the real storage provider; this proves reconstructed repository reads, not a new JVM process, real server download or native reader UI. The isolated targeted Gradle run passed; its temporary init script and generated fixtures were removed.

### Download folder switching fix (2026-09-27; native retest pending)

Source review identified a lifecycle mismatch: the remembered `DownloadRepositoryImpl` read its queue once, while `DesktopDownloadStorageProvider.selected()` read the current `backupFolderUri` for every operation. `DesktopBackupStorageProvider.setSelectedFolder` changed that setting without reinitializing downloads. A subsequent `persistQueue()` could therefore replace the new folder's valid queue with old in-memory state. Starting without a configured folder and selecting a populated folder later also failed to load that folder's queued tasks. Corrupt-queue JSON validation did not protect against a valid-but-unrelated overwrite.

The desktop root now supplies a stable `DesktopDownloadRepository` facade observing the folder setting. Each delegate receives a pinned folder and its own structured coroutine scope. `collectLatest` cancels and joins foreground operations and background workers before opening the next folder, including when returning to a previously used folder. The UI keeps one repository/queue subscription. The common queue writer starts undispatched and finishes its short serialized persistence section non-cancellably, so an acknowledged clear is not lost during cancellation; network/download operations remain cancellable. No Android folder-switching behavior was changed.

Isolated tests passed for initial unconfigured state followed by selection, A/B/A queue isolation with real filesystem providers, clear persistence, and gated foreground/background cleanup before B is opened. A separate common regression test gates a queue write, cancels its owner, then proves the mutation persisted; it passed on desktop and Android. Full suites before adding that final test passed: shared desktop 307 cases (two optional native skips), shared Android 468, and Compose desktop 221 (one native skip). The final targeted rerun passed 26 desktop and 24 Android download cases, including the added cancellation case. `:composeApp:compileKotlinDesktop` passed; subsequent root edits were indentation only. All runs used isolated build outputs. Generated two-folder fixtures and the temporary Gradle init script were removed; previously documented cleanup-blocked paths remain unchanged.

Native folder-switch retest is pending: the live application was not restarted or operated while the user tests login/tray. The answer "yes" authorized those checks but did not establish that either passed. This fix does not complete the remaining offline manga/tag/RSS or broader desktop acceptance matrix. Release workflows/update files remain unchanged and the OpenSpec history isolation check is empty.

Latest artifact preparation (2026-09-27): the previously observed application PID 53020 was absent and its Gradle session 75850 no longer existed; no replacement Java desktop entry process was found. The cause of exit is unknown. After this check, the normal (non-isolated) `:composeApp:desktopJar` build with the local API source option passed in 1m 9s, incorporating the tray-state and download-session fixes. No app window was launched and no installer/release task was run. Manual login/tray results and subsequent native acceptance are still pending.

```powershell
.\gradlew.bat :shared:desktopTest :shared:compileDebugKotlinAndroid --console=plain
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug --console=plain
```

Use the published Maven Central API for all targets. Earlier local-source verification notes below are historical, not current launch instructions:

```powershell
.\gradlew.bat :shared:desktopTest --console=plain
.\gradlew.bat :composeApp:desktopTest --console=plain
.\gradlew.bat "-PdesktopNativeBrowserTest=true" :composeApp:desktopTest --tests "*DesktopNativeBrowserTest" --console=plain
```

The API main branch adds an app-owned `YamiboWafChallengeHost` browser overload. This is **not itself a browser implementation**. The app must supply the browser, trusted-origin navigation and an isolated ephemeral Cookie context, dispose it after each challenge, and never log session credentials. Verification and replay remain in the API coordinator. New sessions strip old WAF clearance before seeding authentication.

The desktop adapter uses [JCEF Maven 146.0.10](https://github.com/jcefmaven/jcefmaven) with per-browser ephemeral request contexts. Native libraries are downloaded/extracted into the versioned application data directory on first use; this requires network access and is not release packaging automation. Chromium console logging is suppressed. Cookie commands execute on the Swing UI dispatcher; executing the first command from the test worker caused a verified timeout. The opt-in native Windows test passed after correcting dispatch and proves local navigation plus HttpOnly Cookie/context isolation, not real login or WAF end-to-end acceptance.

The WAF overlay has an opaque surface, claims input focus and intercepts clicks in its empty regions. An isolated Compose test passed for blocking underlying reader Page Down and pointer actions while keeping Cancel usable. Native Swing/browser overlap and real challenge completion remain unverified.

## Verification boundary

Seven platform-independent implementations (forum, thread, tag, remote favorites, novel comment cache, history and theme) now live in commonMain as Default* repositories; Android callers use the same implementations. Shared desktop and Android unit tests plus Android UI compilation passed after extraction.

Desktop backup storage and font import adapters are wired, with full UI acceptance pending. Backup writes reject overwrite/path traversal, and deletion is restricted to the selected backup folder. Font imports validate actual font data before installing it and restrict deletion to the private fonts folder. The backup repository regression tests now run unchanged from jvmSharedTest on Android and desktop; both passed. A desktop integration test passed using separate SQLite databases and real filesystem backup storage, restoring chapter progress and fractional first-line indentation and reopening the restored settings. This is not a real-device Android backup interchange acceptance result.

AppSyncLocalMutationRoutingTest and AppSyncProductionTwoDeviceConvergenceTest also now run unchanged from jvmSharedTest. Both desktop and Android passed all 19 cases using real repositories/SQLite and a synthetic in-memory journal remote, covering operation recording and two-device convergence across creates, updates, selected deletes and clear-all. This does not establish live cloud-provider authentication/upload/download or desktop sync UI acceptance. The temporary isolated-build init script was removed after the run.

Windows JVM core tests and Android regressions are available locally. macOS/Linux runtime testing is explicitly unavailable per user confirmation; implementation must remain cross-platform but these runtimes must be marked unverified. The real desktop entry now launches on Windows: live home content, profile navigation, settings navigation and the branded title-bar icon were observed through Computer Use. Login, tray and full feature acceptance remain pending.

The full shared/Compose desktop suites, Compose Android unit tests and debug APK assembly passed after reader keyboard integration and background-service fixes. Native browser and keyring tests are opt-in rather than silently assumed by the default suite: both have separate successful Windows runs. Browser evidence predates the final auth wiring and is not a real-login acceptance result. AES-GCM persistence, restart, tamper rejection and session expiry/logout have unit coverage. No real session credentials were used in automated browser tests.

`DesktopSecretStore` stores encrypted session data with an AES key held by [java-keyring](https://github.com/javakeyring/java-keyring); no plaintext fallback is used on keyring failure. Only the two authentication cookies persist, while WAF clearance stays in memory. The opt-in Windows native keyring round-trip passed with an isolated synthetic credential that was removed afterward. Cookie expiry and logout remove persisted authentication and invalidate older browser callbacks; profile response writes verify both generation and authentication snapshot. End-to-end user login/logout remains pending.

Windows testing exposed a startup-state regression: reading a thread before selecting a download folder threw from the optional offline-content lookup and closed the Compose window. Desktop read/manifest/image lookup now returns a cache miss when no folder is selected; a regression test passed and the corrected application was relaunched with the live home page visible. Explicit download writes still require a configured folder. The current test window should be retained for user-assisted login.

The user subsequently confirmed successful login but reported a profile-refresh crash and no immediate home refresh. The running process logged NoClassDefFoundError for LoginComponentKt's click coroutine before entering the refresh logic. Its classpath used build/libs JARs that had been overwritten by concurrent validation builds; the class exists in the rebuilt output. After stopping that process and restarting the latest build, the authenticated profile and personalized home survived restart, the title 百合會論壇 appeared without a menu bar, and clicking profile Refresh did not reproduce the crash or produce new terminal errors. This supports a development-run classpath mutation issue, not a proven network refresh defect. Do not rebuild app/API/shared JARs while an acceptance process is using them. The separate first-login home-refresh report remains open and must be retested through a fresh user-assisted login; restart success is not proof that login-time invalidation works.

Optional offline-content reads also handle a removed download folder or malformed manifest without closing the reader. The desktop adapter reports the problem once through a warning dialog and preserves the original files; the caller may fall back to online content. Filesystem tests passed for missing-folder recovery, malformed-manifest preservation and tag/RSS chapter manifests plus local image URIs across settings/storage restart. Queue initialization and background persistence exceptions now report failure rather than escaping their coroutines, while cancellation still propagates. Desktop queue writes refuse to overwrite an existing malformed queue, preserving it for recovery. Shared desktop/Android download regression tests and desktop corruption-preservation tests passed. Download-library enumeration now isolates malformed manifests per item in all three content types, retaining healthy results and original corrupt bytes; an unavailable selected folder returns an empty list with the same once-per-instance warning. All six DesktopDownloadStorageTest cases and desktop compilation passed using isolated build outputs. These tests do not establish full-reader offline UI acceptance or visible error-dialog behavior; Windows window operations are paused at the user's request.

## Remaining integration

Animated images now use a desktop Coil decoder and lifecycle-owned painter backed by the existing Skiko Codec; no new dependency was added. The pinned Coil 3.5.0 JVM decoder previously produced one static bitmap, and coil-gif remains Android-only ([upstream documentation](https://coil-kt.github.io/coil/upgrading_to_coil3/)). All 18 common AsyncImage/SubcomposeAsyncImage/rememberAsyncImagePainter call sites route through the platform transform; Android/iOS transforms are identity functions. GIF/WebP headers select the desktop decoder, static images retain Coil's normal path, cached encoded data is immutable, and each painter decodes frames on a worker rather than retaining a full decoded animation. Skia handles frame dependencies/disposal, durations and finite/infinite repetition. RememberObserver cancellation releases the worker/codec when the painter leaves composition. Desktop animated crossfade alone is disabled because Coil 3.5's non-Android crossfade wrapper does not forward the forget notification.

Five isolated animation tests passed with generated in-memory GIFs: colors/timing metadata, finite repetition, infinite-playback cancellation, transparent restore-previous disposal, static/malformed handling, and a real Coil load plus painter drawing/forget/restart (some cases cover multiple assertions). The full Compose desktop suite passed with 203 cases (one opt-in native browser skip); all 215 Android Compose unit tests passed, as did desktop/Android compilation. Acceptance-app JAR hashes remained unchanged and no windows were operated. Animated WebP fixtures, large-image resource behavior, hidden/minimized-window suspension and actual full-App visual playback remain to verify; macOS/Linux runtime remains unverified.

The home-only desktop refresh workaround was rejected by the user because other pages were still broken and its sudden spinner lacked the mobile pull animation. It has been removed. Home, forum, updates, messages, user space, novel/tag/RSS details and tag lists now share AppPullToRefreshBox, retaining Material's native pull progress, threshold, release and settle animation, plus desktop F5. All common vertical LazyColumn/verticalScroll call sites route through thin platform wrappers; Android/iOS retain native scrolling unchanged. Desktop drag uses Compose's drag slop/mutation arbitration, scroll-state movement, nested pre/post scroll and fling dispatch, so a top-edge mouse pull drives the native refresh animation. Native wheel overscroll is absorbed before reaching refresh because wheel scrolling has no matching release to settle it. Merely wheeling to the top does not refresh or leave an idle spinner.

Isolated input tests passed for F5/busy suppression, mouse list/plain-column scrolling, retained button clicks, text-editor selection, visible fractional pull progress before release, below-threshold return to zero and threshold-triggered refresh after release. After removing the obsolete home-specific wrappers, the full Compose desktop suite passed (207 cases, one native-browser opt-in skipped), all 215 Android Compose unit tests passed, and debug APK assembly succeeded. These exercise the shared adapters used by all nine refresh screens, not live server actions on every screen. The first-login home request-generation fix remains in place. Full Windows smoothness/navigation acceptance is still pending at the user's request not to operate windows; the currently running app has not been restarted or updated.

The animated WebP test now also passes through the real Coil decoder using generated RIFF/ANIM/ANMF data and independently decoded still-frame pixel references: both distinct frames, their 40/60 ms metadata and a single terminating loop are verified. The initial test failure was a fixture assumption that Skia quality 100 produces lossless VP8L; the actual encoder produces VP8, so references now account for lossy colors. No production decoder change was required.

Refresh request cleanup is now shared by forum, updates, messages, user space, novel/tag/RSS details and tag lists. The launch helper guarantees completion cleanup on success, exceptions, cancellation and an already-cancelled scope; cancellation is rethrown without a misleading network-error notification. Other exceptions show generic load failure without exposing raw exception details. Tag-list refresh result failures now report feedback while keeping old tags, and RSS only reports successful refresh when loadPage produced Success. Three helper tests cover successful/failed execution, suspended cancellation and pre-cancelled scope cleanup. The full Compose desktop and Android unit suites passed after this change. This does not replace real authenticated page-by-page network acceptance.

DesktopMain now publishes visible/not-minimized/not-exiting state outside the window's content composition. Animated painters cancel/join decoding on hide and restart on show, while download/sync scopes remain independent. The seven animation tests and four scrolling tests passed, including injected visibility hide/show, disabled scrolling, secondary-button exclusion and reverse layout. Desktop compilation also passed after the final root binding placement. Actual tray/minimize UI events and large-image resource behavior remain unverified; the visibility test does not operate a real window.

Installer selection now rejects explicitly foreign platforms rather than falling back to extension alone, and desktop selection matches declared CPU architecture aliases (unspecified/universal assets retain compatibility). HTTP header waits use cancellable runInterruptible. On Linux, a verified AppImage receives owner-execute permission while preserving its other permissions, then launches directly as one ProcessBuilder argument (no shell); other installer types use the OS file handler. Desktop compilation and the two selection tests passed after this change. Actual cancellation/installer handoff and Linux permission/process execution remain runtime unverified. No packaging, signing, update manifests or release workflows were changed.

A fresh isolated verification run completed shared desktop tests (283, one native-keyring opt-in skipped), Compose desktop tests (198, one native-browser opt-in skipped), Compose Android unit tests and debug APK assembly. Outputs are under each module's build/desktop-verification; hashes of the app/shared/API JARs used by the still-running acceptance app were unchanged. A temporary init script redirected build directories and disabled only the i18n properties-file path overrides, preserving strict missing-term checks; it was removed after verification. No permanent build/release configuration changes were needed for isolation.

That run exposed a real cache shutdown race: per-cache LRU/access-time coroutines could outlive the driver and retain a SQLite journal lock. The factory now owns their shared IO scope and cancels/joins maintenance before closing its driver; desktop disposal waits for this IO-only shutdown. The close/reopen test and complete shared desktop suite passed after repair. Cleanup of the failed test's isolated Temp/yamibo-cache-close-11387675926130916560 folder was blocked by the execution policy; this synthetic test folder remains pending cleanup. These latest changes are validated builds, not hot-loaded into the currently open acceptance app.

Desktop readers now route Page Up/Down, arrow keys and Space (Shift+Space backwards) to existing page transitions or viewport scrolling. Horizontal arrow direction follows RTL mode. Single-page mouse-wheel input is rate-limited to avoid overlapping page animations; continuous mode keeps native scrolling. Commands are disabled for visible reader panels and only consume keys when the reader itself owns focus, not descendant text editors. Desktop mapping tests and isolated Compose v2 UI tests passed for reader focus, Chinese editor input, Page Down, mouse-wheel paging and disabled input. These tests exercise the input adapter, not full readers. Actual OS IME, selection, resize and complete reading flows remain pending. These changes are not hot-loaded into the user-assisted-login window.

Desktop disposal now closes the cache database driver as well as the primary database/client. A cache write-close-reopen regression test passed. Startup file-lock failures and window-preference write failures show a bounded error message instead of an uncaught coroutine error; explicit exit runs final application disposal even if native-browser cleanup throws. These error-path UI messages still require runtime acceptance.

Background favorite update and backup invocations serialize scheduled/manual work. Only a completed update snapshot produces a success notification; interruption is not reported as completion. Desktop favorite runner polling and downloads share the process cancellation lifetime. Image downloads restrict forum cookies to the exact HTTPS forum origin and re-evaluate every redirect; JVM/Android tests cover cross-origin redirects, plain HTTP, alternative ports, protocol-relative URLs and bounded relative redirect loops.

Desktop runtime icon resources reuse the Android launcher artwork through a generated resource (no duplicate artwork or release workflow changes). Main window, Dock helper and tray are wired; the Windows title-bar icon was visually confirmed. Tray/taskbar/Dock acceptance is still pending. The window title is now 百合會論壇 and its white menu bar was removed at user request (compiled, awaiting runtime refresh). Message-center access moved to the tray; explicit exit remains in the tray and is available through Ctrl/Meta+Shift+Q when no tray is available. Close hides the window when a tray was successfully installed; otherwise it minimizes. A process file lock prevents duplicate workers. Window size/maximized preferences persist; hiding the window keeps the service composition alive.

- Complete entry/lifecycle failure-path and tray acceptance; currently real services are assembled and the Windows entry runs.
- Secure OS credential storage; no plaintext-cookie fallback. Connect browser logout/session generation and WAF root, then verify real login and challenge flows.
- Desktop readers, file/image/font actions and complete forum/local/sync workflows.
- Tray, notifications, scheduled work, close/reopen/exit and missing-tray behavior.
- Windows feature-by-feature acceptance and final Android regression checks.

Implementation tasks are tracked locally by OpenSpec `desktop-parity`; OpenSpec artifacts must not merge into main.

## Windows acceptance follow-up

Desktop wiring review (2026-09-27): found that the remembered background-access adapter captured the initial `trayAvailable` parameter. A tray created after first composition could therefore remain described as unavailable even though the native icon worked. Its `remember` is now keyed by tray availability, so the provided setup state is rebuilt when capability changes. The complete isolated Compose desktop test task passed; the live acceptance app/JARs were not rebuilt or restarted during the user's manual login/tray checks. Native setup-page verification of this final change remains pending. README now links the acceptance matrix and documents local API substitution, startup, native-browser download, secure-storage requirement and explicit exit. Release workflows/update files remain unchanged against main. The temporary isolated-build init script was removed.

Folder-return and native offline acceptance (2026-09-27): in the rebuilt app, entered Backup with no folder, selected the existing isolated QA directory via Storage, and returned to Backup. The correct path, one backup and 14.13 kB appeared immediately without creating another file, verifying the reactive-folder fix. The previously used novel reopened at chapter 3 after process restart. Through its actual reader catalog/download sheet, downloaded only page 1: a completion snackbar appeared and real `queue.json`, `manifest.json` and a 558432-byte `thread_page.json` were written. Then exited normally and restarted with a temporary JavaExec-only `jdk.net.hosts.file` containing localhost entries. A separate JVM probe proved forum DNS unavailable and localhost intact; the running app process was confirmed to carry that flag. With that restriction, download management retained one completed page and history opened the novel's chapter-3 body at the saved position. This validates this text-page offline/restart flow, not manga image downloads or tag/RSS offline flows. No OS network/security setting or credentials were modified. The test process exited normally; its three temporary launch/probe files and temporary folder preference were removed. The earlier policy-blocked QA directory (including backup and this downloaded page) remains pending cleanup; no alternate deletion route was attempted.

Native backup write acceptance (2026-09-27): selected a newly created isolated `build/desktop-ui-qa-20260927` directory through the Swing chooser, created a timestamp-named backup through the actual app, and observed one backup / 14.13 kB in the UI. The file was 14469 bytes and parsed as JSON with the expected backup sections; no restore was submitted. This exposed a stale folder label when returning from Storage settings: `BackupSettingsScreen` refreshed only on first composition. It now observes `backupFolderUri` and keys its refresh effect on the repository/folder. Desktop and Android Compose test tasks both passed after this two-line fix; native verification of the changed return-navigation behavior is still pending. After normal app exit, only the temporary `appsettings.backupfolderuri` setting was removed, restoring the original unset state. Cleanup of the isolated test directory was rejected by execution policy; its test backup remains there and was not removed via an alternate route.

Latest restart/input check (2026-09-27): the previous Gradle run exited normally before rebuilding; the new run includes the DesktopJobs completion-handler repair. Native home and forum primary-button drags scrolled content without opening a card. Forum F5 displayed a refresh indicator and subsequently settled; a later top-edge mouse pull completed with new data (today count 87 to 12; first listed thread views 20122 to 20126). Wheel overscroll to the forum top did not show an idle refresh indicator. The initial home request timed out at 60000 ms, visibly reported the timeout, preserved cached content and settled its indicator without crashing; this run does not establish a successful home network refresh. Existing isolated refresh/scroll XML reports were rechecked (8 tests, zero failures/errors/skips), not rerun during the live app session. Native snapshots establish gestures and settled states, not frame-by-frame animation smoothness. The window remains open for the user; no account actions, folder changes or data restoration were performed.

Offline repository reconstruction is now covered with real desktop settings/filesystem storage and synthetic serialized ThreadPage/PNG data. Fresh settings, storage and DownloadRepositoryImpl instances recovered Downloaded status, Chinese text and file-URI image substitutions (both HTML and image metadata), with byte-identical local image content. Every remote ThreadRepository invocation was configured to fail and counted; the count remained zero and no background failure was reported. Scope cancellation/join precedes temporary-directory cleanup. Full shared desktop regression passed (305 cases, two optional native skips: keyring and the previously verified explicit-font case), zero failures/errors. This does not claim a full process restart, disconnected App navigation, persisted reader progress or successful user-selected download execution. No current user data/settings or live JARs were changed; the isolation script was removed.

Large-animation acceptance now includes a generated in-memory 1536x2048 GIF. Six alternating full-size frames were delivered correctly, cache weight included encoded data plus poster pixels, and cancel/join prevented subsequent delivery. The callback retained only sampled colors, not decoded frame images. Full Compose desktop regression passed (221 cases, one native-browser opt-in skip, zero failures/errors); no production change was needed. The isolation script was removed and the live app was untouched. This does not measure peak native/heap memory or establish long-duration stress/hidden-window OS behavior; those boundaries remain explicit rather than inferred from a passing functional test.

Native search/public-profile acceptance: entered the Unicode query 百合 into the focused search field; Enter loaded real matching threads. Ctrl+A visibly selected only the query and Left collapsed its selection without navigating away. This verifies Unicode text entry, not OS IME candidate/composition behavior. Clicking a result's author opened the corresponding public profile; its blog entry resolved to an explicit empty-list message (no nonempty blog detail tested). Public topics loaded real dated threads. A primary-button drag scrolled the topic list without opening a card, and F5 returned refreshed content to the top; the leading view count changed from 301 to 302. No friend request, subscription, message, sign-in or other forum mutation was submitted. No production changes were needed for these checks.

Positive native-font coverage was added using an explicitly supplied existing Windows Arial TTF in an isolated temporary directory (no font asset added to the repository). Import bytes matched the source; native Java parsing after reopening succeeded; importing the same ID was rejected; deleting the imported copy succeeded while the source bytes stayed unchanged. The first test attempt used Java's File-based font loader, which retained a Windows file lock; the test now mirrors production's stream-based validation. No production font change was required. The failed test's exact temporary copy/directory and the temporary Gradle isolation script were removed. Full shared desktop regression passed with 304 cases, one native-keyring opt-in skip, zero failures/errors; the new font case ran, not skipped. Normal runs without `yamibo.test.font` explicitly skip that optional case. This is native font import/storage acceptance, not Compose reader glyph/rendering or font-picker UI acceptance.

Background shutdown review reproduced two lifecycle-state bugs with failing tests: jobs submitted to an already-cancelled process scope, and queued jobs cancelled before dispatch, retained running entries because body-level finally never executed. DesktopJobs now registers identity-checked invokeOnCompletion cleanup after registration and before start, covering pre-start cancellation without letting old jobs remove replacements. Both regression cases and the complete desktop suite passed after the minimal change (220 cases, one native-browser opt-in skip, zero failures/errors). Only desktop source/tests changed; prior Android 221-case/APK evidence remains unchanged, not rerun. Isolated output preserved live app JARs and the temporary init script was removed. This proves job-state cleanup, not native tray/background delivery or sleep/resume acceptance. The current live app does not yet include this final job cleanup repair.

The chooser-owner repair compiled and was revalidated after another normal app shutdown/relaunch. The backup file chooser now visibly inherits the app icon and is centered over the app instead of the desktop. Cancel closes it and returns to the backup page. The shared open/save/directory helper is changed, but only the open-file path was visually retested after repair; overwrite confirmation was not triggered. The current live app includes this repair.

Native backup acceptance on the safely restarted post-repair build: the backup page loaded with no configured folder; the file picker opened and Cancel returned without selecting/importing anything. Creating a backup with no folder was rejected by DesktopBackupStorageProvider (observed error stack), returned to enabled action buttons and did not terminate the run session. The transient failure snackbar was not captured, so its visual presentation is not claimed. Storage settings loaded actual cache usage; its directory-only chooser displayed folders, and Cancel preserved the unset folder. The user was asked for a durable shared download/backup directory before positive write/offline acceptance. Native picker windows still displayed the Java icon and were centered independently; the shared chooser now uses the active AWT window as owner for open/save/overwrite dialogs, with the app title on overwrite confirmation. This final owner change still needs compile/native verification; the running app is the earlier post-backup-repair build. No backup was created or restored and no existing data was overwritten.

Backup failure-path review found that DesktopBackupStorageProvider correctly throws when a selected folder is unavailable, but BackupSettingsScreen's initial LaunchedEffect and post-create/restore refresh did not handle listing exceptions. The screen now reads a single storage snapshot (count and bytes from the same listing), preserves prior displayed data on failure and posts load-failure feedback. Create/restore use the existing cancellation-aware completion helper so working state always clears, even if a repository throws. Success is reported before listing refresh so an independent listing failure is not hidden by a later success message. Three new common tests cover one-list consistency, unavailable-folder failure/recovery and cancellation propagation. The standard coroutines-test dependency was added to commonTest only, matching the existing coroutine version. Full isolated Compose desktop tests passed (218 cases, one native-browser opt-in skip), Android tests passed (221), and APK assembly succeeded. The temporary isolation script was removed. The live app was not rebuilt or restarted; native file dialogs, unavailable-folder UI and backup/import/export acceptance remain pending. No backup format or release workflow changed.

Windows lifecycle follow-up (2026-09-27): launched a second JVM using the already-running application's exact classpath without rebuilding live JARs. Its main thread stopped at DesktopMain.kt's lock-null message before settings, browser, database or background-service initialization; the original process stayed responsive. The tool could not select the independent Swing dialog, although its already-running message was visible in a transient capture during cleanup. The two test-only duplicate processes were terminated after identity checks; the original app was retained. This verifies single-instance exclusion, not normal dialog dismissal or tray reopening. Clicking the native minimize button was confirmed by the window tool; the original process remained alive. Activating/restoring it retained manga image 4/27 and the same window dimensions after the OS animation settled. Actual close-to-tray/reopen, background task completion and missing-tray/sleep behavior remain pending.

Windows manga acceptance reached a real 27-image thread via image double-click. Page Down moved 1→2, wheel 2→3 and a mouse swipe 3→4, each with corresponding loaded images. Double-click zoom and subsequent pan retained page 4. Native inspection exposed excessive black space when panning a fitted portrait image: bounds used the entire viewport rather than the fitted image rectangle. The repair reports painter intrinsic dimensions (including cached display), uses ContentScale.Fit dimensions for paged bounds, clamps double-tap targets and rendered translation, and preserves container-based scroll-mode behavior. Dedicated portrait/landscape/resize/fallback tests passed. Full post-repair Compose desktop tests passed (215 cases, one native-browser opt-in skip), Android Compose tests passed (218), and APK assembly passed. Shared sources were unchanged since the preceding successful gate. The temporary isolation script was removed.

The old app exited normally before the repaired version was built and launched. History and the signed-in home remained available. Reopened the same manga and paged to image 4/27: double-click zoom followed by the same diagonal pan no longer exposed the excessive right-side black strip. Maximizing retained page 4 and centered the image when its scaled width was smaller than the viewport; a horizontal drag did not pull it off center. Restoring the window retained page 4 with valid bounds. These native checks validate fitted-image boundary wiring, not every gesture mode or frame-by-frame animation smoothness. Reopening through thread history starts the thread reader and image double-click selects that image; it does not prove standalone image-page restart restoration.

Windows chapter navigation acceptance: the reader catalog listed real chapter titles; selecting floor 5 / third story chapter loaded its corresponding text. Returning to details showed that chapter in Continue Reading; reopening loaded floor 5 with the same chapter title and opening text, not the introductory post. This confirms in-session chapter persistence/re-entry, not process-restart/offline restoration. Page Down while the reader toolbar was open did not navigate, matching the deliberate overlay-input guard; after toolbar closure the earlier keyboard checks apply.

Windows novel acceptance: opened the light-novel forum, a real novel detail page and its reader; loading skeleton resolved to metadata, chapter list and content. The settings panel supports mouse dragging. Temporarily switched from continuous scrolling to left-to-right single-page mode: Page Down moved the first post from page 1/3 to 2/3, then one wheel action to 3/3, with corresponding content and settled page indicators. Restored the original continuous-scrolling setting afterward (selected chip verified). This does not yet prove chapter jumping, later-chapter progress restoration, all layout modes, manga or offline reading. No forum content was submitted.

Follow-up Windows reader checks resolved the earlier ambiguous capture: after the Page Down animation settled, a horizontal drag over actual comment text changed selection without moving the viewport. Dragging a non-text/header region moved the viewport while preserving the previous selection, not extending it. Maximize then restore kept the same visible comment area and selection. No production gesture change was warranted by this case; full novel/manga selection/resize remains separate acceptance. The profile page also showed the restored signed-in session, and its Refresh action finished with the card restored and the run process still alive. No logout, credential entry or forum mutation was performed.

Windows acceptance resumed with the user's explicit approval. The old `:composeApp:run` session exited normally through Ctrl+Shift+Q (Gradle exit 0) before building/launching the new version; no live JAR overwrite. Native observations on the new app: branded title/icon and no menu bar; home content and saved favorite forum entries restored; mouse drag scrolls home, wheel back to top leaves no refresh indicator, and a top-edge mouse pull shows an indicator that disappears after completion. ForumPage loads real content; F5 and mouse pull both show then clear the indicator (view counts updated on refresh). A real comment thread loads, and Page Down moves the viewport. A drag across the reader body also produced text selection while moving the view, so reader selection/drag arbitration still needs focused acceptance; do not treat that as fully passed. Point-in-time captures prove these states, not frame-by-frame animation smoothness. Current running build is the newly compiled version, unlike earlier paused-session notes.

Latest combined regression gate passed: shared desktop 303 cases (one native keyring opt-in skip), Compose desktop 212 (one native browser opt-in skip), shared Android 468 and Compose Android 215, all with zero failures/errors; debug APK assembly passed. Shared tests and APK tasks executed; unchanged Compose test tasks were Gradle up-to-date with their successful reports retained. This is not native UI acceptance. Temporary isolation script removed afterward.

## macOS/Linux source audit (runtime unverified)

This is a source/dependency audit, not native execution evidence. No macOS or Linux test host is available. Windows evidence does not establish those platforms' behavior.

| Area | macOS implementation | Linux implementation | Remaining native acceptance |
| --- | --- | --- | --- |
| Data and SQLite | `Library/Application Support/yamibo-app`; JDBC SQLite schema handling | Absolute `XDG_DATA_HOME`, otherwise `.local/share/yamibo-app`; same JDBC adapter | Native driver loading, permissions, restart |
| Credentials | OS Keychain through java-keyring; AES-GCM session file | Secret Service/KWallet through java-keyring; same encrypted file | Available/unlocked credential service, login/logout/restart; failures do not use plaintext fallback |
| Browser | JCEF builder app handler; three required module opens in application and opt-in test JVMs | Same windowed JCEF runtime and ephemeral browser contexts | Native installation, first-use network dependency, WAF, focus/IME and disposal |
| Files and images | Swing chooser, NIO atomic replacement fallback, AWT clipboard, Skiko animation | Same portable adapters | Dialog placement, clipboard formats, fonts, large images |
| Window/background | Window icon plus supported Taskbar Dock icon; AWT tray with minimize fallback | AWT tray when supported, otherwise minimize; process-owned workers | Actual tray availability, notification delivery, sleep/resume, hide/reopen/exit |
| Update handoff | Platform/CPU selection; verified dmg/pkg opened by OS handler | Platform/CPU selection; deb/rpm OS handler or verified AppImage direct execution | OS handlers and execution permissions; release production remains excluded |

The [pinned JCEF README](https://github.com/jcefmaven/jcefmaven/blob/146.0.10/README.md) lists native artifacts for macOS amd64/arm64 and Linux amd64/arm64/arm. This is dependency availability, not a claim that the whole application supports every listed architecture. The source audit found that the opt-in desktop test JVM lacked the macOS module opens already present in application launch; those flags are now aligned. No native macOS result is claimed.

Credential encryption prevents plaintext session storage, not compromise by another program running as the same user. The [java-keyring security notes](https://github.com/javakeyring/java-keyring#security-concerns) describe OS- and packaging-dependent isolation limits; the development JVM is not an application-security boundary. Packaging/signing changes remain outside this task. Linux requires a functioning user-session credential service; a headless or service-less installation is not validated.
