# Forum reading defaults and Smart Comic

Readers can choose how a thread opens in each forum. Someone following a serialized novel can go straight to its detail page and the author's installments, while keeping all replies visible in a discussion forum. Smart Comic opens the selected chapter as images and finds other chapter threads from the same work. The original reference for the per-forum setting and chapter directory was YamiboX. [YamiboX reading features](https://github.com/Arkalin/YamiboX#功能概览)

## Forum defaults and entry points

| Mode | Opening a thread from the forum or search |
| --- | --- |
| Novel | Open the novel detail page for the thread author. This is the initial preference on recognized novel forums. |
| Forum | Open the thread reader with all authors. This is the initial preference on other forums. |
| Smart Comic | Start with the thread the reader clicked and its author. Show that chapter while looking up related threads in the same forum. |

Readers can change the persistent default in settings or from the forum. Switching modes inside a reader applies to that opening. An explicit chapter or post link takes precedence over the work's last-read chapter. Only **Continue reading** from a saved work or history should take the reader to a different chapter. Changing the forum default preserves existing downloads and their author scope. [Forum destinations](../../composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/forum/ForumReadingModes.kt) · [Per-forum defaults](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/settings/AppSettingsRepository.kt)

## Implementation and evidence

The feature branch is `stable/v0.1.0/d211f9a-forum-reading-modes`. Product commit `e953b60` matches the 56 product-file changes used to build Android snapshot `87879e95`. The branch retains the original public history through `395b007`. The six-direction Android checks, storage recovery, local backup compatibility and performance measurements each identify their tested build in the [validation report](forum-reading-modes-validation.md#功能分支交付狀態).

| Area | Implemented and checked | Outstanding |
| --- | --- | --- |
| Chapter directory | Open the selected chapter immediately. Sort numbered and split chapters by number and part, compilations by their first chapter, and unnumbered extras last. Explicit upper, middle and lower chapter parts follow their printed order even when the lower part was posted first. Reversing the display preserves chapter identity. A partial search preserves known members. A catalog becomes complete only after a consistent search covers its known membership. | Real-site samples spanning multiple search pages, author continuations on later post pages, account changes and conflicting matches. |
| Downloads | The library groups sources by TID, stores each PID once per account and thread, and shares image bytes by SHA-256. Old loose files and CBZ packages remain readable and can be imported offline. Batch conversion has an opt-in damaged-CBZ cleanup policy. In the reader, Reload on a confirmed corrupt archive salvages readable content, retires only that archive, then refetches and saves the failed image in its original position. The requested failure UI is the vertical image placeholder from tag 12: image icon, title, cause, source URL and Reload. Both network and local failures use this layout. A failed HTTP request displays the reported status. An unreadable CBZ displays its local cause. A new RSS reference to a complete page writes one manifest, with zero page fetches, image transfers or body archive writes. Editing a saved page updates the counts for overlapping sources. Queue entries, prefetched pages and pending RSS/tag metadata are scoped by account. Regression tests cover switching accounts during inventory, reopening the cache, and two accounts requesting different bodies for the same TID. | Transfer savings across different attachment URLs with independently verified revision and rendition, and long-thread SAF costs, still require evidence. [Migration and reader verification](forum-reading-modes-validation.md#shared-library-verification) |
| Reading position | Map positions by thread ID (TID), post ID (PID), author scope and image occurrence. Save the last chapter and read status for each work. Preserve the source position when the target mode cannot display it. | The November 25 Android candidate passed all six directions, both offline cold starts and persisted round trips. Android also passed fast page/chapter changes and two-device AppSync with a controlled journal transport. The continuous-scroll offset cases and their build-specific outcomes are in the validation report. The current iOS matrix is deferred. |
| Opening speed | Coalesce in-flight page reads with matching scopes. Display the current page before preloading its neighbors. Opening one downloaded page among 10,000 unrelated records required one page lookup and zero library enumerations in a repository test. | In 39 complete Android pairs against tag 12, median tap to visible body increased by 81.8 ms and median tap to first visible swipe by 75.7 ms. The +1 ms gate remains open. The full local, large-library and iOS matrices remain outstanding. |

An Android build (`7376834`) cold-opened the same real 31-image download in all three modes in airplane mode. On iPhone, a controlled first-open comparison reproduced the third-post bug on `90c8780` in three of six cases. Product `d10f7dca` waits until the reader can display its first body entry before adding the page-end actions. The same six UI cases then passed. The fixed product also passed 20/20 Android shared tests, 9/9 iOS native tests, the Android debug build and the iOS Simulator compile. [First-open regression and evidence](forum-reading-modes-validation.md#iphone-first-open-regression) · [Full acceptance matrix](forum-reading-modes-validation.md)

A separate iPhone run verified the actual Default and Default Dark Yamibo palettes. In both palettes, Forum and Novel opened at floor 1 and Smart Comic displayed the first and second local images in order. [Six passing UI cases and screenshots](https://github.com/LittleSurvival/yamibo-app/actions/runs/45164053906)

The current Android worktree preserves confirmed comic chapters through interrupted and truncated searches. It binds in-flight download writes to the account and storage selection that issued the request, including when a user switches away and back. Legacy loose images carry their original source identity so the second appearance of an image on one floor can retain its position in another downloaded copy. Android downloads from the three normal entry points reuse the same three image files across five placements. Extending the comic download to the full forum scope stored one additional reply and issued zero further image requests in the verified fixture. Reader performance is timed from the input through physical presentation. [Android verification and commands](forum-reading-modes-validation.md#2026-11-25-android-修復與驗收腳本)

The November 25 Android snapshot `51b5a34f` passed 72 JVM tests. The `82bc9c0b` QA APK created and reopened downloads from all three normal entry points. The later `56c1cfcd` QA APK reopened those downloads across all six directions, both for offline cold opens and warm round trips. It also created a new novel download, reopened it offline and retained simplified Chinese across process restart. A fresh, authorized reader image response eliminated all three follow-up transfers when the reader tapped Download. An explicit retry of a damaged saved image still made one request and restored the verified bytes. Four full-page HTTP 503 trials covered both themes and both entry routes. Retry requested page 3 once, then history retained that page and a visible PID. [Afternoon Android evidence](forum-reading-modes-validation.md#11-月-25-日下午-android-驗證)

## What a download covers

The current thread-page key contains a TID, forum page number and optional author ID. An absent author ID means all authors. A specific author ID means a filtered view. Both views reference the same PID bodies on disk. Each view keeps its own page membership, floor numbers and order.

| Content already saved | Available offline | Needed to extend coverage |
| --- | --- | --- |
| All-author page with images | Forum can show the saved page. Novel can show known posts by the author. Smart Comic can show the chapter images present. | Missing posts, pages or images. Mark an entire thread or author range complete only when its coverage is known. |
| Author-only page with images | Novel, Smart Comic and Forum's author-filtered view can reuse the saved PIDs. | Other authors' replies and the original all-author pagination. Page 2 of an author-filtered thread can cover different posts from page 2 of the full thread. |
| Smart Comic, RSS or tag chapter | New packages include the source page, posts and image order. Legacy packages with only images keep their original reading entry. | Text readers need the post body, identity and pagination. An online retry should fetch just the missing resources. |

Use the existing download queue, storage location, manifests and retry operations. Check that the manifest, source page and files can be read before promising offline availability. Opening a saved download should use durable local content even with an empty ordinary cache. In Forum and Novel, keep the existing page and whole-thread actions and show their author scope. For Smart Comic, **This chapter** means the active thread. **Entire work** uses the most recent confirmed complete catalog and shows both total and already available chapters. Offer known chapters with their actual coverage after a partial search. Allow newly discovered chapters to be added later. [Download implementation](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/DownloadRepositoryImpl.kt)

Use the same image extraction order for online reading and downloads. For each occurrence retain its PID, attachment ID or normalized source, and the occurrence within that post. Two appearances of the same image must remain two reading positions. Reuse protected resources only within the same access scope. Verify lazy-loaded images, attachments, emoticons, posts containing repeated images, and authors posting across three floors or multiple pages. Completeness depends on that verified range. [Reader image extraction](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/thread/ReaderImages.kt)

Saved work membership must outlive the signed-in account's two-hour search cache. Persist the work identity, forum, account scope, member TIDs and last-read TID. Preserve known membership when titles or attribution change, earlier chapters appear, or search yields a conflicting work. Manual refresh updates the directory. Readers must still be able to open a saved chapter if the refresh fails. How readers correct a mistaken match remains an open design question.

### One offline library

The agreed design gives each thread or chapter one library entry per TID within the same account and access scope. Forum, Novel, Smart Comic, RSS and tag entries all open that item. Store one current body per post ID (PID). All-author pages, author-only pages and chapter lists keep ordered references to the PIDs they cover. Adding another source records its interest in the item. If its coverage is already known and complete, adding it requires zero body or image downloads. Expanding to all replies can require fetching a forum page to discover missing PIDs.

One PID can contain several images, and different PIDs can use the same image. Use a verified attachment identity and rendition, or a known source for that revision, to reuse the file or join an in-flight download. For previously unrelated URLs, compare a digest of the received bytes before retaining a second file. The first transfer of an unknown alias can be necessary. Store the order of image references within each PID so an intentional repeat keeps its reading position. Count each retained image payload once in storage usage.

A refresh publishes a verified edit to the same PID for every entry point. Revalidate an image when its source indicates a revision change, even if its URL stays the same. An older response arriving late or a partial response must preserve the last complete revision. If two legacy copies disagree and their edit order is unknown, preserve both originals during migration until the source confirms the revision. Preserve existing history and archive links while migrating. Release superseded files after their replacements are verified and the final reference is gone. Removing an RSS subscription's download leaves the post readable through any remaining download reference.

Earlier standalone and RSS packages could disagree. The shared library rejects a stale edit and applies a verified newer PID to overlapping saved claims. Conflicting old packages remain available for review. The validation plan specifies how to measure transfers, visible entries and actual stored bytes for the shared library. [PID and image acceptance cases](forum-reading-modes-validation.md#pid-與圖片去重驗收)

### Storage implementation and remaining targets

Keep the existing download queue and its thread, RSS and tag task keys. They identify who requested which pages. All tasks call the same persistence path. A verified source hit returns a file reference. A cache miss can reuse a legacy image or fetch it. Previously unrelated URLs can require independent transfers before their SHA-256 digests are known. Adding a source for already verified, healthy coverage writes only its claim. [Current fetch and pack path](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/DownloadRepositoryImpl.kt)

**Source map** (package prefix `me/thenano/yamibo/yamibo_app` omitted).

```text
shared/src/
  commonMain/kotlin/.../repository/
    download/DownloadRepositoryImpl.kt      queue, retry, update, delete and reader entry points
    download/OfflineLibrary.kt              new, shared manifest format, merge and publish rules
    download/DownloadModels.kt              library item and versioned local image reference
    download/DownloadedContentPresentation.kt   one row per TID and unique image usage
    download/OfflineFiles.kt                verified files and path-based storage adapter
    download/DownloadStorageProvider.kt     storage contract and legacy access
    thread/ReaderImages.kt                  stable source identity for reading progress
  commonMain/sqldelight/.../
    45.sqm                                 next additive migration on this branch
    ReaderDownload.sq                      indexed coverage, ownership and image lookups
  androidMain/kotlin/.../repository/download/AndroidDownloadStorageProvider.kt
  iosMain/kotlin/.../repository/download/IOSDownloadStorageProvider.kt
composeApp/src/commonMain/kotlin/.../profile/download/
  DownloadQueueScreen.kt                    show shared items separately from queue tasks
  DownloadedReaderDialog.kt                 open a selected TID, scope and page in any supported mode
composeApp/src/commonMain/kotlin/.../thread/image/ImageViewer.kt
                                           per-image offline failure and repair
```

**On disk.** The selected folder remains sufficient to rebuild the local index. For example:

```text
YamiboDownloads/library/<account-id>/
  threads/140/manifest.json          current text generation, source claims, coverage and image references
  threads/140/posts-<batch-id>.zip   a body archive for a batch of PIDs; manifest maps each PID to its active batch
  images/ab/<sha256>                 original verified image bytes, shared across TIDs in this scope
```

Within a selected folder, each account has its own directory (including the guest scope). Author-only and all-author pagination are separate coverage maps inside it. A map retains the page header, navigation, view-specific floor metadata and ordered PIDs, enough to reconstruct the existing `ThreadPage`. The app's SQLDelight database indexes the selected folder, scope, TID, author filter, page and PID, image source aliases, and live digest references. It stores no second body or image payload. The reader uses the target page's PID list and the existing seekable ZIP support to read the required text entries. A known image opens directly by its indexed storage locator. On Android retain the document URI for direct SAF access and repair a stale locator on demand.

**Three critical paths.** The following Kotlin illustrates the target contracts. Source URL aliases and digest storage are implemented. The image DTO does not expose an independent attachment revision or rendition key. A missing validator cannot establish that two different URLs serve the same revision. `scope` captures the selected storage key, account and access context at enqueue time. Check it again before publishing after a folder or account switch.

```kotlin
// A claim records which source wants this coverage, including before a download completes.
val coverage = library.registerClaim(scope, claim, pageKey)
if (coverage.isComplete) return
pendingPages.read(scope to pageKey) {
    library.fillMissing(scope, pageKey)  // recheck, fetch missing data, publish before returning
}
```

Check coverage and attach each caller's claim under the same writer boundary as deletion. The shared job includes publication so another caller cannot start a second fetch between the response and the commit. Its page request key includes TID, author filter, page and access scope. A normal download uses this fast path. An explicit refresh checks the source revision. If only an image is missing, retry that resource from the saved page.

```kotlin
return pendingImages.read(scope to image.versionedSourceKey) {
    val hit = library.usableImage(scope, image.versionedSourceKey)
    if (hit != null) return@read hit
    val bytes = imageFetcher.fetch(image.url)  // existing validation and bounded concurrency
    val digest = sha256(bytes)
    val file = storage.putImageIfAbsent(scope, digest, bytes)
    library.rememberAlias(scope, image.versionedSourceKey, file)
    file
}
```

Known attachment, rendition and revision aliases share the request. For other URLs use the existing normalized source with its freshness evidence. A confirmed edit invalidates an old alias even at the same URL. When the server provides no validator, an explicit refresh can refetch to establish the current bytes. `putImageIfAbsent` serializes installation per scope and digest, so concurrent downloads of two unknown aliases can retain only one verified file. The image reference keeps both the source identity used by history and the digest used by storage. In the example `PID 1402 → [A, B, A]`, the two A positions share one file.

```kotlin
// Images and any new text pack are verified before the durable manifest switches.
withThreadWriter(scope, tid) {
    val next = mergeWithCurrentManifest(staged)  // retain only live claims and verified changes
    markIndexDirty(scope, tid)
    storage.publishVerifiedManifest(next)
    readerIndex.transaction { replaceThreadIndexAndClearDirty(next) }
}
```

Prepare heavy I/O outside the writer. If the base generation changed, rebase or restage before publication. Compare verified edit revisions and keep the last complete copy on failed refresh. A first partial download publishes its actual missing list. The portable manifest is authoritative after a crash; an indexed dirty TID is repaired from its last valid manifest before use. Reuse the platforms' existing previous/current recovery protocol. A claim-only change reuses the same text generation. Batch whole-thread body updates so each incoming page does not repack all preceding pages. Images are never repacked on a text edit. The cost to measure here is rewriting a long thread's text and the first open of a shared image on Android SAF.

**Delivery order**

1. Add the format, indexed reads and both storage adapters. Keep legacy CBZ reads available through the existing local image scheme. Preserve source identity in reading history. Put product changes on the feature branch and validation code on its matching validation branch.
2. Route download, refresh, retry and removal through the common path. Switch the library list and chooser together. Use indexed live references for byte totals and deletion. Reclaim an unreferenced digest only after publication, with clean indexes, no staged writer or reader using it, and deletion coordinated with new claims. Keep aliases recoverable from manifests.
3. Import existing packages locally by TID in resumable batches away from reader startup. Verify PIDs, digests, old local links and offline reopen before retiring each old package. Image-only legacy packages retain their original entry and progress until their identity can be established. Validate removal, interruption, concurrent sources and independent folder rescan on Android and iOS. The controlled measurements below decide whether this layout meets the latency and storage targets. [Acceptance cases](forum-reading-modes-validation.md#pid-與圖片去重驗收)

## Resume across the three readers

If the chapter author posts images twice in chapter 14 and a reader stops on the third image of the author's second post, switching to Forum or Novel should open that post and image when available. If the reader then stops on someone else's reply that Smart Comic cannot display, Forum keeps the reply position and Smart Comic resumes at its last representable position.

1. **Capture the content being read.** Keep TID, scope, PID and observation order in the existing history repository. Text positions include the block and within-block offset. Image positions include the owning post, resource identity, occurrence and position within the image. Preserve precise offsets per mode. Sorting, loading or refreshing a directory must never count as reading. [History model](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/ReadHistoryRepository.kt)
2. **Resolve the destination.** Honor explicit links first. For ordinary resume, find the most recent representable reading action. Match the image or text block when possible, otherwise open the matching post or its first readable image. Resolve differing page numbers by PID, using the local index offline. If the post falls outside the target scope, use that mode's last valid position. Start at the beginning on a first open. A fallback must preserve the other mode's precise history.
3. **Save before changing chapters.** Capture the outgoing position before navigation, a mode switch, back, or backgrounding. Order asynchronous writes so an older one cannot overwrite a newer position. Selecting a chapter from the directory resumes it. Paging forward across a chapter boundary starts the next chapter at its first image. Paging backward enters the previous chapter at its last image.
4. **Separate position from completion.** Revisiting an earlier image changes the resume position. A completed chapter stays read until explicitly marked unread. Finishing a comic does not finish unread forum replies. Removing a download preserves reading history. Clearing history preserves the downloaded package.

## Reader startup and compatibility

Look up the target thread, scope and PID first. Open only the page or image archive needed for the first frame. Keep full library scans, legacy reindexing, catalog sorting and cross-page searches off the startup path. Show a usable local page before checking for updates. An online novel detail page and its reader should share a completed or in-flight revalidation. An expired Smart Comic directory can refresh while the saved first image is visible.

Coalesce page reads by account, access rights, TID, author scope and page. Share image requests between display and preload. Prioritize the visible image, cancel abandoned chapter preloads after their last consumer leaves, and capture progress before queuing a disk write.

Use additive database changes and restartable index rebuilds. Upgrade legacy manifests and histories on demand, preserving their timestamps, scope, source and original reader keys. If an old image index cannot identify a PID, keep its original mode's position. AppSync and local backups retain the existing reading identity and numeric position. Optional author, floor and image-occurrence fields belong to the operation that wrote that position. A legacy write keeps its original reader precision. Test mixed-version journal and checkpoint replay as well as deletion. Offline availability is device-local. [AppSync data model](../app-sync-cloud-data-model.md)

## Delivery and acceptance

1. **Image order and older packages.** Compare online reading, saved thread pages and RSS/tag packages using repeated images, missing images, attachments, lazy loading and posts spread over several floors. Verify the original reading path for legacy data.
2. **Offline coverage.** Test page, thread, chapter and work downloads on a long thread with different all-author and author-only pagination. Save both scopes, change the forum preference, reopen both offline, then remove one and open the other. Include partial downloads, interrupted refresh, cancellation, duplicate requests and account changes. Measure zero body and image requests when the destination is fully covered.
3. **Reading positions.** For each of the six directed mode switches, test an offline cold open and a position round trip on both platforms (24 cases). Record TID, PID, image occurrence or text block, and request counts. Cover replies unavailable in the destination, text-only posts, fast chapter changes, process restart, continuous and paged layouts, manual unread, deleted posts, renamed works and reversed directories.
4. **Site behavior and compatibility.** Use *DOUBLE HELIX BLOSSOM* for cross-page discovery and new chapters, *青梅竹马BIG LOVE* for compilations and extras, and *少女所不期望的英雄史诗* and *JK⇔杀手* for fractional and split chapters. Check independent forum preferences after restart, similar work titles, deep links, favorites, history, RSS and tag entries. Verify migrations through the current schema, interruption recovery and clearing history across two synced devices.
5. **Platform evidence.** Record each tested build, platform, theme and failure. Exercise the chooser, partially readable items, missing files, failure details and retry in default and dark themes. Keep test harnesses and fixtures on validation branches. The companion report defines request budgets, UI latency measurements and evidence requirements. [Acceptance scenarios](forum-reading-modes-validation.md) · [Test isolation](../test-isolation.md)


On Android, snapshot `24287161` passed all 12 cold-open and round-trip cases. Snapshot `4ddeb2ee` passed the four targeted cases for restoring a forum reply or novel paragraph at a downloaded page boundary. The final `87879e95` build passed 79 shared Android JVM tests. It recovered both a revoked folder grant and a moved download folder, and imported local backups written by the current app and by stock tag 12.

The latest formal comparison with tag 12 exceeded the reader latency budget. Full performance coverage, current iOS validation, live multi-page comic discovery, the HTTP 429 retry deadline and two intermittent Android startup crashes remain open. The earlier controlled two-device AppSync results cover reader data compatibility. Disabling cloud synchronization is a separate branch. [Acceptance results by build](forum-reading-modes-validation.md#功能分支交付狀態)
