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

The feature branch is `stable/v0.1.0/d211f9a-forum-reading-modes`. The original interoperability baseline was `9b0e8d3`. The following results cover the first implementation through `90c8780`.

| Area | Implemented and checked | Outstanding |
| --- | --- | --- |
| Chapter directory | Open the selected chapter immediately. Sort numbered and split chapters by number and part, compilations by their first chapter, and unnumbered extras last. Reversing the display preserves chapter identity. A partial search preserves known members. A catalog becomes complete only after a consistent search covers its known membership. | Real-site samples spanning multiple search pages, author continuations on later post pages, account changes and conflicting matches. |
| Downloads | New packages save the source text, post IDs, author scope, original page and ordered images. The chooser offers the readers that can use that content. An initial partial download keeps readable content and retries missing images. A failed refresh keeps the last complete version. Legacy image-only packages still open in the comic reader. | Different sources can still write separate CBZ files. Physical archive sharing, byte accounting and cleanup after the last reference remain open. |
| Reading position | Map positions by thread ID (TID), post ID (PID), author scope and image occurrence. Save the last chapter and read status for each work. Preserve the source position when the target mode cannot display it. | Six directed switches, each tested for offline cold start and position round trip on Android and iOS. Rapid transitions and two-device AppSync also need verification. |
| Opening speed | Coalesce in-flight page reads with matching scopes. Display the current page before preloading its neighbors. Opening one downloaded page among 10,000 unrelated records required one page lookup and zero library enumerations in a repository test. | Measure outbound requests, the first correct content frame and the first responsive gesture under controlled conditions on both platforms. |

