# Downloaded content validation

## Revisions and isolation

- Production: `cd202b6facb90f9d0591ec09e7d0c3474be31c81`, PR https://github.com/LittleSurvival/yamibo-app/pull/62 (draft).
- Validation code: `3852dce651ad11aac7f3a063ea7ab127719e261d` on `stable-v0.0.9-fix-downloaded-content-loading-d76edbf-9be0117-validation`.
- `production-source-comparison.json`: all 11 changed production sources are byte-identical in validation. Validation alone adds test sources/dependencies, startup adapters in MainActivity/MainViewController, an independent application/bundle ID, a local DocumentsProvider, QA Activities and an Apple UI workflow.
- Production test-isolation and OpenSpec guards pass; no test/QA code or OpenSpec files are proposed for main. Do not merge the validation branch.

## Android builds and regressions

Production `:composeApp:compileDebugKotlin` passed on cd202b6. Validation `:shared:testDebugUnitTest --tests '*DownloadRepositoryTest' --tests '*DownloadedContentLoadingTest' :composeApp:assembleDebug` passed, max-workers=2.

40 tests, zero failures/errors/skips (31 repository + 9 presentation), see `android-unit-results.json`. Includes local reads while global initialization is held, target and author validation, full-thread-only image fallback, batch missing-image mapping, inventory reuse/forced refresh/folder changes, deletion/read ordering, and 100 pending queue writes coalescing to initial/inflight/latest writes.

## Android emulator flows

Pixel_8_Pro / Android 16 / emulator-5554, debug APK, 2 cores and 2 GB. Independent package `me.thenano.yamibo.yamibo_app.downloadqa.debug`; existing app data was untouched. Wi-Fi/mobile data were disabled for reader checks, then restored; the QA package/fixture tar were removed and this newly started emulator shut down after validation.

Fixtures: 150 KB HTML novel and 300 synthetic cyan-striped 800x1200 local PNGs per manga chapter. `generate_fixtures.py` reproduces them. Actual product repository/storage/provider are used in reader flows; management stress tests use the actual product screen and a synthetic 5,000-page repository.

- Novel, ordinary thread, thread manga, tag manga and RSS manga loaded local content while offline and scrolled/flipped (`final-reader-checks.json`, screenshots/XML).
- A restored author-specific manga reader also worked when only the all-author local folder existed.
- Cyan pixel checks verified actual image rendering for thread/tag/RSS readers (`reader-image-pixels.json`); screenshots show real fixture images.
- Initial actual-reader query counts were 6/6/12/11/11 respectively, all main-thread query counts zero. UI observation durations include Activity/WebView cold initialization and uiautomator dumps; they are not precise reader latency measurements.
- 5,000-page management: expanding and 100 progress updates kept inventory reads at one and preserved expansion; removal added one read; error plus retry recovered at four reads; scrolling passed (`final-management-checks.json`). Earlier UI checks also exercised sorting/filtering and Queue/Content switching (`checks.json`).
- Actual reader and final management UI APK was built from dd70191-equivalent product sources. The later cd202b6 change only serializes content deletion/inventory reads; it was separately covered by the added race regression, final 40-test run and final production/QA compile. No unrelated UI changed after the smoke run.

## SAF query benchmark

Real AndroidDownloadStorageProvider against the validation DocumentsProvider, 300 images, 3 samples, URI maps asserted identical. `storage-benchmark.json`:

| Sample | Batch ms | Previous per-file ms | Batch queries | Previous queries |
| --- | ---: | ---: | ---: | ---: |
| 1 | 143.0617 | 4325.1139 | 4 | 1200 |
| 2 | 8.8204 | 2826.3322 | 4 | 1200 |
| 3 | 13.1684 | 2857.4705 | 4 | 1200 |

Medians: 13.1684 ms versus 2857.4705 ms. Main-thread queries: zero. This measures URI resolution, not whole-page latency or real cloud storage. Batch ran first each round, so the previous algorithm also benefited from warmed filesystem state.

## Frame sample and limits

RSS reader: 8 UI-derived left swipes, reached page 9/300. gfxinfo: 235 total frames, 24 FrameTimeline janky (10.21%), p50 25 ms, p90 38 ms, p95 53 ms, p99 113 ms; see rss-frames.*. Legacy emulator jank/GPU timing counters were inconsistent and are not acceptance metrics. This is one debug emulator sample with no baseline frame comparison; it does not establish physical-device frame smoothness or elimination of all jank.

## Apple validation

First run https://github.com/LittleSurvival/yamibo-app/actions/runs/40014840267 passed simulator/device Kotlin compilation and native regressions; it was superseded before full UI completion. Final-source run https://github.com/LittleSurvival/yamibo-app/actions/runs/40017068693 is pending. It compiles iOS simulator/device targets, runs native regressions, builds an independent app and XCTest target, seeds real local fixtures, and checks novel/thread/three manga/management flows. Manga tests assert rendered fixture pixels and next-page navigation, and export screenshots. Do not claim final iOS simulator acceptance until the run actually passes.
