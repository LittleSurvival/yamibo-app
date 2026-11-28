# Forum reading defaults and Smart Comic

Readers can choose how a thread opens in each forum. Someone following a serialized novel can go straight to its detail page and the author's installments, while keeping all replies visible in a discussion forum. Smart Comic opens the selected chapter as images and finds other chapter threads from the same work. The original reference for the per-forum setting and chapter directory was YamiboX. [YamiboX reading features](https://github.com/Arkalin/YamiboX#功能概览)

## Forum defaults and entry points

| Mode | Opening a thread from the forum or search |
| --- | --- |
| Novel | Open the novel detail page for the thread author. This is the initial preference on recognized novel forums. |
| Forum | Open the thread reader with all authors. This is the initial preference on other forums. |
| Smart Comic | Start with the thread the reader clicked and its author. Show that chapter while looking up related threads in the same forum. |

Readers can change the persistent default in settings or from the forum. Switching modes inside a reader applies to that opening. An explicit chapter or post link takes precedence over the work's last-read chapter. Only **Continue reading** from a saved work or history should take the reader to a different chapter. Changing the forum default preserves existing downloads and their author scope. [Forum destinations](../../composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/forum/ForumReadingModes.kt) · [Per-forum defaults](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/settings/AppSettingsRepository.kt)

## Smart Comic chapter directory

Show the selected chapter while discovering related threads in its forum. Sort numbered chapters by number, then put explicit upper, middle and lower parts in their printed order. Sort compilations by their first chapter and place unnumbered extras last. For example, `#33+7卷加笔` belongs at chapter 33, while a separate post titled `纸质书第一卷新增内容` belongs with the extras. Reversing the display keeps the current chapter selected.

Saved work membership must outlive the signed-in account's two-hour search cache. Persist the work identity, forum, account scope, member TIDs and last-read TID. Preserve known chapters when titles or attribution change, earlier chapters appear, a search is interrupted or a similar title conflicts. Mark a catalog complete only after a consistent search covers its known membership. Manual refresh updates the directory. Saved chapters remain readable if refresh fails. How readers correct a mistaken match remains an open design question.

Live checks covered cross-page discovery with *DOUBLE HELIX BLOSSOM* and compilation threads with *青梅竹马BIG LOVE*. The author's continuations on later post pages, account changes and conflicting matches still need live checks. The examples for fractional and split chapters are *少女所不期望的英雄史诗* and *JK⇔杀手*.

## What a download covers

The current thread-page key contains a TID, forum page number and optional author ID. An absent author ID means all authors. A specific author ID means a filtered view. Both views reference the same PID bodies on disk. Each view keeps its own page membership, floor numbers and order.

| Content already saved | Available offline | Needed to extend coverage |
| --- | --- | --- |
| All-author page with images | Forum can show the saved page. Novel can show known posts by the author. Smart Comic can show the chapter images present. | Missing posts, pages or images. Mark an entire thread or author range complete only when its coverage is known. |
| Author-only page with images | Novel, Smart Comic and Forum's author-filtered view can reuse the saved PIDs. | Other authors' replies and the original all-author pagination. Page 2 of an author-filtered thread can cover different posts from page 2 of the full thread. |
| Smart Comic, RSS or tag chapter | New packages include the source page, posts and image order. Legacy packages with only images keep their original reading entry. | Text readers need the post body, identity and pagination. An online retry should fetch just the missing resources. |

Use the existing download queue, storage location, manifests and retry operations. Check that the manifest, source page and files can be read before promising offline availability. Opening a saved download should use durable local content even with an empty ordinary cache. In Forum and Novel, keep the existing page and whole-thread actions and show their author scope. For Smart Comic, **This chapter** means the active thread. **Entire work** uses the most recent confirmed complete catalog and shows both total and already available chapters. Offer known chapters with their actual coverage after a partial search. Allow newly discovered chapters to be added later. [Download implementation](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/DownloadRepositoryImpl.kt)

Use the same image extraction order for online reading and downloads. For each occurrence retain its PID, attachment ID or normalized source, and the occurrence within that post. Two appearances of the same image must remain two reading positions. Reuse protected resources only within the same access scope. Apply the same rules to lazy-loaded images, attachments, emoticons and posts on later floors or pages. Completeness depends on the range covered. [Reader image extraction](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/thread/ReaderImages.kt)

Network and local image failures use the vertical image placeholder from tag 12. Show the image icon, title, HTTP status or local cause, source URL and **Reload**. On a confirmed corrupt CBZ, Reload salvages readable content, retires that archive, then refetches and saves the failed image in its original position. Bulk conversion offers an opt-in setting to remove damaged CBZ files.

### One offline library

The agreed design gives each thread or chapter one library entry per TID within the same account and access scope. Forum, Novel, Smart Comic, RSS and tag entries all open that item. Store one current body per post ID (PID). All-author pages, author-only pages and chapter lists keep ordered references to the PIDs they cover. Adding another source records its interest in the item. If its coverage is already known and complete, adding it requires zero body or image downloads. Expanding to all replies can require fetching a forum page to discover missing PIDs.

One PID can contain several images, and different PIDs can use the same image. Use a verified attachment identity and rendition, or a known source for that revision, to reuse the file or join an in-flight download. For previously unrelated URLs, compare a digest of the received bytes before retaining a second file. The first transfer of an unknown alias can be necessary. Store the order of image references within each PID so an intentional repeat keeps its reading position. Count each retained image payload once in storage usage.