An Android build (`7376834`) cold-opened the same real 31-image download in all three modes in airplane mode. On iPhone, a controlled first-open comparison reproduced the third-post bug on `90c8780` in three of six cases. Product `d10f7dca` waits until the reader can display its first body entry before adding the page-end actions. The same six UI cases then passed. The fixed product also passed 20/20 Android shared tests, 9/9 iOS native tests, the Android debug build and the iOS Simulator compile. [First-open regression and evidence](forum-reading-modes-validation.md#iphone-first-open-regression) · [Full acceptance matrix](forum-reading-modes-validation.md)

A separate iPhone run verified the actual Default and Default Dark Yamibo palettes. In both palettes, Forum and Novel opened at floor 1 and Smart Comic displayed the first and second local images in order. [Six passing UI cases and screenshots](https://github.com/LittleSurvival/yamibo-app/actions/runs/45164053906)

## What a download covers

A thread-page key contains a TID, forum page number and optional author ID. An absent author ID means all authors. A specific author ID means a filtered copy. A reader can have both copies on disk. The mode controls presentation, while the saved scope determines which posts are available.

| Content already saved | Available offline | Needed to extend coverage |
| --- | --- | --- |
| All-author page with images | Forum can show the saved page. Novel can show known posts by the author. Smart Comic can show the chapter images present. | Missing posts, pages or images. Mark an entire thread or author range complete only when its coverage is known. |
| Author-only page with images | Novel, Smart Comic and Forum's author-filtered view can reuse the saved PIDs. | Other authors' replies and the original all-author pagination. Page 2 of an author-filtered thread can cover different posts from page 2 of the full thread. |
| Smart Comic, RSS or tag chapter | New packages include the source page, posts and image order. Legacy packages with only images keep their original reading entry. | Text readers need the post body, identity and pagination. An online retry should fetch just the missing resources. |

Use the existing download queue, storage location, manifests and retry operations. Check that the manifest, source page and files can be read before promising offline availability. Opening a saved download should use durable local content even with an empty ordinary cache. In Forum and Novel, keep the existing page and whole-thread actions and show their author scope. For Smart Comic, **This chapter** means the active thread. **Entire work** uses the most recent confirmed complete catalog and shows both total and already available chapters. Offer known chapters with their actual coverage after a partial search. Allow newly discovered chapters to be added later. [Download implementation](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/DownloadRepositoryImpl.kt)

Use the same image extraction order for online reading and downloads. For each occurrence retain its PID, attachment ID or normalized source, and the occurrence within that post. Two appearances of the same image must remain two reading positions. Reuse protected resources only within the same access scope. Verify lazy-loaded images, attachments, emoticons, posts containing repeated images, and authors posting across three floors or multiple pages. Completeness depends on that verified range. [Reader image extraction](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/thread/ReaderImages.kt)

Saved work membership must outlive the signed-in account's two-hour search cache. Persist the work identity, forum, account scope, member TIDs and last-read TID. Preserve known membership when titles or attribution change, earlier chapters appear, or search yields a conflicting work. Manual refresh updates the directory. Readers must still be able to open a saved chapter if the refresh fails. How readers correct a mistaken match remains an open design question.

Physical archive sharing across work, thread, tag and RSS downloads is a later storage change. Existing archive URIs identify a task and generation. The design calls for validating a refreshed package before publishing its references, retaining completed data on cancellation, and deleting the physical file only when its final reference disappears. Test concurrent requests, retries and removing one of two references before enabling that behavior.

One source-selection question is deferred. If a standalone thread page and an RSS or tag chapter have the same TID, page and author scope but were saved at different times, the chooser can resolve the standalone copy first. The first-open position tests below cover a single saved copy.

## Resume across the three readers

If the chapter author posts images twice in chapter 14 and a reader stops on the third image of the author's second post, switching to Forum or Novel should open that post and image when available. If the reader then stops on someone else's reply that Smart Comic cannot display, Forum keeps the reply position and Smart Comic resumes at its last representable position.

1. **Capture the content being read.** Keep TID, scope, PID and observation order in the existing history repository. Text positions include the block and within-block offset. Image positions include the owning post, resource identity, occurrence and position within the image. Preserve precise offsets per mode. Sorting, loading or refreshing a directory must never count as reading. [History model](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/ReadHistoryRepository.kt)
2. **Resolve the destination.** Honor explicit links first. For ordinary resume, find the most recent representable reading action. Match the image or text block when possible, otherwise open the matching post or its first readable image. Resolve differing page numbers by PID, using the local index offline. If the post falls outside the target scope, use that mode's last valid position. Start at the beginning on a first open. A fallback must preserve the other mode's precise history.
3. **Save before changing chapters.** Capture the outgoing position before navigation, a mode switch, back, or backgrounding. Order asynchronous writes so an older one cannot overwrite a newer position. Selecting a chapter from the directory resumes it. Paging forward across a chapter boundary starts the next chapter at its first image. Paging backward enters the previous chapter at its last image.
4. **Separate position from completion.** Revisiting an earlier image changes the resume position. A completed chapter stays read until explicitly marked unread. Finishing a comic does not finish unread forum replies. Removing a download preserves reading history. Clearing history preserves the downloaded package.

## Reader startup and compatibility

Look up the target thread, scope and PID first. Open only the page or image archive needed for the first frame. Keep full library scans, legacy reindexing, catalog sorting and cross-page searches off the startup path. Show a usable local page before checking for updates. An online novel detail page and its reader should share a completed or in-flight revalidation. An expired Smart Comic directory can refresh while the saved first image is visible.

Coalesce page reads by account, access rights, TID, author scope and page. Share image requests between display and preload. Prioritize the visible image, cancel abandoned chapter preloads after their last consumer leaves, and capture progress before queuing a disk write.

Use additive database changes and restartable index rebuilds. Upgrade legacy manifests and histories on demand, preserving their timestamps, scope, source and original reader keys. If an old image index cannot identify a PID, keep its original mode's position. For AppSync, first reconstruct supported positions from existing thread, image, RSS and tag history. Verify old-client registry, journal, checkpoint and deletion behavior before introducing a new synced field. Offline availability is device-local. [AppSync data model](../app-sync-cloud-data-model.md)

## Delivery and acceptance

1. **Image order and older packages.** Compare online reading, saved thread pages and RSS/tag packages using repeated images, missing images, attachments, lazy loading and posts spread over several floors. Verify the original reading path for legacy data.
2. **Offline coverage.** Test page, thread, chapter and work downloads on a long thread with different all-author and author-only pagination. Save both scopes, change the forum preference, reopen both offline, then remove one and open the other. Include partial downloads, interrupted refresh, cancellation, duplicate requests and account changes. Measure zero body and image requests when the destination is fully covered.
3. **Reading positions.** For each of the six directed mode switches, test an offline cold open and a position round trip on both platforms (24 cases). Record TID, PID, image occurrence or text block, and request counts. Cover replies unavailable in the destination, text-only posts, fast chapter changes, process restart, continuous and paged layouts, manual unread, deleted posts, renamed works and reversed directories.
4. **Site behavior and compatibility.** Use *DOUBLE HELIX BLOSSOM* for cross-page discovery and new chapters, *青梅竹马BIG LOVE* for compilations and extras, and *少女所不期望的英雄史诗* and *JK⇔杀手* for fractional and split chapters. Check independent forum preferences after restart, similar work titles, deep links, favorites, history, RSS and tag entries. Verify migrations through the current schema, interruption recovery and clearing history across two synced devices.
5. **Platform evidence.** Record each tested build, platform, theme and failure. Exercise the chooser, partially readable items, missing files, failure details and retry in default and dark themes. Keep test harnesses and fixtures on validation branches. The companion report defines request budgets, UI latency measurements and evidence requirements. [Acceptance scenarios](forum-reading-modes-validation.md) · [Test isolation](../test-isolation.md)
