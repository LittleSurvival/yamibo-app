package me.thenano.yamibo.yamibo_app.repository.download

import me.thenano.yamibo.yamibo_app.repository.thread.readerImageUrls
import me.thenano.yamibo.yamibo_app.repository.thread.readerImageAttribute
import me.thenano.yamibo.yamibo_app.repository.thread.readerImages
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import no.synth.kmpzip.crypto.Crypto
import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.okio.asSource
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile
import no.synth.kmpzip.zip.ZipFormatException
import me.thenano.yamibo.yamibo_app.Database
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use
import okio.ByteString.Companion.encodeUtf8
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.PostImage
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.thread.isValidThreadPage
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class DownloadRepositoryImpl(
    private val threadRepository: ThreadRepository,
    private val tagRepository: TagRepository? = null,
    private val rssRepository: RssSearchSubscriptionRepository? = null,
    storageProvider: DownloadStorageProvider,
    private val imageFetcher: DownloadImageFetcher,
    private val cacheDirectory: Path,
    private val database: Database,
    private val accountId: () -> Int = { 0 },
    private val backgroundController: DownloadBackgroundController = DownloadBackgroundController.None,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    },
    private val accessRevision: () -> Long = { 0L },
) : DownloadRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val library = OfflineLibrary(storageProvider, database, cacheDirectory, json, accountId)
    private val storageProvider = library
    // Readers can continue using the committed version while an image download is staged.
    private val libraryMutation = Mutex()
    private val workerMutex = Mutex()
    private val taskMutex = Mutex()
    private val removalMutex = Mutex()
    private val queueWriteMutex = Mutex()
    private var savedQueue: Pair<String?, List<DownloadQueueEntry>>? = null
    private val activeTasks = mutableMapOf<DownloadTaskKey, Job>()
    private val imageRepairs = mutableMapOf<Triple<Int, MissingThreadImage, Long>, Deferred<Result<String>>>()
    // Keep only recently resolved legacy SAF handles. A shelf mutation invalidates all of them.
    private val looseImagePaths = linkedMapOf<String, String>()
    private val archives = linkedMapOf<Pair<String, DownloadTaskKey>, OpenArchive>()
    private class OpenArchive(val owner: Int?, val generation: String, val zip: ZipFile, val names: MutableSet<String>) {
        var readers = 0
        var retired = false
        fun retire() {
            retired = true
            if (readers == 0) zip.close()
        }
    }
    private val imageRevision = MutableStateFlow(0L)
    private val removing = mutableListOf<(DownloadTaskKey) -> Boolean>()
    private val queueWrites = Channel<Long>(Channel.CONFLATED)
    private val contentMutex = Mutex()
    private var contentCatalog: DownloadedContentCatalog? = null
    private val prefetchedPages = mutableMapOf<Pair<Int, ThreadPageDownloadKey>, ThreadPage>()
    private val tagMangaTasks = mutableMapOf<Pair<Int, TagMangaChapterDownloadKey>, TagMangaTaskInfo>()
    private val rssMangaTasks = mutableMapOf<Pair<Int, RssMangaChapterDownloadKey>, RssMangaTaskInfo>()
    private val _queue = MutableStateFlow<List<DownloadQueueEntry>>(emptyList())
    override val queue: StateFlow<List<DownloadQueueEntry>> = _queue
    private val initialized = CompletableDeferred<Unit>()
    private var paused = false

    private inline fun <T> downloadResult(operation: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.e(TAG, "$operation failed", error)
            Result.failure(error)
        }

    init {
        scope.launch {
            try {
                if (!storageProvider.isReady()) return@launch
                downloadResult("restoreQueue") { restoreQueue(storageProvider.getStorageKey()) }.getOrElse { return@launch }
            } finally {
                initialized.complete(Unit)
            }
            drainQueue()
        }
        scope.launch {
            initialized.await()
            for (request in queueWrites) {
                downloadResult("persistQueue") { writeQueueSnapshot(request) }
            }
        }
    }

    override suspend fun isStorageReady(): Boolean =
        storageProvider.isReady()

    override suspend fun getSummary(): DownloadQueueSummary {
        initialized.await()
        return withContext(Dispatchers.IO) {
            val downloaded = getDownloadedContentSummary().totalItems
            val owner = accountId()
            val entries = queue.value.filter { it.owner == null || it.owner == owner }
            DownloadQueueSummary(
                queued = entries.count { it.status == DownloadStatus.Queued },
                downloading = entries.count { it.status == DownloadStatus.Downloading },
                downloaded = downloaded,
                failed = entries.count { it.status == DownloadStatus.Failed },
                updateAvailable = entries.count { it.status == DownloadStatus.UpdateAvailable },
            )
        }
    }

    override suspend fun getDownloadedContentSummary(): DownloadedContentSummary =
        summarizeDownloadedContent(getDownloadedContentGroups())

    override suspend fun getDownloadedContentGroups(forceRefresh: Boolean): List<DownloadedContentGroup> {
        initialized.await()
        return withContext(Dispatchers.IO) {
            val storageKey = storageProvider.getStorageKey()
            if (storageKey != null) ensureStorageReady()
            contentMutex.withLock {
                val catalog = catalogLocked(storageKey, forceRefresh)
                val owner = accountId()
                check(catalog.owner == owner) { "下載帳號已切換" }
                val visibleGroups = catalog.groups.mapNotNull { group ->
                    val visible = group.items.filter { it.owner == null || it.owner == owner }
                    when (visible.size) {
                        0 -> null
                        group.items.size -> group
                        else -> {
                            val title = if (group.type == DownloadedContentGroupType.Thread) visible.first().title else group.title
                            downloadedContentGroup(group.id, title, group.type, visible, group.filterKey, group.filterLabel)
                        }
                    }
                }
                val scope = storageKey to owner
                if (queueCatalogScope != scope || forceRefresh) {
                    reconcileStoredQueue(visibleGroups, owner)
                    queueCatalogScope = scope
                    if (queue.value.any { it.visibleTo(owner) && it.status == DownloadStatus.Queued }) drainQueue()
                }
                mergeDownloadedThreads(visibleGroups)
            }
        }
    }

    private var queueCatalogScope: Pair<String?, Int>? = null

    private fun DownloadQueueEntry.visibleTo(account: Int?) = owner == null || owner == account

    private fun queueEntry(key: DownloadTaskKey, account: Int? = null): DownloadQueueEntry? {
        val owner = account ?: accountId()
        return queue.value.firstOrNull { it.key == key && it.visibleTo(owner) }
    }

    /** Called under contentMutex when the download manager explicitly loads its inventory. */
    private suspend fun reconcileStoredQueue(groups: List<DownloadedContentGroup>, owner: Int) {
        val stored = groups.flatMap { group ->
            group.items.map { item ->
                val title = when (group.type) {
                    DownloadedContentGroupType.Thread -> item.title
                    DownloadedContentGroupType.TagManga -> tagMangaQueueTitle(group.title.removePrefix("#").takeUnless { it == group.id }.orEmpty(), item.title)
                    DownloadedContentGroupType.RssManga -> rssMangaQueueTitle(group.title.removePrefix("RSS / ").takeUnless { it == group.id }.orEmpty(), item.title)
                }
                DownloadQueueEntry(item.key, title,
                    if (item.missingImageCount > 0) DownloadStatus.Failed else DownloadStatus.Downloaded,
                    progressCurrent = item.imageCount, progressTotal = item.imageCount + item.missingImageCount,
                    message = if (item.missingImageCount > 0) "尚缺 ${item.missingImageCount} 張圖片" else null,
                    updatedAt = item.downloadedAt, owner = item.owner)
            }
        }.associateBy { it.key }
        reconcileRepairedQueue { entry ->
            stored[entry.key]?.takeIf { it.status == DownloadStatus.Downloaded && (entry.owner == null || it.owner == entry.owner) }
                ?.progressCurrent
        }
        _queue.update { current ->
            val (mine, otherAccounts) = current.partition { it.visibleTo(owner) }
            val retained = mine.filter { entry ->
                entry.status != DownloadStatus.Downloaded && entry.status != DownloadStatus.UpdateAvailable || entry.key in stored
            }
            val updated = retained.map { entry ->
                if (entry.status == DownloadStatus.Downloaded && stored[entry.key]?.status == DownloadStatus.Failed)
                    stored.getValue(entry.key) else entry
            }
            val keys = updated.mapTo(hashSetOf()) { it.key }
            (otherAccounts + updated + stored.values.filterNot { it.key in keys }).sortedBy { it.key.stableId }
        }
    }

    private data class DownloadedContentCatalog(val storageKey: String?, val groups: List<DownloadedContentGroup>, val owner: Int)

    private suspend fun catalogLocked(storageKey: String?, refresh: Boolean = false): DownloadedContentCatalog {
        val owner = accountId()
        val repair = storageKey != null && (!readerIndex.inventoryReady(storageKey, owner.toLong()).executeAsOne() ||
            readerIndex.dirtyLibraryThreads(storageKey, owner.toLong()).executeAsList().isNotEmpty())
        if (!refresh && !repair) {
            contentCatalog?.takeIf { it.storageKey == storageKey && it.owner == owner }?.let { return it }
            val cached = storageKey?.let { DownloadCatalogIndex(cacheDirectory, "library3/$owner/$it").read(::applyCatalogEdits) }
            if (cached != null) return DownloadedContentCatalog(storageKey, cached, owner).also { contentCatalog = it }
        }
        if (storageKey != null && (refresh || repair)) library.reconcile(storageKey, owner, collect = false)
        val shared = storageKey?.let { library.inventory(it, owner) }.orEmpty()
        val legacy = library.legacy
        val threads = legacy.listManifests()
        val tags = legacy.listTagMangaManifests()
        val feeds = legacy.listRssMangaManifests()
        val legacyKeys = (threads + tags + feeds).filter { it.owner == null || it.owner == owner }.mapTo(hashSetOf()) { it.key }
        val groups = buildContentGroups(
            (shared.filterIsInstance<ThreadPageDownloadManifest>() + threads).distinctBy { (it.owner ?: owner) to it.key },
            (shared.filterIsInstance<TagMangaChapterManifest>() + tags).distinctBy { (it.owner ?: owner) to it.key },
            (shared.filterIsInstance<RssMangaChapterManifest>() + feeds).distinctBy { (it.owner ?: owner) to it.key },
        ).map { group ->
            group.copy(items = group.items.map { if (it.key in legacyKeys) it.copy(needsMigration = true) else it })
        }
        check(storageProvider.getStorageKey() == storageKey) { "儲存資料夾已變更" }
        check(accountId() == owner) { "下載帳號已切換" }
        if (storageKey != null) DownloadCatalogIndex(cacheDirectory, "library3/$owner/$storageKey").commit(groups)
        return DownloadedContentCatalog(storageKey, groups, owner).also { contentCatalog = it }
    }

    /** Invalidate the persistent snapshot before mutating user storage. A crash rebuilds it. */
    private suspend fun beginCatalogChange(): DownloadedContentCatalog {
        val storage = checkNotNull(storageProvider.getStorageKey())
        val catalog = catalogLocked(storage)
        DownloadCatalogIndex(cacheDirectory, "library3/${catalog.owner}/$storage").begin()
        looseImagePaths.clear()
        contentCatalog = null
        return catalog
    }

    private suspend fun finishCatalogChange(
        catalog: DownloadedContentCatalog,
        removed: Set<DownloadTaskKey> = emptySet(),
        updated: List<DownloadManifest> = emptyList(),
        migrated: DownloadTaskKey? = null,
    ) {
        val replacements = buildContentGroups(
            updated.filterIsInstance<ThreadPageDownloadManifest>(),
            updated.filterIsInstance<TagMangaChapterManifest>(),
            updated.filterIsInstance<RssMangaChapterManifest>(),
        )
        val edit = CatalogEdit(removed + updated.map { it.key }, replacements, migrated)
        val groups = applyCatalogEdits(catalog.groups, listOf(edit))
        val storage = checkNotNull(catalog.storageKey)
        DownloadCatalogIndex(cacheDirectory, "library3/${catalog.owner}/$storage").commit(groups, edit)
        contentCatalog = DownloadedContentCatalog(storage, groups, catalog.owner)

        val repaired = updated.filter { it.missingImageCount == 0 }.associateBy { it.key }
        if (repaired.isNotEmpty()) reconcileRepairedQueue { entry ->
            repaired[entry.key]?.takeIf { entry.owner == null || it.owner == entry.owner }?.images?.size
        }
    }

    private suspend fun reconcileRepairedQueue(completedCount: (DownloadQueueEntry) -> Int?) {
        fun repairedCount(entry: DownloadQueueEntry): Int? =
            if (entry.status == DownloadStatus.Failed && !entry.refreshSource && entry.progressCurrent < entry.progressTotal)
                completedCount(entry) else null

        if (queue.value.none { repairedCount(it) != null }) return
        _queue.update { entries ->
            entries.map { entry ->
                val count = repairedCount(entry)
                if (count == null) entry else entry.copy(status = DownloadStatus.Downloaded, message = null,
                    stage = null, progressCurrent = count, progressTotal = count)
            }
        }
        writeQueueSnapshot()
    }

    private fun applyCatalogEdits(previous: List<DownloadedContentGroup>, edits: List<CatalogEdit>): List<DownloadedContentGroup> {
        val groups = previous.associateByTo(linkedMapOf()) { it.id }
        for ((removed, replacements, migrated) in edits) {
            val additions = replacements.associateBy { it.id }
            val byGroup = removed.groupBy { key -> when (key) {
                is ThreadPageDownloadKey -> "thread_${key.tid}_author_${key.authorId ?: "all"}"
                is TagMangaChapterDownloadKey -> key.tagStableId
                is RssMangaChapterDownloadKey -> key.rssStableId
            } }
            for (id in byGroup.keys + additions.keys) {
                val addition = additions[id]
                val old = groups[id]
                val header = addition ?: old ?: continue
                val oldItems = old?.items.orEmpty()
                val pendingMigration = oldItems.filter { it.needsMigration && it.key != migrated }.mapTo(hashSetOf()) { it.key }
                val items = oldItems.filterNot { it.key in removed } + addition?.items.orEmpty().map { item ->
                    if (item.key in pendingMigration) item.copy(needsMigration = true) else item
                }
                if (items.isEmpty()) {
                    groups.remove(id)
                    continue
                }
                val sorted = items.sortedWith(compareBy<DownloadedContentItem> { it.sortPage }.thenBy { it.title })
                val title = if (header.type == DownloadedContentGroupType.Thread) sorted.first().title else header.title
                groups[id] = downloadedContentGroup(id, title, header.type, sorted, header.filterKey, header.filterLabel)
            }
        }
        return groups.values.sortedWith(compareBy<DownloadedContentGroup> { it.type.name }.thenBy { it.title })
    }

    private fun buildContentGroups(
        threadManifests: List<ThreadPageDownloadManifest>,
        tagManifests: List<TagMangaChapterManifest>,
        rssManifests: List<RssMangaChapterManifest>,
    ): List<DownloadedContentGroup> {
        val threadGroups = threadManifests
            .groupBy { manifest -> "thread_${manifest.key.tid}_author_${manifest.key.authorId ?: "all"}" }
            .map { (id, manifests) ->
                val sorted = manifests.sortedBy { it.key.page }
                downloadedContentGroup(
                    id = id,
                    title = sorted.firstOrNull()?.title ?: id,
                    type = DownloadedContentGroupType.Thread,
                    items = sorted.map { manifest ->
                        DownloadedContentItem(
                            key = manifest.key,
                            title = manifest.title,
                            needsMigration = !manifest.shared,
                            sharedImages = manifest.images.mapNotNull { img -> img.digest?.let { it to img.bytes } }.toMap(),
                            sortPage = manifest.key.page,
                            detail = "第 ${manifest.key.page} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = if (manifest.shared) manifest.images.distinctBy { it.digest }.size else manifest.images.size,
                            missingImageCount = manifest.missingImageCount,
                            owner = manifest.owner,
                            imageBytes = (if (manifest.shared) manifest.images.distinctBy { it.digest } else manifest.images).sumOf { it.bytes },
                        )
                    },
                    filterKey = sorted.firstOrNull()?.forumId?.let { "forum_$it" }
                        ?: DOWNLOADED_CONTENT_FILTER_UNKNOWN_FORUM,
                    filterLabel = sorted.firstOrNull()?.forumName?.takeIf { it.isNotBlank() }
                        ?: "未知板塊",
                )
            }
        val tagGroups = tagManifests
            .groupBy { manifest -> "tag_manga_${manifest.key.tagId}" }
            .map { (id, manifests) ->
                val sorted = manifests.sortedWith(compareBy<TagMangaChapterManifest> { it.tagPage }.thenBy { it.title })
                val tagName = sorted.firstOrNull()?.tagName.orEmpty()
                downloadedContentGroup(
                    id = id,
                    title = if (tagName.isBlank()) id else "#$tagName",
                    type = DownloadedContentGroupType.TagManga,
                    items = sorted.map { manifest ->
                        DownloadedContentItem(
                            key = manifest.key,
                            title = manifest.title,
                            needsMigration = !manifest.shared,
                            sharedImages = manifest.images.mapNotNull { img -> img.digest?.let { it to img.bytes } }.toMap(),
                            sortPage = manifest.tagPage,
                            detail = "標籤第 ${manifest.tagPage} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = if (manifest.shared) manifest.images.distinctBy { it.digest }.size else manifest.images.size,
                            missingImageCount = manifest.missingImageCount,
                            owner = manifest.owner,
                            imageBytes = (if (manifest.shared) manifest.images.distinctBy { it.digest } else manifest.images).sumOf { it.bytes },
                        )
                    },
                    filterKey = DOWNLOADED_CONTENT_FILTER_TAG_MANGA,
                    filterLabel = "標籤漫畫",
                )
            }
        val rssGroups = rssManifests
            .groupBy { manifest -> "rss_${manifest.key.subscriptionId}" }
            .map { (id, manifests) ->
                val sorted = manifests.sortedWith(compareBy<RssMangaChapterManifest> { it.subscriptionPage }.thenBy { it.title })
                val subscriptionTitle = sorted.firstOrNull()?.subscriptionTitle.orEmpty()
                downloadedContentGroup(
                    id = id,
                    title = if (subscriptionTitle.isBlank()) id else "RSS / $subscriptionTitle",
                    type = DownloadedContentGroupType.RssManga,
                    items = sorted.map { manifest ->
                        DownloadedContentItem(
                            key = manifest.key,
                            title = manifest.title,
                            needsMigration = !manifest.shared,
                            sharedImages = manifest.images.mapNotNull { img -> img.digest?.let { it to img.bytes } }.toMap(),
                            sortPage = manifest.subscriptionPage,
                            detail = "RSS 第 ${manifest.subscriptionPage} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = if (manifest.shared) manifest.images.distinctBy { it.digest }.size else manifest.images.size,
                            missingImageCount = manifest.missingImageCount,
                            owner = manifest.owner,
                            imageBytes = (if (manifest.shared) manifest.images.distinctBy { it.digest } else manifest.images).sumOf { it.bytes },
                        )
                    },
                    filterKey = DOWNLOADED_CONTENT_FILTER_TAG_MANGA,
                    filterLabel = "標籤漫畫",
                )
            }
        return (threadGroups + tagGroups + rssGroups).sortedWith(compareBy<DownloadedContentGroup> { it.type.name }.thenBy { it.title })
    }

    private fun downloadedContentGroup(
        id: String,
        title: String,
        type: DownloadedContentGroupType,
        items: List<DownloadedContentItem>,
        filterKey: String,
        filterLabel: String,
    ): DownloadedContentGroup =
        DownloadedContentGroup(
            id = id,
            title = title,
            type = type,
            itemCount = items.size,
            imageCount = items.sumOf { it.imageCount },
            imageBytes = items.sumOf { it.imageBytes },
            items = items,
            filterKey = filterKey,
            filterLabel = filterLabel,
            latestDownloadedAt = items.maxOfOrNull { it.downloadedAt } ?: 0L,
        )

    override suspend fun getStatus(key: ThreadPageDownloadKey) = downloadStatus(key)
    override suspend fun getStatus(key: TagMangaChapterDownloadKey) = downloadStatus(key)
    override suspend fun getStatus(key: RssMangaChapterDownloadKey) = downloadStatus(key)

    private suspend fun downloadStatus(key: DownloadTaskKey): DownloadStatus {
        initialized.await()
        val owner = accountId()
        val entry = queueEntry(key, owner)
        if (entry != null && entry.status !in setOf(DownloadStatus.Downloaded, DownloadStatus.UpdateAvailable)) return entry.status
        val manifest = withContext(Dispatchers.IO) { readManifest(key) }
        return when {
            manifest == null || manifest.owner != null && manifest.owner != owner -> DownloadStatus.NotDownloaded
            manifest.missingImageCount > 0 -> DownloadStatus.Failed
            else -> entry?.status ?: DownloadStatus.Downloaded
        }
    }

    private class DownloadAccount(val id: Int, val storage: String?, val revision: Long, val observedAt: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<DownloadAccount>
    }

    private class DownloadScopeChanged : IllegalStateException("下載帳號或儲存資料夾已變更")

    private suspend fun downloadAccount(owner: Int = accountId()): DownloadAccount {
        val revision = accessRevision()
        val storage = storageProvider.getStorageKey()
        if (revision != accessRevision() || owner != accountId()) throw DownloadScopeChanged()
        return DownloadAccount(owner, storage, revision, currentTimeMillis())
    }

    private suspend fun activeOwner(): Int {
        val active = accountId()
        currentCoroutineContext()[DownloadAccount]?.let { task ->
            if (task.id != active || task.revision != accessRevision() || task.storage != storageProvider.getStorageKey()) {
                throw DownloadScopeChanged()
            }
        }
        return active
    }

    private data class PageRequest(val storage: String?, val owner: Int, val revision: Long, val page: ThreadPageDownloadKey)
    private val pendingPages = me.thenano.yamibo.yamibo_app.repository.thread.InFlightReads<PageRequest, YamiboResult<ThreadPage>>()
    private suspend fun fetchDownloadPage(key: ThreadPageDownloadKey): YamiboResult<ThreadPage> {
        val task = currentCoroutineContext()[DownloadAccount] ?: downloadAccount()
        val request = PageRequest(task.storage, activeOwner(), task.revision, key)
        return pendingPages.read(request) { threadRepository.fetchThread(ThreadId(key.tid), key.authorId?.let(::UserId), page = key.page) }
    }

    private val pendingImages = me.thenano.yamibo.yamibo_app.repository.thread.InFlightReads<String, ByteArray>()
    private val readerIndex = database.readerDownloadQueries

    override suspend fun chapterAvailability(chapters: List<ThreadSummary>): Map<ThreadId, Boolean> = withContext(Dispatchers.IO) {
        val storage = storageProvider.getStorageKey() ?: return@withContext emptyMap()
        val owner = activeOwner()
        val ids = chapters.map { it.tid.value.toLong() }
        val indexed = ids.chunked(400).flatMap { readerIndex.availableTids(storage, owner.toLong(), it).executeAsList() }.toHashSet()
        val missing = ids.filterNot { it in indexed }.toHashSet()
        val legacyKeys = if (missing.isEmpty()) emptySet() else getDownloadedContentGroups().asSequence()
            .flatMap { it.items.asSequence() }.flatMap { it.sourceKeys.asSequence() }
            .map { it.pageKey.tid.toLong() }.filter { it in missing }.toSet()
        buildMap {
            for (chapter in chapters) {
                val tid = chapter.tid.value
                if (tid.toLong() !in indexed && tid.toLong() !in legacyKeys) continue
                val author = chapter.author?.uid
                val authorKey = ThreadPageDownloadKey(tid, 1, author?.value)
                val first = getDownloadedPage(authorKey)
                val sourceKey = if (first == null) authorKey.copy(authorId = null) else authorKey
                val page = first ?: getDownloadedPage(sourceKey)
                if (page == null) { put(chapter.tid, false); continue }
                var hasImages = false
                var complete = true
                for (number in 1..(page.pageNav?.totalPages ?: 1)) {
                    val local = if (number == 1) page else getDownloadedPage(sourceKey.copy(page = number))
                    if (local == null) { complete = false; break }
                    val images = local.readerImages(author)
                    if (images.isNotEmpty()) hasImages = true
                    if (images.any { MissingThreadImage.isReaderUri(it.url) }) { complete = false; break }
                }
                put(chapter.tid, complete && hasImages)
            }
        }
    }

    override suspend fun listedReaderPages(tid: ThreadId, author: UserId?): List<Int> = contentMutex.withLock {
        val storage = storageProvider.getStorageKey() ?: return@withLock emptyList()
        val catalog = contentCatalog?.takeIf { it.storageKey == storage && it.owner == accountId() }
        val groupId = "thread_${tid.value}_author_${author?.value ?: "all"}"
        catalog?.groups?.firstOrNull { it.id == groupId }?.items.orEmpty()
            .mapNotNull { (it.key as? ThreadPageDownloadKey)?.page }
    }

    override suspend fun downloadedPageForPost(tid: ThreadId, author: UserId?, pid: io.github.littlesurvival.dto.value.PostId) =
        withContext(Dispatchers.IO) {
            storageProvider.getStorageKey()?.let { storage ->
                library.pageForPost(storage, accountId(), tid.value, author?.value, pid.value) ?: readerIndex.pageForPid(storage, accountId().toLong(), tid.value.toLong(), author?.value?.toLong() ?: 0L,
                    pid.value.toLong()).executeAsOneOrNull()?.toInt()
            }
        }

    override suspend fun getDownloadedPage(key: ThreadPageDownloadKey): ThreadPage? {
        if (!storageProvider.mayHaveDownloads) return null
        return storedReaderPage(key, localImages = true)
    }

    private suspend fun storedReaderPage(key: ThreadPageDownloadKey, localImages: Boolean): ThreadPage? = withContext(Dispatchers.IO) {
        val storage = storageProvider.getStorageKey() ?: return@withContext null
        val owner = accountId()
        suspend fun page(source: DownloadTaskKey, bytes: ByteArray, manifest: DownloadManifest?): ThreadPage? {
            if (manifest?.owner != null && manifest.owner != owner) return null
            val original = decodePage(source, bytes)?.takeIf {
                it.isValidThreadPage(ThreadId(key.tid), page = key.page, authorId = key.authorId?.let(::UserId))
            } ?: return null
            return if (localImages) localPage(original, source, storage, manifest) else original
        }
        contentMutex.withLock {
            library.covered(key)?.let { (snapshot, item) ->
                return@withLock if (localImages) localPage(snapshot, item.key, storage, item) else snapshot
            }
            storageProvider.readPageRecord(key)?.let { (bytes, manifest) ->
                page(key, bytes, manifest)?.let { return@withLock it }
            }
            // A chapter qualifies only when its source page, scope and published generation match.
            val candidates = readerIndex.pageCopies(storage, owner.toLong(), key.tid.toLong(),
                key.authorId?.toLong() ?: 0L, key.page.toLong()).executeAsList()
            for (candidate in candidates) {
                val source = json.decodeFromString<DownloadTaskKey>(candidate.taskKey)
                if (source == key) continue
                val manifest = readManifest(source) ?: continue
                if (manifest.owner != owner || manifest.archiveId != candidate.generation) continue
                val bytes = storageProvider.readThreadPage(source) ?: continue
                page(source, bytes, manifest)?.let { return@withLock it }
            }
            null
        }?.takeIf { accountId() == owner }
    }

    private suspend fun localPage(page: ThreadPage, key: DownloadTaskKey, storage: String, manifest: DownloadManifest?): ThreadPage {
        val saved = manifest?.let { record ->
            val uris = imageUris(storage, record)
            record.images.associate { it.sourceUrl to uris[it.fileName] }
        }.orEmpty()
        val storageId = storage.encodeUtf8().sha256().hex()
        return page.mapThreadImages { source ->
            if (source.startsWith("data:image/", true)) source else saved[source] ?: MissingThreadImage(key, storageId, source).readerUri()
        }
    }

    private suspend fun originalForReader(key: ThreadPageDownloadKey): ThreadPage? =
        storedReaderPage(key, localImages = false)
            ?: threadRepository.getCachedThread(ThreadId(key.tid), key.authorId?.let(::UserId), key.page)

    private suspend fun pageForDownload(entry: DownloadQueueEntry, key: ThreadPageDownloadKey): ThreadPage? {
        // A failed refresh can leave the previous generation on disk. Retry the newer source.
        if (entry.refreshSource) return null
        return originalForReader(key)
    }

    private suspend fun hasCompletePage(key: ThreadPageDownloadKey): Boolean {
        val page = getDownloadedPage(key) ?: return false
        return page.posts.none { post -> post.readerImageUrls().any(MissingThreadImage::isReaderUri) }
    }

    private suspend fun readOriginalPage(key: ThreadPageDownloadKey): ThreadPage? {
        return storageProvider.readThreadPage(key)?.let { decodePage(key, it) }
    }

    private fun decodePage(key: DownloadTaskKey, bytes: ByteArray): ThreadPage? {
        return runCatching { json.decodeFromString<ThreadPage>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode downloaded page key=${key.stableId}", it) }
            .getOrNull()
    }

    override fun observeLocalImage(image: MissingThreadImage) = imageRevision.map {
        contentMutex.withLock {
            if (storageFor(image) == null) return@withLock null
            val manifest = readManifest(image.key) ?: return@withLock null
            if (manifest.owner != null && manifest.owner != accountId()) return@withLock null
            val saved = manifest.images.firstOrNull { it.sourceUrl == image.sourceUrl } ?: return@withLock null
            imageUris(checkNotNull(storageProvider.getStorageKey()), manifest, listOf(saved))[saved.fileName]
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun storageFor(image: MissingThreadImage): String? =
        storageProvider.getStorageKey()?.takeIf { it.encodeUtf8().sha256().hex() == image.storageId }

    private suspend fun resolveArchiveMember(storage: String, ref: ArchivedImage): Pair<DownloadManifest, DownloadedImage>? {
        val owner = accountId()
        check(ref.owner == null || ref.owner == owner) { "下載項目屬於其他帳號" }
        val item = readManifest(ref.key)
        if (item != null && (item.owner == null || item.owner == owner)) {
            item.images.find { it.sourceUrl.encodeUtf8().sha256().hex() == ref.sourceId }?.let { return item to it }
        }
        return library.imageInThread(storage, ref)
    }

    private suspend fun readerImageReference(uri: String): MissingThreadImage {
        val ref = requireNotNull(ArchivedImage.parse(uri))
        return contentMutex.withLock {
            val storage = checkNotNull(storageProvider.getStorageKey())
            check(storage.encodeUtf8().sha256().hex() == ref.storageId)
            val member = resolveArchiveMember(storage, ref)
            if (member != null) MissingThreadImage(member.first.key, ref.storageId, member.second.sourceUrl)
            else {
                // A failed refetch can leave an old reader URI on screen after its CBZ was retired.
                val item = checkNotNull(readManifest(ref.key)) { "下載圖片已移除" }
                check(item.shared && item.owner == accountId()) { "下載圖片已移除" }
                val sources = when (val key = item.key) {
                    is ThreadPageDownloadKey -> readOriginalPage(key)?.threadImageUrls().orEmpty()
                    else -> item.orderedSources()
                }
                val url = sources.firstOrNull { it.encodeUtf8().sha256().hex() == ref.sourceId }
                MissingThreadImage(item.key, ref.storageId, checkNotNull(url) { "圖片已移除" })
            }
        }
    }

    override suspend fun archivedImageSource(uri: String): String? = withContext(Dispatchers.IO) {
        downloadResult("read archived image source") { readerImageReference(uri).sourceUrl }.getOrNull()
    }

    override suspend fun retryArchivedImage(uri: String, failedLocalUri: String): Result<String> = withContext(Dispatchers.IO) {
        downloadResult("retry archived image") {
            retryImage(readerImageReference(uri), failedLocalUri).getOrThrow()
        }
    }

    override suspend fun retryImage(image: MissingThreadImage, failedLocalUri: String?): Result<String> {
        initialized.await()
        val account = downloadAccount()
        val identity = Triple(account.id, image, account.revision)
        val job = taskMutex.withLock {
            if (removing.any { it(image.key) }) return Result.failure(IllegalStateException("下載內容已移除"))
            imageRepairs[identity]?.takeUnless { it.isCompleted } ?: scope.async(account, start = CoroutineStart.LAZY) {
                try {
                    downloadResult("retryImage key=${image.key.stableId}") { libraryMutation.withLock { repairImage(image, failedLocalUri) } }
                } finally {
                    withContext(NonCancellable) { taskMutex.withLock { imageRepairs.remove(identity) } }
                }
            }.also { repair ->
                imageRepairs[identity] = repair
                repair.start()
            }
        }
        return job.await()
    }

    private suspend fun repairImage(image: MissingThreadImage, failedLocalUri: String?): String {
        val key = image.key
        suspend fun currentManifest(recover: Boolean): DownloadManifest? {
            check(storageFor(image) != null) { "儲存資料夾已變更" }
            val owner = activeOwner()
            val manifest = readManifest(key)
            check(manifest?.owner == null || manifest.owner == owner) { "下載項目屬於其他帳號" }
            if (key is ThreadPageDownloadKey) {
                val page = checkNotNull(readOriginalPage(key)) { "下載內容已移除" }
                check(image.sourceUrl in page.threadImageUrls()) { "圖片已從帖子移除" }
                return manifest ?: if (recover) recoverPageManifest(key, page) else null
            }
            check(manifest != null && image.sourceUrl in manifest.orderedSources()) { "圖片已從章節移除" }
            return manifest
        }
        val removedCorrupt = contentMutex.withLock {
            val manifest = currentManifest(recover = false) ?: return@withLock false
            val existing = manifest.images.find { it.sourceUrl == image.sourceUrl } ?: return@withLock false
            val storage = checkNotNull(storageFor(image))
            if (!manifest.shared && existing.packed && isDamagedMember(manifest, existing)) {
                // Commit the recoverable local content and its missing slots before removing this archive.
                migrateItem(storage, activeOwner(), manifest, removeCorrupt = true)
                true
            } else {
                val local = imageUris(storage, manifest, listOf(existing))[existing.fileName]
                if (local != null && local != failedLocalUri) return local
                false
            }
        }
        activeOwner()
        val bytes = if (removedCorrupt || failedLocalUri != null) imageFetcher.fetch(image.sourceUrl)
            else downloadedOrFetch(image.sourceUrl)
        activeOwner()
        return contentMutex.withLock {
            val manifest = checkNotNull(currentManifest(recover = true))
            val storage = checkNotNull(storageFor(image))
            val fileName = manifest.images.firstOrNull { it.sourceUrl == image.sourceUrl }?.fileName
                ?.takeIf { it.isSafeImageFileName() } ?: threadImageFileName(image.sourceUrl)
            val saved = if (manifest.shared) library.storeImage(storage, activeOwner(), image.sourceUrl, bytes, repair = true)
                else DownloadedImage(image.sourceUrl, fileName, bytes.size.toLong())
            val images = manifest.images.filterNot { it.sourceUrl == image.sourceUrl } + saved
            val missing = if (manifest.images.any { it.sourceUrl == image.sourceUrl }) manifest.missingImageCount
                else (manifest.missingImageCount - 1).coerceAtLeast(0)
            val updated = when (manifest) {
                is ThreadPageDownloadManifest -> manifest.copy(images = images, missingImageCount = missing)
                is TagMangaChapterManifest -> manifest.copy(images = images, sourceOrder = manifest.orderedSources(), missingImageCount = missing)
                is RssMangaChapterManifest -> manifest.copy(images = images, sourceOrder = manifest.orderedSources(), missingImageCount = missing)
            }
            val catalog = beginCatalogChange()
            val (uri, updatedClaims) = if (manifest.shared) {
                val page = storageProvider.readThreadPage(key)?.let { decodePage(key, it) }
                saved.archiveUri(image.storageId, key, checkNotNull(updated.archiveId)) to library.save(storage, updated, page)
            } else {
                storageProvider.writeImage(storage, key, PendingDownloadedImage(fileName, bytes), encodeManifest(updated)) to listOf(updated)
            }
            manifest.images.find { it.sourceUrl == image.sourceUrl }?.let { archives[storage to key]?.names?.remove(it.fileName) }
            finishCatalogChange(catalog, updated = updatedClaims)
            imageRevision.update { it + 1 }
            queueEntry(key, catalog.owner)?.let { entry ->
                if (manifest.missingImageCount > 0 && missing == 0 && !entry.refreshSource) {
                    upsert(entry.copy(status = DownloadStatus.Downloaded, message = null, progressCurrent = images.size,
                        progressTotal = images.size, updatedAt = currentTimeMillis()))
                } else upsert(entry.copy(updatedAt = currentTimeMillis()))
            }
            uri
        }
    }

    private suspend fun recoverPageManifest(key: ThreadPageDownloadKey, page: ThreadPage): ThreadPageDownloadManifest {
        val generation = Uuid.random().toString()
        val source = storageProvider.openArchive(key, generation)
        val entries = if (source == null) emptyMap() else try {
            val zip = ZipFile(source)
            try { zip.entries.associate { it.name to it.size } } finally { zip.close() }
        } catch (error: Exception) {
            source.close()
            if (error is CancellationException) throw error
            Logger.w(TAG, "Cannot index damaged archive for ${key.stableId}", error)
            emptyMap()
        }
        val loose = storageProvider.looseImages(key, null)
        val urls = page.threadImageUrls()
        val recovered = urls.mapNotNull { url ->
            // Only a source-derived name identifies an image after the manifest is lost.
            val name = threadImageFileName(url)
            val local = loose[name]
            when {
                local != null -> DownloadedImage(url, name, storageProvider.openLoose(local).buffer().use { it.readAll(okio.blackholeSink()) })
                name in entries -> DownloadedImage(url, name, entries.getValue(name), packed = true)
                else -> null
            }
        }
        return page.downloadManifest(key).copy(images = recovered, missingImageCount = urls.size - recovered.size,
            archiveId = generation.takeIf { entries.isNotEmpty() })
    }

    override suspend fun updateDownloadedPage(key: ThreadPageDownloadKey, page: ThreadPage): Boolean = withContext(Dispatchers.IO) {
        require(page.isValidThreadPage(ThreadId(key.tid), page = key.page, authorId = key.authorId?.let(::UserId)))
        initialized.await()
        contentMutex.withLock {
            val manifest = getManifest(key) ?: return@withContext false
            val urls = page.threadImageUrls()
            val saved = manifest.images.mapTo(hashSetOf()) { it.sourceUrl }
            val missing = urls.count { it !in saved }
            if (missing > 0 && manifest.missingImageCount == 0) return@withContext false
            val totalPages = page.pageNav?.totalPages ?: 1
            val updatedAt = currentTimeMillis()
            val updated = manifest.copy(
                    title = page.thread.title,
                    images = if (manifest.shared) manifest.images.filter { it.sourceUrl in urls } else manifest.images,
                    missingImageCount = missing,
                    downloadedAt = updatedAt,
                    sourceTotalPages = totalPages,
                    pageKind = if (key.page >= totalPages) DownloadPageKind.LastAtDownloadTime else DownloadPageKind.Normal,
                    forumId = page.thread.forum.fid.value,
                    forumName = page.thread.forum.name,
            )
            val catalog = beginCatalogChange()
            val updatedClaims = if (manifest.shared) {
                library.save(checkNotNull(catalog.storageKey), updated, page)
            } else {
                storageProvider.updateThreadPage(key, json.encodeToString(updated).encodeToByteArray(),
                    json.encodeToString(page).encodeToByteArray())
                listOf(updated)
            }
            finishCatalogChange(catalog, updated = updatedClaims)
            queueEntry(key, catalog.owner)?.let { entry ->
                upsert(entry.copy(title = page.thread.title, updatedAt = updatedAt))
            }
            true
        }
    }

    override suspend fun getManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? =
        withContext(Dispatchers.IO) { storageProvider.readManifest(key)?.takeIf { it.owner == null || it.owner == accountId() } }

    override suspend fun getTagMangaChapterImages(key: TagMangaChapterDownloadKey): List<String>? = withContext(Dispatchers.IO) {
        contentMutex.withLock {
            val manifest = storageProvider.readTagMangaManifest(key) ?: return@withLock null
            orderedUris(manifest).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun getTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? =
        withContext(Dispatchers.IO) { storageProvider.readTagMangaManifest(key)?.takeIf { it.owner == null || it.owner == accountId() } }

    override suspend fun getRssMangaChapterImages(key: RssMangaChapterDownloadKey): List<String>? = withContext(Dispatchers.IO) {
        contentMutex.withLock {
            val manifest = storageProvider.readRssMangaManifest(key) ?: return@withLock null
            orderedUris(manifest).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun getRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? =
        withContext(Dispatchers.IO) { storageProvider.readRssMangaManifest(key)?.takeIf { it.owner == null || it.owner == accountId() } }

    private suspend fun enqueueResult(operation: String, block: suspend () -> Unit): Result<Unit> =
        downloadResult(operation) {
            val context = downloadAccount()
            withContext(context) { block() }
        }

    override suspend fun enqueuePage(tid: ThreadId, title: String, authorId: UserId?, page: Int): Result<Unit> = enqueueResult("enqueuePage tid=${tid.value} page=$page authorId=${authorId?.value}") {
        initialized.await()
        ensureStorageReady()
        val key = ThreadPageDownloadKey(tid.value, page, authorId?.value)
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueThread(tid: ThreadId, title: String, authorId: UserId?): Result<Unit> = enqueueResult("enqueueThread tid=${tid.value} authorId=${authorId?.value}") {
        enqueueThreadPages(tid, title, authorId, includeLastPage = true)
    }

    override suspend fun enqueueThreadExceptLastPage(
        tid: ThreadId,
        title: String,
        authorId: UserId?,
    ): Result<Unit> = enqueueResult("enqueueThreadExceptLastPage tid=${tid.value} authorId=${authorId?.value}") {
        enqueueThreadPages(tid, title, authorId, includeLastPage = false)
    }

    private suspend fun enqueueThreadPages(
        tid: ThreadId,
        title: String,
        authorId: UserId?,
        includeLastPage: Boolean,
    ) {
        initialized.await()
        ensureStorageReady()
        val firstKey = ThreadPageDownloadKey(tid.value, 1, authorId?.value)
        val first = originalForReader(firstKey) ?: when (val result = threadRepository.fetchThread(tid, authorId, page = 1)) {
            is YamiboResult.Success -> result.value
            else -> error(result.message())
        }
        val totalPages = first.pageNav?.totalPages ?: 1
        val lastPageToQueue = if (includeLastPage) totalPages else totalPages - 1
        if (lastPageToQueue < 1) error("此 Thread 只有一頁，沒有可排除最後頁後下載的內容")
        val owner = activeOwner()
        val entries = (1..lastPageToQueue).mapNotNull { page ->
            val key = ThreadPageDownloadKey(tid.value, page, authorId?.value)
            if (queueEntry(key)?.status in setOf(DownloadStatus.Queued, DownloadStatus.Downloading)) return@mapNotNull null
            if (hasCompletePage(key)) return@mapNotNull null
            if (page == 1) prefetchedPages[owner to key] = first
            DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis())
        }
        upsertAll(entries)
        drainQueue()
    }

    override suspend fun enqueueTagMangaChapter(
        tagId: TagId,
        tagName: String,
        thread: ThreadSummary,
        tagPage: Int,
    ): Result<Unit> = enqueueResult("enqueueTagMangaChapter tagId=${tagId.value} tid=${thread.tid.value}") {
        initialized.await()
        ensureStorageReady()
        val key = tagMangaKey(tagId, thread)
        val title = tagMangaQueueTitle(tagName, thread.title)
        tagMangaTasks[activeOwner() to key] = TagMangaTaskInfo(tagName, thread.title, tagPage)
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueTagMangaCurrentPage(
        tagId: TagId,
        tagName: String,
        threads: List<ThreadSummary>,
        tagPage: Int,
    ): Result<Unit> = enqueueResult("enqueueTagMangaCurrentPage tagId=${tagId.value} page=$tagPage count=${threads.size}") {
        initialized.await()
        ensureStorageReady()
        queueTagMangaThreads(tagId, tagName, threads, tagPage)
        drainQueue()
    }

    override suspend fun enqueueTagMangaAllPages(tagId: TagId, tagName: String): Result<Unit> = enqueueResult("enqueueTagMangaAllPages tagId=${tagId.value}") {
        initialized.await()
        ensureStorageReady()
        val repository = tagRepository ?: error("TagRepository is required for tag manga downloads")
        val first = when (val result = repository.fetchTagPage(tagId, 1)) {
            is YamiboResult.Success -> result.value
            else -> error(result.message())
        }
        queueTagMangaPage(tagId, tagName, first, 1)
        drainQueue()
        val totalPages = first.pageNav?.totalPages ?: 1
        for (page in 2..totalPages) {
            when (val result = repository.fetchTagPage(tagId, page)) {
                is YamiboResult.Success -> {
                    queueTagMangaPage(tagId, tagName, result.value, page)
                    drainQueue()
                }
                else -> error(result.message())
            }
        }
    }

    override suspend fun enqueueRssMangaChapter(
        subscriptionId: Long,
        title: String,
        query: String,
        thread: ThreadSummary,
        page: Int,
    ): Result<Unit> = enqueueResult("enqueueRssMangaChapter subscriptionId=$subscriptionId tid=${thread.tid.value}") {
        initialized.await()
        ensureStorageReady()
        val key = rssMangaKey(subscriptionId, thread)
        val queueTitle = rssMangaQueueTitle(title, thread.title)
        rssMangaTasks[activeOwner() to key] = RssMangaTaskInfo(title, query, thread.title, page)
        upsert(DownloadQueueEntry(key, queueTitle, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueRssMangaCurrentPage(
        subscriptionId: Long,
        title: String,
        query: String,
        threads: List<ThreadSummary>,
        page: Int,
    ): Result<Unit> = enqueueResult("enqueueRssMangaCurrentPage subscriptionId=$subscriptionId page=$page count=${threads.size}") {
        initialized.await()
        ensureStorageReady()
        queueRssMangaThreads(subscriptionId, title, query, threads, page)
        drainQueue()
    }

    override suspend fun enqueueRssMangaAllPages(subscriptionId: Long, title: String, query: String): Result<Unit> = enqueueResult("enqueueRssMangaAllPages subscriptionId=$subscriptionId") {
        initialized.await()
        ensureStorageReady()
        val repository = rssRepository ?: error("RssSearchSubscriptionRepository is required for RSS manga downloads")
        val first = repository.getCatalogPage(subscriptionId, 1)
            ?: error("RSS 目錄無法載入")
        queueRssMangaPage(subscriptionId, title, query, first.tagPage, 1)
        drainQueue()
        val totalPages = first.tagPage.pageNav?.totalPages ?: 1
        for (page in 2..totalPages) {
            val catalogPage = repository.getCatalogPage(subscriptionId, page)
                ?: error("RSS 第 $page 頁無法載入")
            queueRssMangaPage(subscriptionId, title, query, catalogPage.tagPage, page)
            drainQueue()
        }
    }

    override suspend fun refreshPage(tid: ThreadId, title: String, authorId: UserId?, page: Int): YamiboResult<ThreadPage> = withContext(Dispatchers.IO) {
        initialized.await()
        val key = ThreadPageDownloadKey(tid.value, page, authorId?.value)
        refreshDownload(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            return@task when (val result = fetchDownloadPage(ThreadPageDownloadKey(tid.value, page, authorId?.value))) {
                is YamiboResult.Success -> {
                    try {
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Downloading, stage = DownloadStage.Preparing, updatedAt = currentTimeMillis()))
                        persistPage(key, title, result.value)
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Downloaded, updatedAt = currentTimeMillis()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "refreshPage failed key=${key.stableId}", e)
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Failed).failed(e))
                        return@task YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
                    }
                    result
                }
                else -> YamiboResult.Failure(result.message())
            }
        }
    }

    override suspend fun refreshTagMangaChapter(key: TagMangaChapterDownloadKey): YamiboResult<List<String>> = withContext(Dispatchers.IO) {
        initialized.await()
        refreshDownload(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            val oldManifest = storageProvider.readTagMangaManifest(key)
            val task = tagMangaTasks[activeOwner() to key]
            val tagName = oldManifest?.tagName ?: task?.tagName ?: ""
            val title = oldManifest?.title ?: task?.chapterTitle ?: queueEntry(key)?.title ?: key.stableId
            val tagPage = oldManifest?.tagPage ?: task?.tagPage ?: 1
            val result = fetchDownloadPage(key.pageKey)
            if (result !is YamiboResult.Success) {
                return@task YamiboResult.Failure(result.message())
            }
            return@task try {
                upsert(DownloadQueueEntry(key, tagMangaQueueTitle(tagName, title), DownloadStatus.Downloading, stage = DownloadStage.Preparing, updatedAt = currentTimeMillis()))
                val images = persistTagMangaChapter(key, tagName, title, tagPage, result.value)
                val queueTitle = tagMangaQueueTitle(tagName, title)
                upsert(DownloadQueueEntry(key, queueTitle, DownloadStatus.Downloaded, images.size, images.size, updatedAt = currentTimeMillis()))
                YamiboResult.Success(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "refreshTagMangaChapter failed key=${key.stableId}", e)
                upsert(DownloadQueueEntry(key, tagMangaQueueTitle(tagName, title), DownloadStatus.Failed).failed(e))
                YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
            }
        }
    }

    override suspend fun refreshRssMangaChapter(key: RssMangaChapterDownloadKey): YamiboResult<List<String>> = withContext(Dispatchers.IO) {
        initialized.await()
        refreshDownload(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            val oldManifest = storageProvider.readRssMangaManifest(key)
            val task = rssMangaTasks[activeOwner() to key]
            val subscriptionTitle = oldManifest?.subscriptionTitle ?: task?.subscriptionTitle ?: ""
            val subscriptionQuery = oldManifest?.subscriptionQuery ?: task?.subscriptionQuery ?: subscriptionTitle
            val title = oldManifest?.title ?: task?.chapterTitle ?: queueEntry(key)?.title ?: key.stableId
            val page = oldManifest?.subscriptionPage ?: task?.subscriptionPage ?: 1
            val result = fetchDownloadPage(key.pageKey)
            if (result !is YamiboResult.Success) return@task YamiboResult.Failure(result.message())
            return@task try {
                val queueTitle = rssMangaQueueTitle(subscriptionTitle, title)
                upsert(DownloadQueueEntry(key, queueTitle, DownloadStatus.Downloading, stage = DownloadStage.Preparing, updatedAt = currentTimeMillis()))
                val images = persistRssMangaChapter(key, subscriptionTitle, subscriptionQuery, title, page, result.value)
                upsert(DownloadQueueEntry(key, queueTitle, DownloadStatus.Downloaded, images.size, images.size, updatedAt = currentTimeMillis()))
                YamiboResult.Success(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "refreshRssMangaChapter failed key=${key.stableId}", e)
                upsert(DownloadQueueEntry(key, rssMangaQueueTitle(subscriptionTitle, title), DownloadStatus.Failed).failed(e))
                YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
            }
        }
    }

    override suspend fun markThreadUpdateAvailable(tid: ThreadId, authorId: UserId?) {
        initialized.await()
        contentMutex.withLock {
            withContext(Dispatchers.IO) { storageProvider.listManifests() }
                .filter { it.key.tid == tid.value && it.key.authorId == authorId?.value }
                .forEach { manifest ->
                    upsert(
                        DownloadQueueEntry(
                            key = manifest.key,
                            title = manifest.title,
                            status = DownloadStatus.UpdateAvailable,
                            message = "下載內容可刷新",
                            updatedAt = currentTimeMillis(),
                        )
                    )
                }
        }
    }

    override suspend fun removeTask(key: DownloadTaskKey) = removeOne(key, tasksOnly = true)
    override suspend fun clearPage(key: ThreadPageDownloadKey) = removeOne(key)
    override suspend fun clearTagMangaChapter(key: TagMangaChapterDownloadKey) = removeOne(key)
    override suspend fun clearRssMangaChapter(key: RssMangaChapterDownloadKey) = removeOne(key)

    private suspend fun removeOne(key: DownloadTaskKey, tasksOnly: Boolean = false): Result<Unit> =
        downloadResult("remove ${key.stableId}") {
            val error = removeItems(setOf(key), tasksOnly)[key]
            if (error != null) throw error
        }

    override suspend fun clearThread(key: ThreadPageDownloadKey) = removeGroup(key) {
        it is ThreadPageDownloadKey && it.tid == key.tid && it.authorId == key.authorId
    }
    override suspend fun clearTagManga(tagId: TagId) = removeGroup(TagMangaChapterDownloadKey(tagId.value, 0)) { it is TagMangaChapterDownloadKey && it.tagId == tagId.value }
    override suspend fun clearRssManga(subscriptionId: Long) = removeGroup(RssMangaChapterDownloadKey(subscriptionId, 0)) { it is RssMangaChapterDownloadKey && it.subscriptionId == subscriptionId }

    private suspend fun removeGroup(group: DownloadTaskKey, matches: (DownloadTaskKey) -> Boolean): Result<Unit> = downloadResult("removeGroup") {
        withContext(Dispatchers.IO) {
            withStoppedTasks(matches) {
                libraryMutation.withLock { contentMutex.withLock {
                    val storage = checkNotNull(storageProvider.getStorageKey())
                    val keys = storageProvider.groupKeys(storage, group) + queue.value.filter { it.visibleTo(accountId()) }.map { it.key }.filter(matches)
                    val failures = removeLocked(keys, false) {}
                    failures.values.firstOrNull()?.let { throw it }
                } }
            }
        }
    }

    override suspend fun removeItems(keys: Set<DownloadTaskKey>, tasksOnly: Boolean, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        var failures = emptyMap<DownloadTaskKey, Throwable>()
        val result = downloadResult("removeItems") {
            withStoppedTasks(keys::contains) {
                libraryMutation.withLock { contentMutex.withLock { failures = removeLocked(keys, tasksOnly, onProgress) } }
            }
        }
        result.exceptionOrNull()?.let { error -> keys.associateWith { error } } ?: failures
    }

    private suspend fun removeLocked(keys: Set<DownloadTaskKey>, tasksOnly: Boolean, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> {
        ensureStorageReady()
        val catalog = if (tasksOnly) null else beginCatalogChange()
        val failures = if (catalog == null) {
            queue.value.filter { it.key in keys && it.visibleTo(accountId()) && it.status !in setOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed) }
                .associate { it.key to IllegalStateException("任務已完成，請在下載內容管理中刪除") }
        } else {
            val errors = storageProvider.deleteItems(checkNotNull(catalog.storageKey), keys, onProgress)
            ensureStorageReady()
            check(storageProvider.getStorageKey() == catalog.storageKey) { "儲存資料夾已變更，請重新載入" }
            errors
        }
        val succeeded = keys - failures.keys
        if (catalog != null) {
            readerIndex.transaction {
                succeeded.forEach {
                    readerIndex.clearFloors(checkNotNull(catalog.storageKey), it.stableId)
                    readerIndex.clearPage(checkNotNull(catalog.storageKey), it.stableId)
                    readerIndex.clearImages(checkNotNull(catalog.storageKey), it.stableId)
                }
            }
            succeeded.forEach { archives.remove(catalog.storageKey to it)?.retire() }
            finishCatalogChange(catalog, succeeded)
            imageRevision.update { it + 1 }
        }
        if (succeeded.isNotEmpty()) forgetTasks(succeeded::contains)
        return failures
    }

    private suspend fun <T> refreshDownload(key: DownloadTaskKey, block: suspend () -> YamiboResult<T>): YamiboResult<T> =
        try {
            runDownloadTask(key, block = block) ?: YamiboResult.Failure("下載任務正在處理，請稍後重試")
        } catch (changed: DownloadScopeChanged) {
            YamiboResult.Failure(checkNotNull(changed.message), changed)
        }

    private suspend fun <T> runDownloadTask(
        key: DownloadTaskKey,
        expected: DownloadQueueEntry? = null,
        block: suspend () -> T,
    ): T? = supervisorScope {
        val task = taskMutex.withLock {
            if (removing.any { it(key) } || activeTasks.containsKey(key) ||
                expected != null && expected !in queue.value) return@supervisorScope null
            val owner = expected?.owner ?: accountId()
            val account = downloadAccount(owner)
            val previous = queueEntry(key, owner)
            async(account, start = CoroutineStart.LAZY) {
                activeOwner()
                val existing = readManifest(key)
                check(existing?.owner == null || existing.owner == owner) { "下載項目屬於其他帳號" }
                try {
                    block()
                } catch (changed: DownloadScopeChanged) {
                    _queue.update { entries -> entries.map { entry ->
                        if (entry.key != key || !entry.visibleTo(owner) || entry.status != DownloadStatus.Downloading) entry
                        else previous?.takeUnless { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
                            ?: entry.copy(status = DownloadStatus.Paused, stage = null)
                    } }
                    if (account.storage == storageProvider.getStorageKey()) writeQueueSnapshot()
                    throw changed
                }
            }.also {
                activeTasks[key] = it
                it.start()
            }
        }
        try {
            task.await()
        } finally {
            withContext(NonCancellable) {
                taskMutex.withLock { if (activeTasks[key] === task) activeTasks.remove(key) }
            }
        }
    }

    private suspend fun withStoppedTasks(matches: (DownloadTaskKey) -> Boolean, block: suspend () -> Unit) {
        initialized.await()
        removalMutex.withLock {
            val tasks = taskMutex.withLock {
                removing.add(matches)
                (activeTasks.filterKeys(matches).values + imageRepairs.filterKeys { matches(it.second.key) }.values)
                    .also { jobs -> jobs.forEach { it.cancel() } }
            }
            try {
                // Never join while holding the catalog/task lock: a finishing writer may need it.
                tasks.joinAll()
                block()
            } finally {
                withContext(NonCancellable) {
                    taskMutex.withLock {
                        // Failed removals remain visible and retryable, never stuck as Downloading.
                        _queue.update { entries -> entries.map {
                            if (matches(it.key) && it.visibleTo(accountId()) && it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Paused, stage = null)
                            else it
                        } }
                        removing.remove(matches)
                    }
                    backgroundController.onQueueChanged(queue.value)
                    persistQueue()
                    drainQueue()
                }
            }
        }
    }

    private suspend fun forgetTasks(matches: (DownloadTaskKey) -> Boolean) {
        val owner = accountId()
        fun removed(entry: DownloadQueueEntry) = entry.visibleTo(owner) && matches(entry.key)
        queueWriteMutex.withLock {
            // Persist removal first. On failure the task remains visible and retryable.
            saveQueue(queue.value.filterNot(::removed))
            taskMutex.withLock {
                prefetchedPages.keys.removeAll { (account, key) -> account == owner && matches(key) }
                tagMangaTasks.keys.removeAll { (account, key) -> account == owner && matches(key) }
                rssMangaTasks.keys.removeAll { (account, key) -> account == owner && matches(key) }
                _queue.update { entries -> entries.filterNot(::removed) }
            }
        }
        backgroundController.onQueueChanged(queue.value)
        persistQueue()
    }

    private suspend fun restoreQueue(storageKey: String?) = queueWriteMutex.withLock {
        if (storageKey == null || savedQueue?.first == storageKey) return@withLock
        val persisted = storageProvider.readQueue()
        check(storageProvider.getStorageKey() == storageKey) { "儲存資料夾已變更，請重新載入" }
        savedQueue = storageKey to persisted
        val restored = pruneExpiredQueueEntries(persisted, currentTimeMillis()).map { entry ->
            if (entry.status == DownloadStatus.Downloading) entry.copy(status = DownloadStatus.Queued) else entry
        }.sortedBy { it.key.stableId }
        _queue.value = restored
        backgroundController.onQueueChanged(restored)
        saveQueue(restored)
    }

    private suspend fun writeQueueSnapshot(revision: Long = accessRevision()) = queueWriteMutex.withLock {
        // An unreadable tree may still contain a queue. Only replace a snapshot we have actually read.
        if (revision == accessRevision() && savedQueue?.first != null && savedQueue?.first == storageProvider.getStorageKey()) {
            saveQueue(queue.value)
        }
    }

    private suspend fun saveQueue(entries: List<DownloadQueueEntry>) {
        val snapshot = storageProvider.getStorageKey() to entries
        if (snapshot == savedQueue) return
        storageProvider.writeQueue(entries)
        savedQueue = snapshot
    }

    override suspend fun retry(key: DownloadTaskKey): Result<Unit> = enqueueResult("retry key=${key.stableId}") {
        initialized.await()
        ensureStorageReady()
        val previous = queueEntry(key)
        val title = previous?.title ?: when (key) {
            is ThreadPageDownloadKey -> storageProvider.readManifest(key)?.title
            is TagMangaChapterDownloadKey -> storageProvider.readTagMangaManifest(key)?.let {
                tagMangaQueueTitle(it.tagName, it.title)
            }
            is RssMangaChapterDownloadKey -> storageProvider.readRssMangaManifest(key)?.let {
                rssMangaQueueTitle(it.subscriptionTitle, it.title)
            }
        } ?: key.stableId
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis(),
            refreshSource = previous?.refreshSource == true))
        drainQueue()
    }

    override fun pauseAll() {
        paused = true
        _queue.update { entries ->
            entries.map { if (it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Paused) else it }
        }
        persistQueue()
    }

    override fun resumeAll() {
        paused = false
        _queue.update { entries ->
            entries.map { if (it.status == DownloadStatus.Paused) it.copy(status = DownloadStatus.Queued) else it }
        }
        persistQueue()
        scope.launch { drainQueue() }
    }

    private fun drainQueue() {
        scope.launch {
            workerMutex.withLock {
                while (!paused) {
                    val next = taskMutex.withLock {
                        val owner = accountId()
                        queue.value.firstOrNull { entry -> entry.visibleTo(owner) && entry.status == DownloadStatus.Queued &&
                            entry.key !in activeTasks && removing.none { it(entry.key) } }
                    } ?: break
                    process(next)
                }
            }
        }
    }

    private suspend fun process(entry: DownloadQueueEntry) {
        val owner = entry.owner ?: accountId()
        try {
            runDownloadTask(entry.key, expected = entry) {
                upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.Preparing, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
                when (val key = entry.key) {
                    is ThreadPageDownloadKey -> processThreadPage(entry, key)
                    is TagMangaChapterDownloadKey -> processTagMangaChapter(entry, key)
                    is RssMangaChapterDownloadKey -> processRssMangaChapter(entry, key)
                }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (_: DownloadScopeChanged) {
            // runDownloadTask restored the previous queue entry for this account.
        } catch (error: Exception) {
            Logger.e(TAG, "Download task failed key=${entry.key.stableId}", error)
            taskMutex.withLock {
                if (removing.none { it(entry.key) }) {
                    _queue.update { entries -> entries.map {
                        if (it.key == entry.key && it.visibleTo(owner)) it.copy(status = DownloadStatus.Failed, stage = null, message = error.message) else it
                    } }
                }
            }
            persistQueue()
        }
    }

    private suspend fun processThreadPage(entry: DownloadQueueEntry, key: ThreadPageDownloadKey) {
        val prefetched = prefetchedPages.remove(activeOwner() to key)
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        val result = (prefetched ?: pageForDownload(entry, key))?.let { YamiboResult.Success(it) }
            ?: fetchDownloadPage(key)
        when (result) {
            is YamiboResult.Success -> {
                if (queueEntry(key, entry.owner)?.status != DownloadStatus.Downloading) return
                persistEntry(
                    entry = entry,
                    key = key,
                    operation = "processThreadPage",
                    persist = {
                        persistPage(key, entry.title, result.value)
                    },
                    successProgress = success@{
                        val current = queueEntry(key, entry.owner)
                        val total = current?.progressTotal ?: return@success 0 to 0
                        total to total
                    },
                )
            }
            else -> upsert(entry.copy(status = DownloadStatus.Failed, stage = null, message = result.message(), updatedAt = currentTimeMillis()))
        }
    }

    private suspend fun processTagMangaChapter(entry: DownloadQueueEntry, key: TagMangaChapterDownloadKey) {
        val task = tagMangaTasks[activeOwner() to key]
        val oldManifest = storageProvider.readTagMangaManifest(key)
        val tagName = oldManifest?.tagName ?: task?.tagName ?: ""
        val title = oldManifest?.title ?: task?.chapterTitle ?: entry.title
        val tagPage = oldManifest?.tagPage ?: task?.tagPage ?: 1
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        when (val result = pageForDownload(entry, ThreadPageDownloadKey(key.tid, 1, key.authorId))?.let { YamiboResult.Success(it) }
            ?: fetchDownloadPage(key.pageKey)) {
            is YamiboResult.Success -> {
                if (queueEntry(key, entry.owner)?.status != DownloadStatus.Downloading) return
                persistEntry(
                    entry = entry,
                    key = key,
                    operation = "processTagMangaChapter",
                    persist = { persistTagMangaChapter(key, tagName, title, tagPage, result.value) },
                    successProgress = { images -> images.size to images.size },
                )
            }
            else -> upsert(entry.copy(status = DownloadStatus.Failed, stage = null, message = result.message(), updatedAt = currentTimeMillis()))
        }
    }

    private suspend fun processRssMangaChapter(entry: DownloadQueueEntry, key: RssMangaChapterDownloadKey) {
        val task = rssMangaTasks[activeOwner() to key]
        val oldManifest = storageProvider.readRssMangaManifest(key)
        val subscriptionTitle = oldManifest?.subscriptionTitle ?: task?.subscriptionTitle ?: ""
        val subscriptionQuery = oldManifest?.subscriptionQuery ?: task?.subscriptionQuery ?: subscriptionTitle
        val title = oldManifest?.title ?: task?.chapterTitle ?: entry.title
        val page = oldManifest?.subscriptionPage ?: task?.subscriptionPage ?: 1
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        when (val result = pageForDownload(entry, ThreadPageDownloadKey(key.tid, 1, key.authorId))?.let { YamiboResult.Success(it) }
            ?: fetchDownloadPage(key.pageKey)) {
            is YamiboResult.Success -> {
                if (queueEntry(key, entry.owner)?.status != DownloadStatus.Downloading) return
                persistEntry(
                    entry = entry,
                    key = key,
                    operation = "processRssMangaChapter",
                    persist = { persistRssMangaChapter(key, subscriptionTitle, subscriptionQuery, title, page, result.value) },
                    successProgress = { images -> images.size to images.size },
                )
            }
            else -> upsert(entry.copy(status = DownloadStatus.Failed, stage = null, message = result.message(), updatedAt = currentTimeMillis()))
        }
    }

    private suspend fun <T> persistEntry(
        entry: DownloadQueueEntry,
        key: DownloadTaskKey,
        operation: String,
        persist: suspend () -> T,
        successProgress: (T) -> Pair<Int, Int>,
    ) {
        try {
            val result = persist()
            val (current, total) = successProgress(result)
            upsert(
                entry.copy(
                    status = DownloadStatus.Downloaded,
                    progressCurrent = current,
                    progressTotal = total,
                    stage = null,
                    updatedAt = currentTimeMillis(),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.e(TAG, "$operation persist failed key=${key.stableId}", error)
            upsert(entry.failed(error))
        }
    }

    private suspend fun readManifest(key: DownloadTaskKey): DownloadManifest? = when (key) {
        is ThreadPageDownloadKey -> storageProvider.readManifest(key)
        is TagMangaChapterDownloadKey -> storageProvider.readTagMangaManifest(key)
        is RssMangaChapterDownloadKey -> storageProvider.readRssMangaManifest(key)
    }

    private suspend fun archive(storage: String, manifest: DownloadManifest): OpenArchive? {
        val key = manifest.key
        val generation = manifest.archiveId ?: return null
        val id = storage to key
        archives.remove(id)?.let { old ->
            if (old.generation == generation) { archives[id] = old; return old }
            old.retire()
        }
        val source = storageProvider.openArchive(key, generation) ?: return null
        val zip = try { ZipFile(source) } catch (error: Throwable) { source.close(); throw error }
        if (archives.size >= 4) archives.remove(archives.keys.first())?.retire()
        val names = zip.entries.mapTo(hashSetOf()) { it.name }
        names.retainAll(manifest.images.filter { it.packed }.map { it.fileName }.toSet())
        return OpenArchive(manifest.owner, generation, zip, names).also { archives[id] = it }
    }

    private suspend fun imageUris(storage: String, manifest: DownloadManifest,
                                  images: List<DownloadedImage> = manifest.images): Map<String, String> {
        if (manifest.shared) {
            check(manifest.owner == accountId())
            val storageId = storage.encodeUtf8().sha256().hex()
            return images.associate { image -> image.fileName to image.archiveUri(storageId, manifest.key, checkNotNull(manifest.archiveId), manifest.owner) }
        }
        val safe = images.filter { it.fileName.isSafeImageFileName() }
        val storageId = storage.encodeUtf8().sha256().hex()
        val loose = safe.filterNot { it.packed }
        val paths = if (loose.isEmpty()) emptyMap() else storageProvider.looseImages(manifest.key, loose.map { it.fileName })
        val urls = mutableMapOf<String, String>()
        for (image in loose) {
            val path = paths[image.fileName] ?: continue
            // Old histories can still point to this physical path during the first restore after an upgrade.
            val uri = image.archiveUri(storageId, manifest.key, "loose", accountId(),
                legacySourceId = path.encodeUtf8().sha256().hex())
            urls[image.fileName] = uri
            looseImagePaths[uri] = path
            if (looseImagePaths.size > 128) looseImagePaths.remove(looseImagePaths.keys.first())
        }
        val generation = manifest.archiveId ?: return urls
        if (safe.none { it.packed }) return urls
        val names = try {
            archive(storage, manifest)?.names.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(TAG, "Image archive unavailable for ${manifest.key.stableId}", error)
            emptySet()
        }
        for (image in safe) if (image.packed && image.fileName in names) {
            urls[image.fileName] = image.archiveUri(storageId, manifest.key, generation)
        }
        return urls
    }

    private fun DownloadedImage.archiveUri(storageId: String, key: DownloadTaskKey, generation: String, owner: Int? = null,
                                           legacySourceId: String? = null) =
        ArchivedImage(storageId, key, fileName, generation, sourceUrl.encodeUtf8().sha256().hex(), owner,
            digest.takeIf { owner != null }, legacySourceId).uri()

    private suspend fun orderedUris(manifest: DownloadManifest): List<String> {
        if (manifest.owner != null && manifest.owner != accountId()) return emptyList()
        val storage = checkNotNull(storageProvider.getStorageKey())
        val uris = imageUris(storage, manifest)
        val bySource = manifest.images.associate { it.sourceUrl to uris[it.fileName] }
        val storageId = storage.encodeUtf8().sha256().hex()
        return manifest.orderedSources().map { source ->
            if (source.startsWith("data:image/", ignoreCase = true)) source
            else bySource[source] ?: MissingThreadImage(manifest.key, storageId, source).readerUri()
        }
    }

    override suspend fun openArchivedImage(uri: String): okio.Source = withContext(Dispatchers.IO) {
        val ref = requireNotNull(ArchivedImage.parse(uri))
        contentMutex.withLock {
            val storage = checkNotNull(storageProvider.getStorageKey())
            check(storage.encodeUtf8().sha256().hex() == ref.storageId)
            check(ref.owner == null || ref.owner == accountId()) { "下載項目屬於其他帳號" }
            if (ref.generation == "loose") {
                looseImagePaths[uri]?.let { path ->
                    try {
                        return@withLock storageProvider.openLoose(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // SAF can replace a document independently of a download or migration.
                        looseImagePaths.remove(uri)
                    }
                }
            }
            ref.digest?.let { digest ->
                library.openImage(storage, checkNotNull(ref.owner), digest)?.let { return@withLock it }
            }
            val cached = archives[storage to ref.key]?.takeIf { it.owner == null || it.owner == accountId() }
            var member = ref.fileName
            val open = if (cached?.generation == ref.generation && member in cached.names) cached else {
                val (saved, image) = checkNotNull(resolveArchiveMember(storage, ref)) { "下載圖片已移除" }
                member = image.fileName
                image.digest?.let { return@withLock checkNotNull(library.openImage(storage, checkNotNull(saved.owner), it)) { "下載的圖片檔案遺失" } }
                if (!image.packed) {
                    val loose = storageProvider.looseImages(saved.key, listOf(image.fileName)).getValue(image.fileName)
                    return@withLock storageProvider.openLoose(loose)
                }
                checkNotNull(archive(storage, saved))
            }
            val source = open.zip.getInputStream(member).asSource()
            open.readers++
            object : okio.ForwardingSource(source) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try { super.close() } finally {
                        scope.launch {
                            contentMutex.withLock {
                                open.readers--
                                if (open.retired && open.readers == 0) open.zip.close()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ZipFile.imageBytes(name: String): ByteArray =
        getInputStream(name).asSource().buffer().use { it.readByteArray() }

    private fun encodeManifest(manifest: DownloadManifest) = when (manifest) {
        is ThreadPageDownloadManifest -> json.encodeToString(manifest)
        is TagMangaChapterManifest -> json.encodeToString(manifest)
        is RssMangaChapterManifest -> json.encodeToString(manifest)
    }.encodeToByteArray()

    private fun remapCovers(aliases: Map<String, String>) {
        database.transaction {
            val queries = database.contentCoverQueries
            for ((url) in queries.localImageReferences().executeAsList()) {
                if (url == null) continue
                val replacement = aliases[url.removePrefix("https://bbs.yamibo.com/")] ?: continue
                queries.remapLocalImage(newUrl = replacement, oldUrl = url)
            }
        }
    }

    private class MissingImages(val present: Int, val total: Int, val keptComplete: Boolean, cause: Throwable) :
        IllegalStateException("尚缺 ${total - present} 張圖片（已取得 $present/$total）" +
            (if (keptComplete) "，已保留原有完整下載" else "，已儲存可閱讀的部分") + "。原因：" + imageFailureReason(cause), cause)

    private fun DownloadQueueEntry.failed(error: Throwable): DownloadQueueEntry {
        val missing = error as? MissingImages
        return copy(status = DownloadStatus.Failed, stage = null, message = error.message,
            progressCurrent = missing?.present ?: progressCurrent, progressTotal = missing?.total ?: progressTotal,
            refreshSource = missing?.keptComplete ?: refreshSource, updatedAt = currentTimeMillis())
    }

    private suspend fun isCompleteCopy(storage: String, manifest: DownloadManifest?): Boolean {
        if (manifest == null || manifest.missingImageCount > 0) return false
        val expected = when (val key = manifest.key) {
            is ThreadPageDownloadKey -> readOriginalPage(key)?.threadImageUrls() ?: return false
            else -> manifest.orderedSources().filter { it.startsWith("https://") || it.startsWith("http://") }.toSet()
        }
        val uris = imageUris(storage, manifest)
        val available = manifest.images.filter { it.fileName in uris }.mapTo(hashSetOf()) { it.sourceUrl }
        return expected.all { it in available }
    }

    private suspend fun persistPage(key: ThreadPageDownloadKey, title: String, page: ThreadPage) {
        val urls = page.threadImageUrls()
        saveContent(key, page, urls) { images, missing, generation ->
            page.downloadManifest(key).copy(title = title, images = images, owner = accountId(), archiveId = generation,
                shared = true, missingImageCount = missing)
        }
        threadRepository.setCachedThread(ThreadId(key.tid), key.authorId?.let(::UserId), key.page, page)
    }

    private suspend fun persistTagMangaChapter(key: TagMangaChapterDownloadKey, tagName: String, title: String,
                                               tagPage: Int, page: ThreadPage) = persistChapter(key, key.authorId, page) { images, order, generation, missing ->
        TagMangaChapterManifest(key, tagName, title = title, tagPage = tagPage, imageCount = order.size,
            downloadedAt = currentTimeMillis(), images = images, archiveId = generation, owner = accountId(), missingImageCount = missing, sourceOrder = order.takeUnless { it == images.map(DownloadedImage::sourceUrl) }.orEmpty())
    }

    private suspend fun persistRssMangaChapter(key: RssMangaChapterDownloadKey, subscriptionTitle: String,
                                               subscriptionQuery: String, title: String, subscriptionPage: Int, page: ThreadPage) =
        persistChapter(key, key.authorId, page) { images, order, generation, missing ->
            RssMangaChapterManifest(key, subscriptionTitle, subscriptionQuery, title = title, subscriptionPage = subscriptionPage,
                imageCount = order.size, downloadedAt = currentTimeMillis(), images = images, archiveId = generation, owner = accountId(), missingImageCount = missing, sourceOrder = order.takeUnless { it == images.map(DownloadedImage::sourceUrl) }.orEmpty())
        }

    private suspend fun persistChapter(key: DownloadTaskKey, authorId: Int?, page: ThreadPage,
                                      manifest: (List<DownloadedImage>, List<String>, String, Int) -> DownloadManifest): List<String> {
        val order = page.readerImages(authorId?.let(::UserId)).map { it.url }
        check(order.isNotEmpty()) { "此章節沒有可下載圖片" }
        val saved = saveContent(key, page, order) { images, missing, generation ->
            manifest(images, order, generation, missing).sharedCopy(images, accountId(), generation)
        }
        return orderedUris(saved)
    }

    private suspend fun saveContent(key: DownloadTaskKey, page: ThreadPage, order: Collection<String>,
                                    make: (List<DownloadedImage>, Int, String) -> DownloadManifest): DownloadManifest =
        libraryMutation.withLock {
            val storage = checkNotNull(storageProvider.getStorageKey())
            val owner = activeOwner()
            val task = currentCoroutineContext()[DownloadAccount]
            check(task == null || task.storage == storage) { "儲存資料夾已變更" }
            val old = readManifest(key)
            val urls = order.distinct().filter { it.startsWith("http://") || it.startsWith("https://") }
            val previous = library.covered(key.pageKey)?.first?.posts.orEmpty().associateBy { it.pid }
            val revised = page.posts.filter { post ->
                val prior = previous[post.pid]
                prior != null && post.lastEditedTime != prior.lastEditedTime
            }.flatMapTo(mutableSetOf()) { it.readerImageUrls() }
            val images = mutableListOf<DownloadedImage>()
            var firstFailure: Throwable? = null
            for (chunk in urls.chunked(3)) {
                coroutineScope {
                    chunk.map { url -> async {
                        try {
                            val hit = if (url in revised) null else library.knownImage(storage, owner, url)
                            Result.success(hit ?: library.storeImage(storage, owner, url,
                                if (url in revised) imageFetcher.fetch(url).also { activeOwner() } else downloadedOrFetch(url)))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            Logger.w(TAG, "Cannot save downloaded image", e)
                            Result.failure(e)
                        }
                    } }.awaitAll().forEach { result ->
                        result.onSuccess(images::add).onFailure { if (firstFailure == null) firstFailure = it }
                    }
                }
                updateStage(key, DownloadStage.DownloadingImages, images.size, urls.size)
            }
            val missing = urls.size - images.size
            val item = make(images, missing, Uuid.random().toString())
            contentMutex.withLock {
                check(owner == activeOwner() && storageProvider.getStorageKey() == storage)
                if (missing > 0 && isCompleteCopy(storage, old)) throw MissingImages(images.size, urls.size, true, checkNotNull(firstFailure))
                val catalog = beginCatalogChange()
                val updatedClaims = library.save(storage, item, page, observedAt = task?.observedAt ?: currentTimeMillis())
                archives.remove(storage to key)?.retire()
                finishCatalogChange(catalog, updated = updatedClaims)
                imageRevision.update { it + 1 }
            }
            if (missing > 0) throw MissingImages(images.size, urls.size, false, checkNotNull(firstFailure))
            item
        }

    private suspend fun isDamagedMember(item: DownloadManifest, member: DownloadedImage): Boolean {
        val source = library.legacy.openArchive(item.key, item.archiveId.orEmpty()) ?: return false
        val zip = try { ZipFile(source) }
            catch (_: ZipFormatException) { source.close(); return true }
            catch (error: Throwable) { source.close(); throw error }
        return try {
            zip.checkedImage(member, zip.entries.firstOrNull { it.name == member.fileName })
            false
        } catch (_: ZipFormatException) { true }
        finally { zip.close() }
    }

    private fun ZipFile.checkedImage(image: DownloadedImage, entry: ZipEntry?): ByteArray {
        if (entry == null) throw ZipFormatException("Missing image")
        val data = imageBytes(image.fileName)
        if (data.size.toLong() != image.bytes || Crypto.crc32(data) != entry.crc) throw ZipFormatException("Invalid image size or CRC")
        return data
    }

    override suspend fun migrateImages(removeCorruptCbz: Boolean, onCorruptRemoved: () -> Unit, onProgress: (Int, Int) -> Unit): Map<DownloadTaskKey, Throwable> = withContext(Dispatchers.IO) {
        val owner = accountId()
        val storage = checkNotNull(storageProvider.getStorageKey())
        val oldStore = library.legacy
        val targets = (oldStore.listManifests() + oldStore.listTagMangaManifests() + oldStore.listRssMangaManifests())
            .filter { it.owner == null || it.owner == owner }.distinctBy { it.key }
        val errors = mutableMapOf<DownloadTaskKey, Throwable>()
        onProgress(0, targets.size)
        for ((index, target) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            downloadResult("migrate ${target.key.stableId}") {
                libraryMutation.withLock {
                    contentMutex.withLock {
                        check(owner == accountId() && storage == storageProvider.getStorageKey())
                        if (migrateItem(storage, owner, target, removeCorruptCbz)) onCorruptRemoved()
                    }
                }
            }.exceptionOrNull()?.let { errors[target.key] = it }
            onProgress(index + 1, targets.size)
        }
        libraryMutation.withLock { library.reconcile(storage, owner, collect = true) }
        errors
    }

    // Storage access, password and unsupported format errors must keep their original files.
    private inline fun <T> inspectCbz(removeCorrupt: Boolean, read: () -> T): T? =
        try { read() } catch (error: ZipFormatException) {
            if (!removeCorrupt) throw IllegalStateException("CBZ 損壞，已保留原檔", error)
            null
        }

    private suspend fun migrateItem(storage: String, owner: Int, old: DownloadManifest, removeCorrupt: Boolean): Boolean {
        val key = old.key
        val pageBytes = library.legacy.readThreadPage(key)
        val page = pageBytes?.let { checkNotNull(decodePage(key, it)) }
        if (key is ThreadPageDownloadKey) checkNotNull(page) { "舊版正文不完整" }
        val packed = old.images.filter { it.packed }
        val loose = library.legacy.looseImages(key, null)
        val looseNames = old.images.filterNot { it.packed }.map { it.fileName }.toSet()
        check(loose.keys == looseNames) { "舊圖片與下載清單不符，已保留原檔" }
        val aliases = mutableMapOf<String, String>()
        val images = mutableListOf<DownloadedImage>()
        val source = library.legacy.openArchive(key, old.archiveId.orEmpty())
        check(source != null || packed.isEmpty()) { "找不到 CBZ，已保留原檔" }
        val zip = source?.let {
            try { inspectCbz(removeCorrupt) { ZipFile(it) }.also { opened -> if (opened == null) it.close() } }
            catch (error: Throwable) { it.close(); throw error }
        }
        val missing = mutableSetOf<String>()
        try {
            check(source == null || packed.isNotEmpty() || zip != null) { "CBZ 與下載清單不符，已保留原檔" }
            val entries = zip?.entries.orEmpty().filterNot { it.name.endsWith("/") }
            val expected = packed.mapTo(hashSetOf()) { it.fileName }
            val byName = entries.associateBy { it.name }
            check(byName.size == entries.size && (expected + looseNames).containsAll(byName.keys)) { "CBZ 含有無法對應的圖片，已保留原檔" }
            check(old.images.distinctBy { it.fileName }.size == old.images.size) { "舊圖片檔名重複，已保留原檔" }
            for (image in old.images) {
                currentCoroutineContext().ensureActive()
                require(image.fileName.isSafeImageFileName())
                val bytes = if (image.packed) {
                    inspectCbz(removeCorrupt) {
                        if (zip == null) throw ZipFormatException("Missing ZIP directory")
                        zip.checkedImage(image, byName[image.fileName])
                    }
                } else {
                    val data = library.legacy.openLoose(loose.getValue(image.fileName)).buffer().use { it.readByteArray() }
                    check(data.size.toLong() == image.bytes) { "舊版散圖長度不符，已保留原檔" }
                    data
                }
                if (bytes == null) missing += image.sourceUrl
                else images += library.storeImage(storage, owner, image.sourceUrl, bytes, verifyExisting = true)
            }
        } finally { zip?.close() }
        val generation = old.archiveId ?: Uuid.random().toString()
        val imported = old.sharedCopy(images, owner, generation)
        val count = old.missingImageCount + (missing - images.map { it.sourceUrl }.toSet()).size
        val saved = when (imported) {
            is ThreadPageDownloadManifest -> imported.copy(missingImageCount = count)
            is TagMangaChapterManifest -> imported.copy(missingImageCount = count, sourceOrder = old.orderedSources())
            is RssMangaChapterManifest -> imported.copy(missingImageCount = count, sourceOrder = old.orderedSources())
        }
        val existing = readManifest(key)
        val catalog = beginCatalogChange()
        val updatedClaims = if (existing?.shared == true) {
            check(existing.images.map { it.sourceUrl to it.digest } == images.map { it.sourceUrl to it.digest } &&
                (page == null || storageProvider.readThreadPage(key)?.let { decodePage(key, it) } == page)) {
                "舊下載與已儲存版本不同，保留原檔"
            }
            listOf(existing)
        } else {
            library.save(storage, saved, page, migrating = true)
        }
        val stored = checkNotNull(readManifest(key))
        check(stored.shared && stored.images == saved.images && stored.missingImageCount == count)
        if (key !is ThreadPageDownloadKey) check(stored.orderedSources() == old.orderedSources())
        if (page != null) check(storageProvider.readThreadPage(key)?.let { decodePage(key, it) } == page)
        val bySource = images.associateBy { it.sourceUrl }
        for (image in old.images) {
            val importedImage = bySource[image.sourceUrl] ?: continue
            val replacement = importedImage.archiveUri(storage.encodeUtf8().sha256().hex(), key, checkNotNull(stored.archiveId))
            loose[image.fileName]?.let { aliases[it] = replacement }
        }
        database.transaction {
            remapCovers(aliases)
            for (image in old.images) {
                val uri = loose[image.fileName] ?: continue
                val previous = uri.encodeUtf8().sha256().hex()
                val replacement = image.sourceUrl.encodeUtf8().sha256().hex()
                readerIndex.remapImagePosition(replacement, previous, key.pageKey.tid.toLong())
                readerIndex.remapTextPosition(replacement, previous, key.pageKey.tid.toLong())
            }
        }
        check(owner == activeOwner() && storage == storageProvider.getStorageKey())
        archives.remove(storage to key)?.retire()
        library.legacy.deleteItems(storage, setOf(key)) {}[key]?.let { throw it }
        finishCatalogChange(catalog, updated = updatedClaims, migrated = key)
        queueCatalogScope = null
        imageRevision.update { it + 1 }
        return missing.isNotEmpty()
    }

    private suspend fun queueTagMangaPage(tagId: TagId, tagName: String, page: TagPage, pageNumber: Int) {
        queueTagMangaThreads(tagId, tagName, page.threadSummaries, pageNumber)
    }

    private suspend fun queueTagMangaThreads(
        tagId: TagId,
        tagName: String,
        threads: List<ThreadSummary>,
        pageNumber: Int,
    ) {
        val owner = activeOwner()
        val entries = threads.map { thread ->
            val key = tagMangaKey(tagId, thread)
            val title = tagMangaQueueTitle(tagName, thread.title)
            tagMangaTasks[owner to key] = TagMangaTaskInfo(tagName, thread.title, pageNumber)
            DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis())
        }
        upsertAll(entries)
    }

    private suspend fun queueRssMangaPage(
        subscriptionId: Long,
        title: String,
        query: String,
        page: TagPage,
        pageNumber: Int,
    ) {
        queueRssMangaThreads(subscriptionId, title, query, page.threadSummaries, pageNumber)
    }

    private suspend fun queueRssMangaThreads(
        subscriptionId: Long,
        title: String,
        query: String,
        threads: List<ThreadSummary>,
        pageNumber: Int,
    ) {
        val owner = activeOwner()
        val entries = threads.map { thread ->
            val key = rssMangaKey(subscriptionId, thread)
            val queueTitle = rssMangaQueueTitle(title, thread.title)
            rssMangaTasks[owner to key] = RssMangaTaskInfo(title, query, thread.title, pageNumber)
            DownloadQueueEntry(key, queueTitle, DownloadStatus.Queued, updatedAt = currentTimeMillis())
        }
        upsertAll(entries)
    }

    private suspend fun downloadedOrFetch(source: String): ByteArray {
        val storage = checkNotNull(storageProvider.getStorageKey())
        val owner = activeOwner()
        val id = source.encodeUtf8().sha256().hex()
        val revision = currentCoroutineContext()[DownloadAccount]?.revision ?: accessRevision()
        return pendingImages.read("$storage|$owner|$revision|$id") {
            val copies = readerIndex.imageCopies(storage, owner.toLong(), id).executeAsList()
            for (copy in copies) {
                val key = json.decodeFromString<DownloadTaskKey>(copy.taskKey)
                val ref = ArchivedImage(storage.encodeUtf8().sha256().hex(), key, copy.fileName, copy.generation, id)
                val bytes = try { openArchivedImage(ref.uri()).buffer().use { it.readByteArray() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { null }
                if (bytes != null && bytes.size.toLong() == copy.bytes) return@read bytes
            }
            activeOwner()
            imageFetcher.fetchForDownload(source).also { activeOwner() }
        }
    }

    private fun tagMangaKey(tagId: TagId, thread: ThreadSummary): TagMangaChapterDownloadKey =
        TagMangaChapterDownloadKey(tagId.value, thread.tid.value, thread.author?.uid?.value)

    private fun rssMangaKey(subscriptionId: Long, thread: ThreadSummary): RssMangaChapterDownloadKey =
        RssMangaChapterDownloadKey(subscriptionId, thread.tid.value, thread.author?.uid?.value)

    private fun tagMangaQueueTitle(tagName: String, title: String): String =
        if (tagName.isBlank()) title else "#$tagName / $title"

    private fun rssMangaQueueTitle(subscriptionTitle: String, title: String): String =
        if (subscriptionTitle.isBlank()) title else "RSS / $subscriptionTitle / $title"

    private suspend fun updateStage(
        key: DownloadTaskKey,
        stage: DownloadStage,
        progressCurrent: Int = 0,
        progressTotal: Int = 0,
    ) {
        currentCoroutineContext().ensureActive()
        val owner = activeOwner()
        taskMutex.withLock {
            check(removing.none { it(key) }) { "下載任務正在移除，請稍後重試" }
            // The foreground service observes this flow. Restart resumes the page from the beginning.
            _queue.update { entries ->
                val index = entries.indexOfFirst { it.key == key && it.visibleTo(owner) }
                if (index < 0) return@update entries
                entries.toMutableList().apply {
                    this[index] = this[index].copy(status = DownloadStatus.Downloading, stage = stage,
                        progressCurrent = progressCurrent, progressTotal = progressTotal, message = null,
                        updatedAt = currentTimeMillis())
                }
            }
        }
    }

    private suspend fun ensureStorageReady() {
        val storageKey = storageProvider.getStorageKey()
        check(storageKey != null) { "尚未選擇備份資料夾" }
        check(storageProvider.isReady()) { "下載資料夾無法存取，請重新選擇資料夾" }
        restoreQueue(storageKey)
    }

    private suspend fun upsert(incoming: DownloadQueueEntry) {
        val owner = activeOwner()
        val entry = if (incoming.owner == null) incoming.copy(owner = owner) else incoming
        currentCoroutineContext().ensureActive()
        taskMutex.withLock {
            check(removing.none { it(entry.key) }) { "下載任務正在移除，請稍後重試" }
            _queue.update { entries ->
                val previous = entries.firstOrNull { it.key == entry.key && it.visibleTo(entry.owner) }
                val without = entries.filterNot { it.key == entry.key && it.visibleTo(entry.owner) }
                (without + entry.preserveQueuedAt(previous)).sortedWith(compareBy { it.key.stableId })
            }
        }
        backgroundController.onQueueChanged(queue.value)
        if (entry.status == DownloadStatus.Downloaded) {
            // A reopen immediately after success must not replay a stale Downloading entry.
            writeQueueSnapshot()
        } else {
            persistQueue()
        }
    }

    private suspend fun upsertAll(newEntries: List<DownloadQueueEntry>) {
        if (newEntries.isEmpty()) return
        val owner = activeOwner()
        val newEntriesByKey = newEntries.associate { it.key to it.copy(owner = it.owner ?: owner) }
        currentCoroutineContext().ensureActive()
        taskMutex.withLock {
            check(newEntries.none { entry -> removing.any { it(entry.key) } }) { "下載任務正在移除，請稍後重試" }
            _queue.update { entries ->
                val previousByKey = entries.filter { it.visibleTo(owner) }.associateBy { it.key }
                val without = entries.filterNot { it.visibleTo(owner) && it.key in newEntriesByKey }
                val replacements = newEntriesByKey.values.map { it.preserveQueuedAt(previousByKey[it.key]) }
                (without + replacements).sortedWith(compareBy { it.key.stableId })
            }
        }
        backgroundController.onQueueChanged(queue.value)
        persistQueue()
    }

    private fun persistQueue() {
        queueWrites.trySend(accessRevision())
    }

    private data class TagMangaTaskInfo(
        val tagName: String,
        val chapterTitle: String,
        val tagPage: Int,
    )

    private data class RssMangaTaskInfo(
        val subscriptionTitle: String,
        val subscriptionQuery: String,
        val chapterTitle: String,
        val subscriptionPage: Int,
    )


}

internal fun String.isSafeImageFileName() =
    isNotBlank() && this != "." && this != ".." && '/' !in this && '\\' !in this

private fun threadImageFileName(source: String): String {
    val extension = source.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "img"
    return "${source.encodeUtf8().sha256().hex()}.$extension"
}

private fun threadImageSource(raw: String): String? {
    if (raw.isBlank() || raw.contains("none.gif", ignoreCase = true) || raw.contains("/smiley/", ignoreCase = true)) return null
    return normalizeDownloadImageUrl(raw).takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

/** HTML readers prefer Discuz lazy-image attributes over the placeholder src. */
private fun threadImageDocument(html: String) =
    if (html.contains("<img", ignoreCase = true)) Ksoup.parseBodyFragment(html) else null

internal fun ThreadPage.threadImageUrls(): Set<String> = buildSet {
    for (post in posts) {
        post.readerImageUrls().filterTo(this) { it.startsWith("https://") || it.startsWith("http://") }
    }
}

/** Only the read-time copy contains local URIs and manual retry references. */
private fun ThreadPage.mapThreadImages(transform: (String) -> String): ThreadPage = copy(posts = posts.map { post ->
    val document = threadImageDocument(post.contentHtml)
    val html = if (document != null) {
        document.outputSettings().prettyPrint(false)
        document.select("img").forEach { element ->
            val attribute = element.readerImageAttribute()
            threadImageSource(element.attr(attribute))?.let { element.attr(attribute, transform(it)) }
        }
        document.body().html()
    } else post.contentHtml
    post.copy(contentHtml = html, images = post.images.map { image ->
        threadImageSource(image.url)?.let { PostImage(transform(it), image.alt) } ?: image
    })
})

internal const val DOWNLOAD_QUEUE_TERMINAL_RETENTION_MS: Long = 24L * 60L * 60L * 1000L
private const val TAG = "DownloadRepository"

private fun DownloadQueueEntry.preserveQueuedAt(previous: DownloadQueueEntry?): DownloadQueueEntry =
    if (previous == null || status == DownloadStatus.Queued) this
    else copy(queuedAt = previous.queuedAt)

internal fun pruneExpiredQueueEntries(
    entries: List<DownloadQueueEntry>,
    now: Long,
    retentionMs: Long = DOWNLOAD_QUEUE_TERMINAL_RETENTION_MS,
): List<DownloadQueueEntry> =
    entries.filter { entry ->
        val expired = entry.updatedAt > 0L && now - entry.updatedAt > retentionMs
        !expired || entry.status != DownloadStatus.Downloaded && entry.status != DownloadStatus.Failed
    }

private fun imageFailureReason(error: Throwable): String {
    val message = error.message.orEmpty()
    // Transport exceptions may contain signed URLs. Persist only a status or a known category.
    if (message.length == 8 && message.startsWith("HTTP ") &&
        message.takeLast(3).all(Char::isDigit)) return message
    val diagnostic = (error::class.simpleName.orEmpty() + " " + message).lowercase()
    return when {
        message == "圖片資料不完整" -> message
        "timeout" in diagnostic || "timed out" in diagnostic -> "圖片下載逾時"
        "enospc" in diagnostic || "no space left" in diagnostic -> "儲存空間不足"
        "securityexception" in diagnostic || "permission denied" in diagnostic || "eacces" in diagnostic -> "無權存取下載資料夾"
        "socket" in diagnostic || "connect" in diagnostic || "unknownhost" in diagnostic ||
            "end of stream" in diagnostic || "eofexception" in diagnostic -> "圖片網路連線失敗"
        else -> "無法取得或儲存圖片"
    }
}