A refresh publishes a verified edit to the same PID for every entry point. Revalidate an image when its source indicates a revision change, even if its URL stays the same. An older response arriving late or a partial response must preserve the last complete revision. If two legacy copies disagree and their edit order is unknown, preserve both originals during migration until the source confirms the revision. Preserve existing history and archive links while migrating. Release superseded files after their replacements are verified and the final reference is gone. Removing an RSS subscription's download leaves the post readable through any remaining download reference.

Earlier standalone and RSS packages could disagree. The shared library rejects a stale edit and applies a verified newer PID to overlapping saved claims. Conflicting old packages remain available for review. Editing a saved page updates the coverage counts for all sources that reference it.

### Storage and migration

The existing thread, RSS and tag queue keys identify the source of a download. The portable manifest records page coverage per source, ordered PIDs and image references. SQLDelight indexes the selected folder, account, thread and author scope. Android uses the stored SAF document URI to open a known file. [Download repository](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/DownloadRepositoryImpl.kt) · [Library index](../../shared/src/commonMain/sqldelight/me/thenano/yamibo/yamibo_app/ReaderDownload.sq)

```text
YamiboDownloads/library/<account>/threads/<tid>/manifest.json
YamiboDownloads/library/<account>/threads/<tid>/posts-<batch-id>.zip
YamiboDownloads/library/<account>/images/<prefix>/<sha256>
```

Image aliases established by a previous transfer can reuse the saved bytes. For two previously unseen URLs, the current SDK supplies no image revision or pixel dimensions that prove a match. Verify the bytes before sharing a file, and keep the source URL and occurrence for reading history. Publish verified files before switching the manifest and reclaim old files only after their last reference is gone. A failed update preserves the last complete version. [Manifest and publication rules](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/download/OfflineLibrary.kt)

Migrate older loose files and CBZ packages locally by TID, outside reader startup. Keep their entries and reading positions usable until the new PIDs, files and image occurrences have been verified. [Original source map and implementation examples](https://github.com/LittleSurvival/yamibo-app/blob/3d5f66a61e98fa0f9e52cded7f2134b74c7ff8e9/dev-docs/planning/forum-reading-modes.md)

## Resume across the three readers

If the chapter author posts images twice in chapter 14 and a reader stops on the third image of the author's second post, switching to Forum or Novel should open that post and image when available. If the reader then stops on someone else's reply that Smart Comic cannot display, Forum keeps the reply position and Smart Comic resumes at its last representable position.

1. **Capture the content being read.** Keep TID, scope, PID and observation order in the existing history repository. Text positions include the block and within-block offset. Image positions include the owning post, resource identity, occurrence and position within the image. Preserve precise offsets per mode. Sorting, loading or refreshing a directory must never count as reading. [History model](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/ReadHistoryRepository.kt)
2. **Resolve the destination.** Honor explicit links first. For ordinary resume, find the most recent representable reading action. Match the image or text block when possible, otherwise open the matching post or its first readable image. Resolve differing page numbers by PID, using the local index offline. If the post falls outside the target scope, use that mode's last valid position. Start at the beginning on a first open. A fallback must preserve the other mode's precise history.
3. **Save before changing chapters.** Capture the outgoing position before navigation, a mode switch, back, or backgrounding. Order asynchronous writes so an older one cannot overwrite a newer position. Selecting a chapter from the directory resumes it. Paging forward across a chapter boundary starts the next chapter at its first image. Paging backward enters the previous chapter at its last image.
4. **Separate position from completion.** Revisiting an earlier image changes the resume position. A completed chapter stays read until explicitly marked unread. Finishing a comic does not finish unread forum replies. Removing a download preserves reading history. Clearing history preserves the downloaded package.

## Reader startup and compatibility

Look up the target thread, scope and PID first. Open only the page or image archive needed for the first frame. Keep full library scans, legacy reindexing, catalog sorting and cross-page searches off the startup path. Show a usable local page before checking for updates. Show page-end actions once the first body entry can be displayed. An online novel detail page and its reader should share a completed or in-flight revalidation. An expired Smart Comic directory can refresh while the saved first image is visible.

Coalesce page reads by account, access rights, TID, author scope and page. Share image requests between display and preload. Prioritize the visible image, cancel abandoned chapter preloads after their last consumer leaves, and capture progress before queuing a disk write. Compare tap to the first presented body against tag 12 on the same device and thread, including the transition. The allowed increase in the paired median is 16.7 ms at 60 Hz. Record the first visible swipe response and frame gaps during entry, scrolling, revealing the controls by tapping the body, and opening a catalog. Measure the controls from their first visible motion until they settle. Record tap recognition separately. See the [everyday acceptance checks](forum-reading-modes-validation.md) for the remaining targets.

Use additive database changes and restartable index rebuilds. Upgrade legacy manifests and histories on demand, preserving their timestamps, scope, source and original reader keys. If an old image index cannot identify a PID, keep its original mode's position. AppSync and local backups retain the existing reading identity and numeric position. Optional author, floor and image-occurrence fields belong to the operation that wrote that position. A legacy write keeps its original reader precision. Journal replay, checkpoint replay and deletion must preserve these semantics across versions. Offline availability is device-local. Disabling cloud synchronization is a separate branch. [AppSync data model](../app-sync-cloud-data-model.md)

## Validation status

For completed case counts and open acceptance items, see the [reader validation plan](forum-reading-modes-validation.md).
