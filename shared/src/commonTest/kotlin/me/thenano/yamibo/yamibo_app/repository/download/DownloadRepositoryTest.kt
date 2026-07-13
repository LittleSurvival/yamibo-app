package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.Tags
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.PostImage
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.ThreadInfo
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import io.github.littlesurvival.dto.value.TagId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRepositoryTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun keyIsStableAndSeparatesAuthorMode() {
        assertEquals("thread_42_page_3_author_all", ThreadPageDownloadKey(42, 3).stableId)
        assertEquals("thread_42_page_3_author_7", ThreadPageDownloadKey(42, 3, 7).stableId)
        assertEquals("thread_42_", ThreadPageDownloadKey(42, 3, 7).threadPrefix)
    }

    @Test
    fun downloadedPageUsesLocalImageUrisInImagesAndHtml() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val source = "https://bbs.yamibo.com/data/attachment/forum/a.jpg"
        val storage = FakeStorage()
        val page = page(42, 1, 1, source)
        storage.seed(
            key,
            ThreadPageDownloadManifest(
                key = key,
                title = "title",
                downloadedAt = 1,
                sourceTotalPages = 1,
                pageKind = DownloadPageKind.LastAtDownloadTime,
                images = listOf(DownloadedImage(source, "0000.jpg", 3)),
            ),
            page,
            mapOf("0000.jpg" to "content://download/0000.jpg"),
        )
        val repository = repository(FakeThreadRepository(), storage)

        val downloaded = assertNotNull(repository.getDownloadedPage(key))

        assertEquals("content://download/0000.jpg", downloaded.posts.single().images.single().url)
        assertTrue(downloaded.posts.single().contentHtml.contains("content://download/0000.jpg"))
    }

    @Test
    fun completeThreadExpandsAllPagesAndMarksLastPage() = runBlocking {
        val storage = FakeStorage()
        val thread = FakeThreadRepository(
            pages = mutableMapOf(
                1 to YamiboResult.Success(page(42, 1, 3)),
                2 to YamiboResult.Success(page(42, 2, 3)),
                3 to YamiboResult.Success(page(42, 3, 3)),
            ),
        )
        val repository = repository(thread, storage)

        repository.enqueueThread(ThreadId(42), "title", null).getOrThrow()
        awaitStatus(repository, ThreadPageDownloadKey(42, 3), DownloadStatus.Downloaded)

        assertEquals(setOf(1, 2, 3), storage.manifests.values.map { it.key.page }.toSet())
        assertEquals(DownloadPageKind.Normal, storage.manifests.getValue(ThreadPageDownloadKey(42, 2)).pageKind)
        assertEquals(
            DownloadPageKind.LastAtDownloadTime,
            storage.manifests.getValue(ThreadPageDownloadKey(42, 3)).pageKind,
        )
        assertEquals(1, thread.fetchCounts[1])
    }

    @Test
    fun threadExceptLastPageQueuesOnlyStablePages() = runBlocking {
        val storage = FakeStorage()
        val thread = FakeThreadRepository(
            pages = mutableMapOf(
                1 to YamiboResult.Success(page(42, 1, 3)),
                2 to YamiboResult.Success(page(42, 2, 3)),
            ),
        )
        val repository = repository(thread, storage)

        repository.enqueueThreadExceptLastPage(ThreadId(42), "title", null).getOrThrow()
        awaitStatus(repository, ThreadPageDownloadKey(42, 2), DownloadStatus.Downloaded)

        assertEquals(setOf(1, 2), storage.manifests.keys.map { it.page }.toSet())
        assertEquals(DownloadStatus.NotDownloaded, repository.getStatus(ThreadPageDownloadKey(42, 3)))
    }

    @Test
    fun threadExceptLastPageRejectsSinglePageThread() = runBlocking {
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(1 to YamiboResult.Success(page(42, 1, 1))),
            ),
            FakeStorage(),
        )

        val result = repository.enqueueThreadExceptLastPage(ThreadId(42), "title", null)

        assertTrue(result.isFailure)
        Unit
    }

    @Test
    fun restoresDownloadedManifestsWhenQueueFileIsMissing() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val storage = FakeStorage()
        storage.seed(
            key,
            ThreadPageDownloadManifest(key, "saved", 1, 1, DownloadPageKind.LastAtDownloadTime),
            page(42, 1, 1),
        )

        val repository = repository(FakeThreadRepository(), storage)
        repeat(100) {
            if (repository.queue.value.any { it.key == key }) return@repeat
            delay(10)
        }

        assertEquals(DownloadStatus.Downloaded, repository.getStatus(key))
        assertTrue(repository.queue.value.any { it.key == key && it.status == DownloadStatus.Downloaded })
    }

    @Test
    fun restoresInterruptedQueueAndContinuesDownload() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val storage = FakeStorage()
        storage.seedQueue(
            listOf(DownloadQueueEntry(key, "restored", DownloadStatus.Downloading)),
        )
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(1 to YamiboResult.Success(page(42, 1, 1))),
            ),
            storage,
        )

        awaitStatus(repository, key, DownloadStatus.Downloaded)

        assertNotNull(storage.manifests[key])
        Unit
    }

    @Test
    fun pruneExpiredQueueEntriesOnlyRemovesOldTerminalEntries() {
        val now = DOWNLOAD_QUEUE_TERMINAL_RETENTION_MS * 2
        val expired = now - DOWNLOAD_QUEUE_TERMINAL_RETENTION_MS - 1
        val recent = now - 1_000L
        val entries = listOf(
            DownloadQueueEntry(ThreadPageDownloadKey(1, 1), "downloaded", DownloadStatus.Downloaded, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(2, 1), "failed", DownloadStatus.Failed, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(3, 1), "queued", DownloadStatus.Queued, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(4, 1), "downloading", DownloadStatus.Downloading, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(5, 1), "paused", DownloadStatus.Paused, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(6, 1), "update", DownloadStatus.UpdateAvailable, updatedAt = expired),
            DownloadQueueEntry(ThreadPageDownloadKey(7, 1), "recent", DownloadStatus.Failed, updatedAt = recent),
        )

        val pruned = pruneExpiredQueueEntries(entries, now)

        assertEquals(
            setOf(3, 4, 5, 6, 7),
            pruned.map { (it.key as ThreadPageDownloadKey).tid }.toSet(),
        )
    }

    @Test
    fun expiredQueueHistoryDoesNotRemoveManifestDownloadedStatus() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val storage = FakeStorage()
        storage.seed(
            key,
            ThreadPageDownloadManifest(key, "saved", 1, 1, DownloadPageKind.LastAtDownloadTime),
            page(42, 1, 1),
        )
        storage.seedQueue(
            listOf(
                DownloadQueueEntry(
                    key = key,
                    title = "old history",
                    status = DownloadStatus.Failed,
                    updatedAt = 1,
                ),
            )
        )
        val repository = repository(FakeThreadRepository(), storage)

        assertEquals(DownloadStatus.Downloaded, repository.getStatus(key))
        assertTrue(repository.queue.value.any { it.key == key && it.status == DownloadStatus.Downloaded })
    }

    @Test
    fun clearingInFlightPagePreventsLateWrite() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val gate = CompletableDeferred<Unit>()
        val storage = FakeStorage()
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(1 to YamiboResult.Success(page(42, 1, 1))),
                fetchGate = gate,
            ),
            storage,
        )

        repository.enqueuePage(ThreadId(42), "title", null, 1).getOrThrow()
        awaitStatus(repository, key, DownloadStatus.Downloading)
        repository.clearPage(key).getOrThrow()
        gate.complete(Unit)
        delay(100)

        assertNull(storage.manifests[key])
        assertEquals(DownloadStatus.NotDownloaded, repository.getStatus(key))
    }

    @Test
    fun refreshFailureKeepsExistingSnapshot() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val storage = FakeStorage()
        val oldPage = page(42, 1, 1)
        storage.seed(
            key,
            ThreadPageDownloadManifest(key, "old", 1, 1, DownloadPageKind.LastAtDownloadTime),
            oldPage,
        )
        val thread = FakeThreadRepository(
            pages = mutableMapOf(1 to YamiboResult.Failure("network failed")),
        )
        val repository = repository(thread, storage)

        val result = repository.refreshPage(ThreadId(42), "new", null, 1)

        assertIs<YamiboResult.Failure>(result)
        assertEquals("old", storage.manifests.getValue(key).title)
        assertNotNull(storage.pages[key])
        Unit
    }

    @Test
    fun clearThreadOnlyDeletesMatchingAuthorVariant() = runBlocking {
        val storage = FakeStorage()
        val all = ThreadPageDownloadKey(42, 1)
        val author = ThreadPageDownloadKey(42, 1, 7)
        val other = ThreadPageDownloadKey(43, 1)
        listOf(all, author, other).forEach { key ->
            storage.seed(
                key,
                ThreadPageDownloadManifest(key, "title", 1, 1, DownloadPageKind.LastAtDownloadTime),
                page(key.tid, 1, 1),
            )
        }
        val repository = repository(FakeThreadRepository(), storage)

        repository.clearThread(author).getOrThrow()

        assertNotNull(storage.manifests[all])
        assertNull(storage.manifests[author])
        assertNotNull(storage.manifests[other])
        Unit
    }

    @Test
    fun tagMangaKeyIsStableAndSeparatedFromThreadPageDownloads() {
        val key = TagMangaChapterDownloadKey(tagId = 8, tid = 42, authorId = 7)

        assertEquals("tag_manga_8_chapter_42_author_7", key.stableId)
        assertEquals("tag_manga_8", key.tagStableId)
        assertEquals("chapter_42_author_7", key.chapterStableId)
    }

    @Test
    fun tagMangaCurrentPageQueuesOnlyVisibleThreads() = runBlocking {
        val storage = FakeStorage()
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(
                    1 to YamiboResult.Success(page(42, 1, 1, "https://img/42.jpg")),
                ),
            ),
            storage,
        )
        val thread = summary(42, "chapter")

        repository.enqueueTagMangaCurrentPage(TagId(8), "tag", listOf(thread), 3).getOrThrow()
        awaitStatus(repository, TagMangaChapterDownloadKey(8, 42, 7), DownloadStatus.Downloaded)

        assertEquals(setOf(TagMangaChapterDownloadKey(8, 42, 7)), storage.tagManifests.keys)
        assertEquals(3, storage.tagManifests.getValue(TagMangaChapterDownloadKey(8, 42, 7)).tagPage)
    }

    @Test
    fun tagMangaRefreshFailureKeepsExistingImages() = runBlocking {
        val key = TagMangaChapterDownloadKey(8, 42, 7)
        val storage = FakeStorage()
        storage.seedTag(
            key,
            TagMangaChapterManifest(
                key = key,
                tagName = "tag",
                tid = 42,
                title = "old",
                authorId = 7,
                tagPage = 1,
                imageCount = 1,
                downloadedAt = 1,
                images = listOf(DownloadedImage("https://old/a.jpg", "0001.jpg", 3)),
            ),
            mapOf("0001.jpg" to "content://tag/0001.jpg"),
        )
        val repository = repository(
            FakeThreadRepository(pages = mutableMapOf(1 to YamiboResult.Failure("network failed"))),
            storage,
        )

        val result = repository.refreshTagMangaChapter(key)

        assertIs<YamiboResult.Failure>(result)
        assertEquals("old", storage.tagManifests.getValue(key).title)
        assertEquals(listOf("content://tag/0001.jpg"), repository.getTagMangaChapterImages(key))
    }

    @Test
    fun threadDownloadPublishesStageAndImageProgress() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val snapshots = mutableListOf<DownloadQueueEntry>()
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(
                    1 to YamiboResult.Success(page(42, 1, 1, "https://img/a.jpg", "https://img/b.jpg")),
                ),
            ),
            FakeStorage(),
            backgroundController = DownloadBackgroundController { entries ->
                entries.firstOrNull { it.key == key }?.let { snapshots += it }
            },
        )

        repository.enqueuePage(ThreadId(42), "title", null, 1).getOrThrow()
        awaitStatus(repository, key, DownloadStatus.Downloaded)

        assertTrue(snapshots.any { it.stage == DownloadStage.Preparing })
        assertTrue(snapshots.any { it.stage == DownloadStage.FetchingContent })
        assertTrue(snapshots.any { it.stage == DownloadStage.DownloadingText })
        assertTrue(snapshots.any {
            it.stage == DownloadStage.DownloadingImages &&
                it.progressCurrent == 2 &&
                it.progressTotal == 2
        })
        assertTrue(snapshots.any { it.stage == DownloadStage.Saving })
    }

    @Test
    fun tagMangaDownloadPublishesImageProgressWithoutTextStage() = runBlocking {
        val key = TagMangaChapterDownloadKey(8, 42, 7)
        val snapshots = mutableListOf<DownloadQueueEntry>()
        val repository = repository(
            FakeThreadRepository(
                pages = mutableMapOf(
                    1 to YamiboResult.Success(page(42, 1, 1, "https://img/a.jpg", "https://img/b.jpg")),
                ),
            ),
            FakeStorage(),
            backgroundController = DownloadBackgroundController { entries ->
                entries.firstOrNull { it.key == key }?.let { snapshots += it }
            },
        )

        repository.enqueueTagMangaChapter(TagId(8), "tag", summary(42, "chapter"), 1).getOrThrow()
        awaitStatus(repository, key, DownloadStatus.Downloaded)

        assertTrue(snapshots.none { it.stage == DownloadStage.DownloadingText })
        assertTrue(snapshots.any { it.stage == DownloadStage.FetchingContent })
        assertTrue(snapshots.any {
            it.stage == DownloadStage.DownloadingImages &&
                it.progressCurrent == 2 &&
                it.progressTotal == 2
        })
        assertTrue(snapshots.any { it.stage == DownloadStage.Saving })
    }

    @Test
    fun downloadedContentSummaryAndGroupsUseManifestImageSizes() = runBlocking {
        val threadKey = ThreadPageDownloadKey(42, 2)
        val tagKey = TagMangaChapterDownloadKey(8, 50, 7)
        val storage = FakeStorage()
        storage.seed(
            threadKey,
            ThreadPageDownloadManifest(
                key = threadKey,
                title = "thread",
                downloadedAt = 1,
                sourceTotalPages = 3,
                pageKind = DownloadPageKind.Normal,
                forumId = 2,
                forumName = "板塊",
                images = listOf(DownloadedImage("https://img/a.jpg", "0000.jpg", 10)),
            ),
            page(42, 2, 3),
        )
        storage.seedTag(
            tagKey,
            TagMangaChapterManifest(
                key = tagKey,
                tagName = "tag",
                title = "chapter",
                tagPage = 4,
                imageCount = 2,
                downloadedAt = 2,
                images = listOf(
                    DownloadedImage("https://img/b.jpg", "0001.jpg", 20),
                    DownloadedImage("https://img/c.jpg", "0002.jpg", 30),
                ),
            ),
        )
        val repository = repository(FakeThreadRepository(), storage)

        val summary = repository.getDownloadedContentSummary()
        val groups = repository.getDownloadedContentGroups()

        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.threadPages)
        assertEquals(1, summary.tagMangaChapters)
        assertEquals(3, summary.imageCount)
        assertEquals(60, summary.imageBytes)
        assertEquals(10, summary.threadImageBytes)
        assertEquals(50, summary.tagMangaImageBytes)
        assertEquals(setOf(DownloadedContentGroupType.Thread, DownloadedContentGroupType.TagManga), groups.map { it.type }.toSet())
        assertTrue(groups.any { it.title == "thread" && it.imageBytes == 10L && it.filterKey == "forum_2" && it.filterLabel == "板塊" })
        assertTrue(groups.any { it.title == "#tag" && it.imageBytes == 50L && it.filterKey == DOWNLOADED_CONTENT_FILTER_TAG_MANGA })
    }

    @Test
    fun downloadedContentGroupsFallbackToUnknownForumForOldManifest() = runBlocking {
        val key = ThreadPageDownloadKey(42, 1)
        val storage = FakeStorage()
        storage.seed(
            key,
            ThreadPageDownloadManifest(
                key = key,
                title = "old",
                downloadedAt = 1,
                sourceTotalPages = 1,
                pageKind = DownloadPageKind.LastAtDownloadTime,
            ),
            page(42, 1, 1),
        )
        val repository = repository(FakeThreadRepository(), storage)

        val group = repository.getDownloadedContentGroups().single()

        assertEquals(DOWNLOADED_CONTENT_FILTER_UNKNOWN_FORUM, group.filterKey)
        assertEquals("未知板塊", group.filterLabel)
    }

    @Test
    fun downloadedContentPresentationSortsAndFiltersGroups() {
        val groups = listOf(
            contentGroup("thread-small", DownloadedContentGroupType.Thread, 10, 1, "forum_1", "A"),
            contentGroup("thread-large", DownloadedContentGroupType.Thread, 30, 3, "forum_2", "B"),
            contentGroup("tag", DownloadedContentGroupType.TagManga, 20, 2, DOWNLOADED_CONTENT_FILTER_TAG_MANGA, "標籤漫畫"),
        )

        val bySizeDesc = filterAndSortDownloadedContentGroups(
            groups,
            selectedFilterKeys = emptySet(),
            sortMode = DownloadedContentSortMode.TotalSize,
            descending = true,
        )
        val forumOnly = filterAndSortDownloadedContentGroups(
            groups,
            selectedFilterKeys = setOf("forum_1"),
            sortMode = DownloadedContentSortMode.DownloadedAt,
            descending = false,
        )
        val options = buildDownloadedContentFilterOptions(groups)

        assertEquals(listOf("thread-large", "tag", "thread-small"), bySizeDesc.map { it.id })
        assertEquals(listOf("thread-small"), forumOnly.map { it.id })
        assertEquals(setOf("forum_1", "forum_2", DOWNLOADED_CONTENT_FILTER_TAG_MANGA), options.map { it.key }.toSet())
    }

    private fun repository(
        threadRepository: ThreadRepository,
        storage: FakeStorage,
        tagRepository: TagRepository? = null,
        imageFetcher: DownloadImageFetcher = object : DownloadImageFetcher({ "" }) {
            override suspend fun fetch(url: String): ByteArray = byteArrayOf(1, 2, 3)
        },
        backgroundController: DownloadBackgroundController = DownloadBackgroundController.None,
    ) = DownloadRepositoryImpl(
        threadRepository = threadRepository,
        tagRepository = tagRepository,
        storageProvider = storage,
        imageFetcher = imageFetcher,
        backgroundController = backgroundController,
        json = json,
    )

    private suspend fun awaitStatus(
        repository: DownloadRepository,
        key: DownloadTaskKey,
        expected: DownloadStatus,
    ) {
        repeat(200) {
            val actual = when (key) {
                is ThreadPageDownloadKey -> repository.getStatus(key)
                is TagMangaChapterDownloadKey -> repository.getStatus(key)
                is RssMangaChapterDownloadKey -> repository.getStatus(key)
            }
            if (actual == expected) return
            delay(10)
        }
        error("Timed out waiting for $key to become $expected. Queue=${repository.queue.value}")
    }

    private fun page(tid: Int, currentPage: Int, totalPages: Int, vararg images: String): ThreadPage {
        val sources = images.toList()
        return ThreadPage(
            thread = ThreadInfo(
                tid = ThreadId(tid),
                title = "title",
                forum = ForumSummary(ForumId(1), "forum", "forum.php?fid=1"),
            ),
            posts = listOf(
                Post(
                    pid = PostId(currentPage),
                    floor = currentPage,
                    title = "",
                    author = User(UserId(7), "author"),
                    timeCreate = TimeInfo("2026-01-01", null, 0),
                    contentHtml = sources.joinToString("") { "<img src=\"$it\">" },
                    images = sources.map { PostImage(it) },
                    tags = Tags(),
                    poll = null,
                ),
            ),
            pageNav = PageNav(currentPage = currentPage, totalPages = totalPages),
        )
    }

    private fun summary(tid: Int, title: String): ThreadSummary {
        return ThreadSummary(
            tid = ThreadId(tid),
            title = title,
            fid = ForumId(1),
            hasPoll = false,
            url = "forum.php?mod=viewthread&tid=$tid",
            author = User(UserId(7), "author"),
        )
    }

    private fun contentGroup(
        id: String,
        type: DownloadedContentGroupType,
        bytes: Long,
        downloadedAt: Long,
        filterKey: String,
        filterLabel: String,
    ): DownloadedContentGroup {
        val key: DownloadTaskKey = when (type) {
            DownloadedContentGroupType.Thread -> ThreadPageDownloadKey(id.hashCode(), 1)
            DownloadedContentGroupType.TagManga -> TagMangaChapterDownloadKey(1, id.hashCode())
            DownloadedContentGroupType.RssManga -> RssMangaChapterDownloadKey(1, id.hashCode())
        }
        return DownloadedContentGroup(
            id = id,
            title = id,
            type = type,
            itemCount = 1,
            imageCount = 1,
            imageBytes = bytes,
            items = listOf(
                DownloadedContentItem(
                    key = key,
                    title = id,
                    detail = id,
                    downloadedAt = downloadedAt,
                    imageCount = 1,
                    imageBytes = bytes,
                )
            ),
            filterKey = filterKey,
            filterLabel = filterLabel,
            latestDownloadedAt = downloadedAt,
        )
    }

    private class FakeStorage : DownloadStorageProvider {
        val manifests = mutableMapOf<ThreadPageDownloadKey, ThreadPageDownloadManifest>()
        val pages = mutableMapOf<ThreadPageDownloadKey, ByteArray>()
        val tagManifests = mutableMapOf<TagMangaChapterDownloadKey, TagMangaChapterManifest>()
        val rssManifests = mutableMapOf<RssMangaChapterDownloadKey, RssMangaChapterManifest>()
        private val imageUris = mutableMapOf<Pair<ThreadPageDownloadKey, String>, String>()
        private val tagImageUris = mutableMapOf<Pair<TagMangaChapterDownloadKey, String>, String>()
        private val rssImageUris = mutableMapOf<Pair<RssMangaChapterDownloadKey, String>, String>()
        private var queue = emptyList<DownloadQueueEntry>()
        private val json = Json { ignoreUnknownKeys = true }

        fun seed(
            key: ThreadPageDownloadKey,
            manifest: ThreadPageDownloadManifest,
            page: ThreadPage,
            images: Map<String, String> = emptyMap(),
        ) {
            manifests[key] = manifest
            pages[key] = json.encodeToString(page).encodeToByteArray()
            images.forEach { (name, uri) -> imageUris[key to name] = uri }
        }

        fun seedQueue(entries: List<DownloadQueueEntry>) {
            queue = entries
        }

        fun seedTag(
            key: TagMangaChapterDownloadKey,
            manifest: TagMangaChapterManifest,
            images: Map<String, String> = emptyMap(),
        ) {
            tagManifests[key] = manifest
            images.forEach { (name, uri) -> tagImageUris[key to name] = uri }
        }

        fun seedRss(
            key: RssMangaChapterDownloadKey,
            manifest: RssMangaChapterManifest,
            images: Map<String, String> = emptyMap(),
        ) {
            rssManifests[key] = manifest
            images.forEach { (name, uri) -> rssImageUris[key to name] = uri }
        }

        override suspend fun getSelectedFolderLabel(): String = "test"
        override suspend fun isReady(): Boolean = true
        override suspend fun writeThreadPage(
            key: ThreadPageDownloadKey,
            manifestBytes: ByteArray,
            threadPageBytes: ByteArray,
            images: List<PendingDownloadedImage>,
        ) {
            manifests[key] = json.decodeFromString(manifestBytes.decodeToString())
            pages[key] = threadPageBytes
            images.forEach { image -> imageUris[key to image.fileName] = "content://download/${image.fileName}" }
        }

        override suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray? = pages[key]
        override suspend fun resolveImageUri(key: ThreadPageDownloadKey, fileName: String): String? =
            imageUris[key to fileName]
        override suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? = manifests[key]
        override suspend fun listManifests(): List<ThreadPageDownloadManifest> = manifests.values.toList()
        override suspend fun writeTagMangaChapter(
            key: TagMangaChapterDownloadKey,
            manifestBytes: ByteArray,
            images: List<PendingDownloadedImage>,
        ) {
            tagManifests[key] = json.decodeFromString(manifestBytes.decodeToString())
            images.forEach { image -> tagImageUris[key to image.fileName] = "content://tag/${image.fileName}" }
        }
        override suspend fun resolveTagMangaImageUri(key: TagMangaChapterDownloadKey, fileName: String): String? =
            tagImageUris[key to fileName]
        override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? =
            tagManifests[key]
        override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> = tagManifests.values.toList()
        override suspend fun writeRssMangaChapter(
            key: RssMangaChapterDownloadKey,
            manifestBytes: ByteArray,
            images: List<PendingDownloadedImage>,
        ) {
            rssManifests[key] = json.decodeFromString(manifestBytes.decodeToString())
            images.forEach { image -> rssImageUris[key to image.fileName] = "content://rss/${image.fileName}" }
        }
        override suspend fun resolveRssMangaImageUri(key: RssMangaChapterDownloadKey, fileName: String): String? =
            rssImageUris[key to fileName]
        override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? =
            rssManifests[key]
        override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> = rssManifests.values.toList()
        override suspend fun readQueue(): List<DownloadQueueEntry> = queue
        override suspend fun writeQueue(entries: List<DownloadQueueEntry>) {
            queue = entries
        }

        override suspend fun deleteThreadPage(key: ThreadPageDownloadKey) {
            manifests.remove(key)
            pages.remove(key)
            imageUris.keys.removeAll { it.first == key }
        }

        override suspend fun deleteThread(key: ThreadPageDownloadKey) {
            manifests.keys
                .filter { it.tid == key.tid && it.authorId == key.authorId }
                .toList()
                .forEach { deleteThreadPage(it) }
        }

        override suspend fun deleteTagMangaChapter(key: TagMangaChapterDownloadKey) {
            tagManifests.remove(key)
            tagImageUris.keys.removeAll { it.first == key }
        }

        override suspend fun deleteTagManga(tagId: Int) {
            tagManifests.keys
                .filter { it.tagId == tagId }
                .toList()
                .forEach { deleteTagMangaChapter(it) }
        }

        override suspend fun deleteRssMangaChapter(key: RssMangaChapterDownloadKey) {
            rssManifests.remove(key)
            rssImageUris.keys.removeAll { it.first == key }
        }

        override suspend fun deleteRssManga(subscriptionId: Long) {
            rssManifests.keys
                .filter { it.subscriptionId == subscriptionId }
                .toList()
                .forEach { deleteRssMangaChapter(it) }
        }
    }

    private class FakeTagRepository(
        private val pages: MutableMap<Int, YamiboResult<TagPage>> = mutableMapOf(),
    ) : TagRepository {
        override suspend fun fetchTagPage(tagId: TagId, page: Int): YamiboResult<TagPage> =
            pages[page] ?: YamiboResult.Failure("missing tag page $page")

        override suspend fun fetchExtractTags(tid: ThreadId): YamiboResult<Tags> = error("unused")
        override fun getCachedTagPage(tagId: TagId, page: Int): TagPage? = null
        override fun setCachedTagPage(tagId: TagId, page: Int, tagPage: TagPage) = Unit
        override fun clearCachedTagPage(tagId: TagId) = Unit
    }

    private class FakeThreadRepository(
        private val pages: MutableMap<Int, YamiboResult<ThreadPage>> = mutableMapOf(),
        private val fetchGate: CompletableDeferred<Unit>? = null,
    ) : ThreadRepository {
        val fetchCounts = mutableMapOf<Int, Int>()

        override suspend fun fetchThread(
            tid: ThreadId,
            authorId: UserId?,
            page: Int,
            reverse: Boolean,
        ): YamiboResult<ThreadPage> {
            fetchCounts[page] = (fetchCounts[page] ?: 0) + 1
            fetchGate?.await()
            return pages[page] ?: YamiboResult.Failure("missing page $page")
        }

        override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? = null
        override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) = Unit
        override fun clearCachedThread(tid: ThreadId) = Unit
        override suspend fun fetchFindPost(tid: ThreadId, postId: PostId, authorId: UserId?) = error("unused")
        override suspend fun addFavorite(tid: ThreadId, formHash: FormHash) = error("unused")
        override suspend fun votePoll(
            fId: ForumId,
            tId: ThreadId,
            pollOptionIds: List<PollOptionId>,
            formHash: FormHash,
        ) = error("unused")
        override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> = error("unused")
        override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> = error("unused")
        override suspend fun fetchVoters(
            tId: ThreadId,
            pollOptionId: PollOptionId?,
            page: Int,
        ): YamiboResult<VotersPopoutScreen> = error("unused")
        override suspend fun ratePost(
            tId: ThreadId,
            pId: PostId,
            score: Int,
            reason: String,
            formHash: FormHash,
            noticeAuthor: Boolean,
        ) = error("unused")
        override suspend fun commentPost(
            tId: ThreadId,
            pId: PostId,
            message: String,
            formHash: FormHash,
        ) = error("unused")
    }
}
