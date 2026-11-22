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
import no.synth.kmpzip.okio.ZipOutputStream
import no.synth.kmpzip.okio.asSource
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile
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
    private val storageProvider: DownloadStorageProvider,
    private val imageFetcher: DownloadImageFetcher,
    private val cacheDirectory: Path,
    private val database: Database,
    private val backgroundController: DownloadBackgroundController = DownloadBackgroundController.None,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    },
) : DownloadRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerMutex = Mutex()
    private val taskMutex = Mutex()
    private val removalMutex = Mutex()
    private val queueWriteMutex = Mutex()
    private var savedQueue: Pair<String?, List<DownloadQueueEntry>>? = null
    private val activeTasks = mutableMapOf<DownloadTaskKey, Job>()
    private val imageRepairs = mutableMapOf<MissingThreadImage, Deferred<Result<String>>>()
    private val archives = linkedMapOf<Pair<String, DownloadTaskKey>, OpenArchive>()
    private class OpenArchive(val generation: String, val zip: ZipFile, val names: MutableSet<String>) {
        var readers = 0
        var retired = false
        fun retire() {
            retired = true
            if (readers == 0) zip.close()
        }
    }
    private val imageRevision = MutableStateFlow(0L)
    private val removing = mutableListOf<(DownloadTaskKey) -> Boolean>()
    private val queueWrites = Channel<Unit>(Channel.CONFLATED)
    private val contentMutex = Mutex()
    private var contentCatalog: DownloadedContentCatalog? = null
    private val knownTitles = mutableMapOf<DownloadTaskKey, String>()
    private val prefetchedPages = mutableMapOf<ThreadPageDownloadKey, ThreadPage>()
    private val tagMangaTasks = mutableMapOf<TagMangaChapterDownloadKey, TagMangaTaskInfo>()
    private val rssMangaTasks = mutableMapOf<RssMangaChapterDownloadKey, RssMangaTaskInfo>()
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
                val storageKey = storageProvider.getStorageKey()
                val persisted = storageProvider.readQueue()
                savedQueue = storageKey to persisted
                val groups = contentMutex.withLock { catalogLocked(storageKey).groups }
                val contentKeys = groups.flatMap { group -> group.items.map { it.key } }.toHashSet()
                val restoredQueue = pruneExpiredQueueEntries(
                    entries = persisted,
                    now = currentTimeMillis(),
                ).filter { entry ->
                    entry.status != DownloadStatus.Downloaded && entry.status != DownloadStatus.UpdateAvailable || entry.key in contentKeys
                }.map { entry ->
                    when (entry.status) {
                        DownloadStatus.Downloading -> entry.copy(status = DownloadStatus.Queued)
                        else -> entry
                    }
                }
                val queueKeys = restoredQueue.mapTo(hashSetOf()) { it.key }
                val completed = groups.flatMap { group ->
                    group.items.filterNot { it.key in queueKeys }.map { item ->
                        val title = when (group.type) {
                            DownloadedContentGroupType.Thread -> item.title
                            DownloadedContentGroupType.TagManga -> tagMangaQueueTitle(group.title.removePrefix("#").takeUnless { it == group.id }.orEmpty(), item.title)
                            DownloadedContentGroupType.RssManga -> rssMangaQueueTitle(group.title.removePrefix("RSS / ").takeUnless { it == group.id }.orEmpty(), item.title)
                        }
                        DownloadQueueEntry(item.key, title, DownloadStatus.Downloaded,
                            progressCurrent = item.imageCount, progressTotal = item.imageCount, updatedAt = item.downloadedAt)
                    }
                }
                val restored = (restoredQueue + completed).sortedBy { it.key.stableId }
                _queue.value = restored
                restored.forEach { knownTitles[it.key] = it.title }
                backgroundController.onQueueChanged(restored)
                queueWriteMutex.withLock { saveQueue(restoredQueue) }
            } finally {
                initialized.complete(Unit)
            }
            drainQueue()
        }
        scope.launch {
            initialized.await()
            for (request in queueWrites) {
                downloadResult("persistQueue") { writeQueueSnapshot() }
            }
        }
    }

    override suspend fun isStorageReady(): Boolean =
        storageProvider.isReady()

    override suspend fun getSummary(): DownloadQueueSummary {
        initialized.await()
        return withContext(Dispatchers.IO) {
            val entries = queue.value
            DownloadQueueSummary(
                queued = entries.count { it.status == DownloadStatus.Queued },
                downloading = entries.count { it.status == DownloadStatus.Downloading },
                downloaded = getDownloadedContentSummary().totalItems,
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
            contentMutex.withLock {
                catalogLocked(storageKey, forceRefresh).groups
            }
        }
    }

    private data class DownloadedContentCatalog(val storageKey: String?, val groups: List<DownloadedContentGroup>)

    private suspend fun catalogLocked(storageKey: String?, refresh: Boolean = false): DownloadedContentCatalog {
        if (!refresh) {
            contentCatalog?.takeIf { it.storageKey == storageKey }?.let { return it }
            val cached = storageKey?.let { DownloadCatalogIndex(cacheDirectory, it).read(::applyCatalogEdits) }
            if (cached != null) return DownloadedContentCatalog(storageKey, cached).also { contentCatalog = it }
        }
        val groups = buildContentGroups(storageProvider.listManifests(), storageProvider.listTagMangaManifests(), storageProvider.listRssMangaManifests())
        check(storageProvider.getStorageKey() == storageKey) { "儲存資料夾已變更" }
        if (storageKey != null) DownloadCatalogIndex(cacheDirectory, storageKey).commit(groups)
        return DownloadedContentCatalog(storageKey, groups).also { contentCatalog = it }
    }

    /** Invalidate the persistent snapshot before mutating user storage. A crash rebuilds it. */
    private suspend fun beginCatalogChange(): DownloadedContentCatalog {
        val storage = checkNotNull(storageProvider.getStorageKey())
        val catalog = catalogLocked(storage)
        DownloadCatalogIndex(cacheDirectory, storage).begin()
        contentCatalog = null
        return catalog
    }

    private fun finishCatalogChange(catalog: DownloadedContentCatalog, removed: Set<DownloadTaskKey>, replacement: DownloadedContentGroup? = null) {
        val edit = CatalogEdit(removed, replacement)
        val groups = applyCatalogEdits(catalog.groups, listOf(edit))
        val storage = checkNotNull(catalog.storageKey)
        DownloadCatalogIndex(cacheDirectory, storage).commit(groups, edit)
        contentCatalog = DownloadedContentCatalog(storage, groups)
    }

    private fun applyCatalogEdits(previous: List<DownloadedContentGroup>, edits: List<CatalogEdit>): List<DownloadedContentGroup> {
        val groups = previous.associateByTo(linkedMapOf()) { it.id }
        for ((removed, replacement) in edits) {
            val byGroup = removed.groupBy { key -> when (key) {
                is ThreadPageDownloadKey -> "thread_${key.tid}_author_${key.authorId ?: "all"}"
                is TagMangaChapterDownloadKey -> key.tagStableId
                is RssMangaChapterDownloadKey -> key.rssStableId
            } }
            for (id in byGroup.keys + listOfNotNull(replacement?.id)) {
                val addition = replacement?.takeIf { it.id == id }
                val old = groups[id]
                val header = addition ?: old ?: continue
                val items = old?.items.orEmpty().filterNot { it.key in removed } + addition?.items.orEmpty()
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

    private fun group(manifest: ThreadPageDownloadManifest) = buildContentGroups(listOf(manifest), emptyList(), emptyList()).single()
    private fun group(manifest: TagMangaChapterManifest) = buildContentGroups(emptyList(), listOf(manifest), emptyList()).single()
    private fun group(manifest: RssMangaChapterManifest) = buildContentGroups(emptyList(), emptyList(), listOf(manifest)).single()

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
                            needsMigration = manifest.archiveId == null && manifest.images.isNotEmpty(),
                            sortPage = manifest.key.page,
                            detail = "第 ${manifest.key.page} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = manifest.images.size,
                            imageBytes = manifest.images.sumOf { it.bytes },
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
                            needsMigration = manifest.archiveId == null && manifest.images.isNotEmpty(),
                            sortPage = manifest.tagPage,
                            detail = "標籤第 ${manifest.tagPage} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = manifest.images.size,
                            imageBytes = manifest.images.sumOf { it.bytes },
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
                            needsMigration = manifest.archiveId == null && manifest.images.isNotEmpty(),
                            sortPage = manifest.subscriptionPage,
                            detail = "RSS 第 ${manifest.subscriptionPage} 頁",
                            downloadedAt = manifest.downloadedAt,
                            imageCount = manifest.images.size,
                            imageBytes = manifest.images.sumOf { it.bytes },
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

    override suspend fun getStatus(key: ThreadPageDownloadKey): DownloadStatus {
        initialized.await()
        queue.value.firstOrNull { it.key == key }?.let { return it.status }
        return if (withContext(Dispatchers.IO) { storageProvider.readManifest(key) } != null) DownloadStatus.Downloaded else DownloadStatus.NotDownloaded
    }

    override suspend fun getStatus(key: TagMangaChapterDownloadKey): DownloadStatus {
        initialized.await()
        queue.value.firstOrNull { it.key == key }?.let { return it.status }
        return if (withContext(Dispatchers.IO) { storageProvider.readTagMangaManifest(key) } != null) DownloadStatus.Downloaded else DownloadStatus.NotDownloaded
    }

    override suspend fun getStatus(key: RssMangaChapterDownloadKey): DownloadStatus {
        initialized.await()
        queue.value.firstOrNull { it.key == key }?.let { return it.status }
        return if (withContext(Dispatchers.IO) { storageProvider.readRssMangaManifest(key) } != null) DownloadStatus.Downloaded else DownloadStatus.NotDownloaded
    }

    override suspend fun getDownloadedPage(key: ThreadPageDownloadKey): ThreadPage? {
        return withContext(Dispatchers.IO) {
            contentMutex.withLock {
                val (bytes, record) = storageProvider.readPageRecord(key) ?: return@withLock null
                val page = decodePage(key, bytes) ?: return@withLock null
                val storageKey = storageProvider.getStorageKey()
                val saved = if (storageKey != null && record != null) {
                    val uris = imageUris(storageKey, record)
                    record.images.associate { it.sourceUrl to uris[it.fileName] }
                } else emptyMap()
                val storageId = storageKey?.encodeUtf8()?.sha256()?.hex().orEmpty()
                page.mapThreadImages { source ->
                    saved[source] ?: MissingThreadImage(key, storageId, source).readerUri()
                }
            }
        }
    }

    private suspend fun readOriginalPage(key: ThreadPageDownloadKey): ThreadPage? {
        return storageProvider.readThreadPage(key)?.let { decodePage(key, it) }
    }

    private fun decodePage(key: ThreadPageDownloadKey, bytes: ByteArray): ThreadPage? {
        return runCatching { json.decodeFromString<ThreadPage>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode downloaded page key=${key.stableId}", it) }
            .getOrNull()
    }

    override fun observeLocalImage(image: MissingThreadImage) = imageRevision.map {
        contentMutex.withLock {
            if (storageFor(image) == null) return@withLock null
            val manifest = readManifest(image.key) ?: return@withLock null
            val saved = manifest.images.firstOrNull { it.sourceUrl == image.sourceUrl } ?: return@withLock null
            imageUris(checkNotNull(storageProvider.getStorageKey()), manifest, listOf(saved))[saved.fileName]
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun storageFor(image: MissingThreadImage): String? =
        storageProvider.getStorageKey()?.takeIf { it.encodeUtf8().sha256().hex() == image.storageId }

    override suspend fun retryArchivedImage(uri: String, failedLocalUri: String): Result<String> = withContext(Dispatchers.IO) {
        downloadResult("retry archived image") {
            val ref = requireNotNull(ArchivedImage.parse(uri))
            val image = contentMutex.withLock {
                val source = readManifest(ref.key)?.images?.find { it.sourceUrl.encodeUtf8().sha256().hex() == ref.sourceId }
                MissingThreadImage(ref.key, ref.storageId, checkNotNull(source) { "下載圖片已移除" }.sourceUrl)
            }
            retryImage(image, failedLocalUri).getOrThrow()
        }
    }

    override suspend fun retryImage(image: MissingThreadImage, failedLocalUri: String?): Result<String> {
        initialized.await()
        val job = taskMutex.withLock {
            if (removing.any { it(image.key) }) return Result.failure(IllegalStateException("下載內容已移除"))
            imageRepairs[image]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    downloadResult("retryImage key=${image.key.stableId}") { repairImage(image, failedLocalUri) }
                } finally {
                    withContext(NonCancellable) { taskMutex.withLock { imageRepairs.remove(image) } }
                }
            }.also { repair ->
                imageRepairs[image] = repair
                repair.start()
            }
        }
        return job.await()
    }

    private suspend fun repairImage(image: MissingThreadImage, failedLocalUri: String?): String {
        val key = image.key
        suspend fun currentManifest(recover: Boolean): DownloadManifest? {
            check(storageFor(image) != null) { "儲存資料夾已變更" }
            val manifest = readManifest(key)
            if (key is ThreadPageDownloadKey) {
                val page = checkNotNull(readOriginalPage(key)) { "下載內容已移除" }
                check(image.sourceUrl in page.threadImageUrls()) { "圖片已從帖子移除" }
                return manifest ?: if (recover) recoverPageManifest(key, page) else null
            }
            check(manifest != null && image.sourceUrl in manifest.orderedSources()) { "圖片已從章節移除" }
            return manifest
        }
        contentMutex.withLock {
            val manifest = currentManifest(recover = false) ?: return@withLock
            val existing = manifest.images.find { it.sourceUrl == image.sourceUrl }
            if (existing != null) {
                val local = imageUris(checkNotNull(storageFor(image)), manifest, listOf(existing))[existing.fileName]
                if (local != null && local != failedLocalUri) return local
            }
        }
        val bytes = imageFetcher.fetch(image.sourceUrl)
        return contentMutex.withLock {
            val manifest = checkNotNull(currentManifest(recover = true))
            val storage = checkNotNull(storageFor(image))
            val fileName = threadImageFileName(image.sourceUrl)
            val saved = DownloadedImage(image.sourceUrl, fileName, bytes.size.toLong())
            val images = manifest.images.filterNot { it.sourceUrl == image.sourceUrl } + saved
            val updated = when (manifest) {
                is ThreadPageDownloadManifest -> manifest.copy(images = images)
                is TagMangaChapterManifest -> manifest.copy(images = images, sourceOrder = manifest.orderedSources())
                is RssMangaChapterManifest -> manifest.copy(images = images, sourceOrder = manifest.orderedSources())
            }
            val catalog = beginCatalogChange()
            val uri = storageProvider.writeImage(
                storage, key, PendingDownloadedImage(fileName, bytes), encodeManifest(updated),
            )
            manifest.images.find { it.sourceUrl == image.sourceUrl }?.let { archives[storage to key]?.names?.remove(it.fileName) }
            finishCatalogChange(catalog, setOf(key), manifestGroup(updated))
            imageRevision.update { it + 1 }
            queue.value.firstOrNull { it.key == key }?.let { upsert(it.copy(updatedAt = currentTimeMillis())) }
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
        val recovered = page.threadImageUrls().mapNotNull { url ->
            // Only a source-derived name identifies an image after the manifest is lost.
            val name = threadImageFileName(url)
            val local = loose[name]
            when {
                local != null -> DownloadedImage(url, name, storageProvider.openLoose(local).buffer().use { it.readAll(okio.blackholeSink()) })
                name in entries -> DownloadedImage(url, name, entries.getValue(name), packed = true)
                else -> null
            }
        }
        return page.downloadManifest(key).copy(images = recovered, archiveId = generation.takeIf { entries.isNotEmpty() })
    }

    override suspend fun updateDownloadedPage(key: ThreadPageDownloadKey, page: ThreadPage): Boolean = withContext(Dispatchers.IO) {
        require(page.isValidThreadPage(ThreadId(key.tid), page = key.page, authorId = key.authorId?.let(::UserId)))
        initialized.await()
        contentMutex.withLock {
            val manifest = storageProvider.readManifest(key) ?: return@withContext false
            val totalPages = page.pageNav?.totalPages ?: 1
            val updatedAt = currentTimeMillis()
            val updated = manifest.copy(
                    title = page.thread.title,
                    downloadedAt = updatedAt,
                    sourceTotalPages = totalPages,
                    pageKind = if (key.page >= totalPages) DownloadPageKind.LastAtDownloadTime else DownloadPageKind.Normal,
                    forumId = page.thread.forum.fid.value,
                    forumName = page.thread.forum.name,
            )
            val catalog = beginCatalogChange()
            storageProvider.updateThreadPage(key, json.encodeToString(updated).encodeToByteArray(),
                json.encodeToString(page).encodeToByteArray(),
            )
            finishCatalogChange(catalog, setOf(key), group(updated))
            queue.value.firstOrNull { it.key == key }?.let { entry ->
                upsert(entry.copy(title = page.thread.title, updatedAt = updatedAt))
            }
            true
        }
    }

    override suspend fun getManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? =
        withContext(Dispatchers.IO) { storageProvider.readManifest(key) }

    override suspend fun getTagMangaChapterImages(key: TagMangaChapterDownloadKey): List<String>? = withContext(Dispatchers.IO) {
        contentMutex.withLock {
            val manifest = storageProvider.readTagMangaManifest(key) ?: return@withLock null
            orderedUris(manifest).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun getTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? =
        withContext(Dispatchers.IO) { storageProvider.readTagMangaManifest(key) }

    override suspend fun getRssMangaChapterImages(key: RssMangaChapterDownloadKey): List<String>? = withContext(Dispatchers.IO) {
        contentMutex.withLock {
            val manifest = storageProvider.readRssMangaManifest(key) ?: return@withLock null
            orderedUris(manifest).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun getRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? =
        withContext(Dispatchers.IO) { storageProvider.readRssMangaManifest(key) }

    override suspend fun enqueuePage(tid: ThreadId, title: String, authorId: UserId?, page: Int): Result<Unit> = downloadResult("enqueuePage tid=${tid.value} page=$page authorId=${authorId?.value}") {
        initialized.await()
        ensureStorageReady()
        val key = ThreadPageDownloadKey(tid.value, page, authorId?.value)
        knownTitles[key] = title
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueThread(tid: ThreadId, title: String, authorId: UserId?): Result<Unit> = downloadResult("enqueueThread tid=${tid.value} authorId=${authorId?.value}") {
        enqueueThreadPages(tid, title, authorId, includeLastPage = true)
    }

    override suspend fun enqueueThreadExceptLastPage(
        tid: ThreadId,
        title: String,
        authorId: UserId?,
    ): Result<Unit> = downloadResult("enqueueThreadExceptLastPage tid=${tid.value} authorId=${authorId?.value}") {
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
        val first = when (val result = threadRepository.fetchThread(tid, authorId, page = 1)) {
            is YamiboResult.Success -> result.value
            else -> error(result.message())
        }
        val totalPages = first.pageNav?.totalPages ?: 1
        val lastPageToQueue = if (includeLastPage) totalPages else totalPages - 1
        if (lastPageToQueue < 1) error("此 Thread 只有一頁，沒有可排除最後頁後下載的內容")
        val entries = (1..lastPageToQueue).map { page ->
            val key = ThreadPageDownloadKey(tid.value, page, authorId?.value)
            knownTitles[key] = title
            if (page == 1) prefetchedPages[key] = first
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
    ): Result<Unit> = downloadResult("enqueueTagMangaChapter tagId=${tagId.value} tid=${thread.tid.value}") {
        initialized.await()
        ensureStorageReady()
        val key = tagMangaKey(tagId, thread)
        val title = tagMangaQueueTitle(tagName, thread.title)
        knownTitles[key] = title
        tagMangaTasks[key] = TagMangaTaskInfo(tagName, thread.title, tagPage)
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueTagMangaCurrentPage(
        tagId: TagId,
        tagName: String,
        threads: List<ThreadSummary>,
        tagPage: Int,
    ): Result<Unit> = downloadResult("enqueueTagMangaCurrentPage tagId=${tagId.value} page=$tagPage count=${threads.size}") {
        initialized.await()
        ensureStorageReady()
        queueTagMangaThreads(tagId, tagName, threads, tagPage)
        drainQueue()
    }

    override suspend fun enqueueTagMangaAllPages(tagId: TagId, tagName: String): Result<Unit> = downloadResult("enqueueTagMangaAllPages tagId=${tagId.value}") {
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
    ): Result<Unit> = downloadResult("enqueueRssMangaChapter subscriptionId=$subscriptionId tid=${thread.tid.value}") {
        initialized.await()
        ensureStorageReady()
        val key = rssMangaKey(subscriptionId, thread)
        val queueTitle = rssMangaQueueTitle(title, thread.title)
        knownTitles[key] = queueTitle
        rssMangaTasks[key] = RssMangaTaskInfo(title, query, thread.title, page)
        upsert(DownloadQueueEntry(key, queueTitle, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
        drainQueue()
    }

    override suspend fun enqueueRssMangaCurrentPage(
        subscriptionId: Long,
        title: String,
        query: String,
        threads: List<ThreadSummary>,
        page: Int,
    ): Result<Unit> = downloadResult("enqueueRssMangaCurrentPage subscriptionId=$subscriptionId page=$page count=${threads.size}") {
        initialized.await()
        ensureStorageReady()
        queueRssMangaThreads(subscriptionId, title, query, threads, page)
        drainQueue()
    }

    override suspend fun enqueueRssMangaAllPages(subscriptionId: Long, title: String, query: String): Result<Unit> = downloadResult("enqueueRssMangaAllPages subscriptionId=$subscriptionId") {
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
        runDownloadTask(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            return@task when (val result = threadRepository.fetchThread(tid, authorId, page = page)) {
                is YamiboResult.Success -> {
                    knownTitles[key] = title
                    try {
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Downloading, stage = DownloadStage.Preparing, updatedAt = currentTimeMillis()))
                        persistPage(key, title, result.value)
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Downloaded, updatedAt = currentTimeMillis()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "refreshPage failed key=${key.stableId}", e)
                        upsert(DownloadQueueEntry(key, title, DownloadStatus.Failed, message = e.message, updatedAt = currentTimeMillis()))
                        return@task YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
                    }
                    result
                }
                else -> YamiboResult.Failure(result.message())
            }
        } ?: YamiboResult.Failure("下載任務正在處理，請稍後重試")
    }

    override suspend fun refreshTagMangaChapter(key: TagMangaChapterDownloadKey): YamiboResult<List<String>> = withContext(Dispatchers.IO) {
        initialized.await()
        runDownloadTask(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            val oldManifest = storageProvider.readTagMangaManifest(key)
            val task = tagMangaTasks[key]
            val tagName = oldManifest?.tagName ?: task?.tagName ?: ""
            val title = oldManifest?.title ?: task?.chapterTitle ?: knownTitles[key] ?: key.stableId
            val tagPage = oldManifest?.tagPage ?: task?.tagPage ?: 1
            val result = threadRepository.fetchThread(ThreadId(key.tid), key.authorId?.let(::UserId), page = 1)
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
                upsert(DownloadQueueEntry(key, tagMangaQueueTitle(tagName, title), DownloadStatus.Failed, message = e.message, updatedAt = currentTimeMillis()))
                YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
            }
        } ?: YamiboResult.Failure("下載任務正在處理，請稍後重試")
    }

    override suspend fun refreshRssMangaChapter(key: RssMangaChapterDownloadKey): YamiboResult<List<String>> = withContext(Dispatchers.IO) {
        initialized.await()
        runDownloadTask(key) task@{
            if (!storageProvider.isReady()) return@task YamiboResult.Failure("尚未選擇備份資料夾")
            val oldManifest = storageProvider.readRssMangaManifest(key)
            val task = rssMangaTasks[key]
            val subscriptionTitle = oldManifest?.subscriptionTitle ?: task?.subscriptionTitle ?: ""
            val subscriptionQuery = oldManifest?.subscriptionQuery ?: task?.subscriptionQuery ?: subscriptionTitle
            val title = oldManifest?.title ?: task?.chapterTitle ?: knownTitles[key] ?: key.stableId
            val page = oldManifest?.subscriptionPage ?: task?.subscriptionPage ?: 1
            val result = threadRepository.fetchThread(ThreadId(key.tid), key.authorId?.let(::UserId), page = 1)
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
                upsert(DownloadQueueEntry(key, rssMangaQueueTitle(subscriptionTitle, title), DownloadStatus.Failed, message = e.message, updatedAt = currentTimeMillis()))
                YamiboResult.Failure(e.message ?: "下載內容寫入失敗", e)
            }
        } ?: YamiboResult.Failure("下載任務正在處理，請稍後重試")
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
                contentMutex.withLock {
                    val storage = checkNotNull(storageProvider.getStorageKey())
                    val keys = storageProvider.groupKeys(storage, group) + queue.value.map { it.key }.filter(matches)
                    val failures = removeLocked(keys, false) {}
                    failures.values.firstOrNull()?.let { throw it }
                }
            }
        }
    }

    override suspend fun removeItems(keys: Set<DownloadTaskKey>, tasksOnly: Boolean, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        var failures = emptyMap<DownloadTaskKey, Throwable>()
        val result = downloadResult("removeItems") {
            withStoppedTasks(keys::contains) {
                contentMutex.withLock { failures = removeLocked(keys, tasksOnly, onProgress) }
            }
        }
        result.exceptionOrNull()?.let { error -> keys.associateWith { error } } ?: failures
    }

    private suspend fun removeLocked(keys: Set<DownloadTaskKey>, tasksOnly: Boolean, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> {
        ensureStorageReady()
        val catalog = if (tasksOnly) null else beginCatalogChange()
        val failures = if (catalog == null) {
            queue.value.filter { it.key in keys && it.status !in setOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed) }
                .associate { it.key to IllegalStateException("任務已完成，請在下載內容管理中刪除") }
        } else {
            val errors = storageProvider.deleteItems(checkNotNull(catalog.storageKey), keys, onProgress)
            ensureStorageReady()
            check(storageProvider.getStorageKey() == catalog.storageKey) { "儲存資料夾已變更，請重新載入" }
            errors
        }
        val succeeded = keys - failures.keys
        if (catalog != null) {
            succeeded.forEach { archives.remove(catalog.storageKey to it)?.retire() }
            finishCatalogChange(catalog, succeeded)
            imageRevision.update { it + 1 }
        }
        if (succeeded.isNotEmpty()) forgetTasks(succeeded::contains)
        return failures
    }

    private suspend fun <T> runDownloadTask(
        key: DownloadTaskKey,
        expected: DownloadQueueEntry? = null,
        block: suspend () -> T,
    ): T? = supervisorScope {
        val task = taskMutex.withLock {
            if (removing.any { it(key) } || activeTasks.containsKey(key) ||
                expected != null && queue.value.firstOrNull { it.key == key } != expected) return@supervisorScope null
            async(start = CoroutineStart.LAZY) { block() }.also {
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
                (activeTasks.filterKeys(matches).values + imageRepairs.filterKeys { matches(it.key) }.values)
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
                            if (matches(it.key) && it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Paused, stage = null)
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
        queueWriteMutex.withLock {
            // Persist removal first. On failure the task remains visible and retryable.
            saveQueue(queue.value.filterNot { matches(it.key) })
            taskMutex.withLock {
                prefetchedPages.keys.removeAll(matches)
                tagMangaTasks.keys.removeAll(matches)
                rssMangaTasks.keys.removeAll(matches)
                knownTitles.keys.removeAll(matches)
                _queue.update { entries -> entries.filterNot { matches(it.key) } }
            }
        }
        backgroundController.onQueueChanged(queue.value)
        persistQueue()
    }

    private suspend fun writeQueueSnapshot() = queueWriteMutex.withLock {
        saveQueue(queue.value)
    }

    private suspend fun saveQueue(entries: List<DownloadQueueEntry>) {
        val snapshot = storageProvider.getStorageKey() to entries
        if (snapshot == savedQueue) return
        storageProvider.writeQueue(entries)
        savedQueue = snapshot
    }

    override suspend fun retry(key: DownloadTaskKey): Result<Unit> = downloadResult("retry key=${key.stableId}") {
        initialized.await()
        ensureStorageReady()
        val title = knownTitles[key] ?: when (key) {
            is ThreadPageDownloadKey -> storageProvider.readManifest(key)?.title
            is TagMangaChapterDownloadKey -> storageProvider.readTagMangaManifest(key)?.let {
                tagMangaQueueTitle(it.tagName, it.title)
            }
            is RssMangaChapterDownloadKey -> storageProvider.readRssMangaManifest(key)?.let {
                rssMangaQueueTitle(it.subscriptionTitle, it.title)
            }
        } ?: key.stableId
        upsert(DownloadQueueEntry(key, title, DownloadStatus.Queued, updatedAt = currentTimeMillis()))
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
                        queue.value.firstOrNull { entry -> entry.status == DownloadStatus.Queued &&
                            entry.key !in activeTasks && removing.none { it(entry.key) } }
                    } ?: break
                    process(next)
                }
            }
        }
    }

    private suspend fun process(entry: DownloadQueueEntry) {
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
        } catch (error: Exception) {
            Logger.e(TAG, "Download task failed key=${entry.key.stableId}", error)
            taskMutex.withLock {
                if (removing.none { it(entry.key) }) {
                    _queue.update { entries -> entries.map {
                        if (it.key == entry.key) it.copy(status = DownloadStatus.Failed, stage = null, message = error.message) else it
                    } }
                }
            }
            persistQueue()
        }
    }

    private suspend fun processThreadPage(entry: DownloadQueueEntry, key: ThreadPageDownloadKey) {
        val prefetched = prefetchedPages.remove(key)
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        val result = prefetched?.let { YamiboResult.Success(it) }
            ?: threadRepository.fetchThread(
                ThreadId(key.tid),
                key.authorId?.let(::UserId),
                page = key.page,
            )
        when (result) {
            is YamiboResult.Success -> {
                if (queue.value.none {
                        it.key == key && it.status == DownloadStatus.Downloading
                    }
                ) {
                    return
                }
                persistEntry(
                    entry = entry,
                    key = key,
                    operation = "processThreadPage",
                    persist = {
                        persistPage(key, entry.title, result.value)
                    },
                    successProgress = success@{
                        val current = queue.value.firstOrNull { it.key == key }
                        val total = current?.progressTotal ?: return@success 0 to 0
                        total to total
                    },
                )
            }
            else -> upsert(entry.copy(status = DownloadStatus.Failed, stage = null, message = result.message(), updatedAt = currentTimeMillis()))
        }
    }

    private suspend fun processTagMangaChapter(entry: DownloadQueueEntry, key: TagMangaChapterDownloadKey) {
        val task = tagMangaTasks[key]
        val oldManifest = storageProvider.readTagMangaManifest(key)
        val tagName = oldManifest?.tagName ?: task?.tagName ?: ""
        val title = oldManifest?.title ?: task?.chapterTitle ?: entry.title
        val tagPage = oldManifest?.tagPage ?: task?.tagPage ?: 1
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        when (val result = threadRepository.fetchThread(ThreadId(key.tid), key.authorId?.let(::UserId), page = 1)) {
            is YamiboResult.Success -> {
                if (queue.value.none { it.key == key && it.status == DownloadStatus.Downloading }) return
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
        val task = rssMangaTasks[key]
        val oldManifest = storageProvider.readRssMangaManifest(key)
        val subscriptionTitle = oldManifest?.subscriptionTitle ?: task?.subscriptionTitle ?: ""
        val subscriptionQuery = oldManifest?.subscriptionQuery ?: task?.subscriptionQuery ?: subscriptionTitle
        val title = oldManifest?.title ?: task?.chapterTitle ?: entry.title
        val page = oldManifest?.subscriptionPage ?: task?.subscriptionPage ?: 1
        upsert(entry.copy(status = DownloadStatus.Downloading, stage = DownloadStage.FetchingContent, progressCurrent = 0, progressTotal = 0, message = null, updatedAt = currentTimeMillis()))
        when (val result = threadRepository.fetchThread(ThreadId(key.tid), key.authorId?.let(::UserId), page = 1)) {
            is YamiboResult.Success -> {
                if (queue.value.none { it.key == key && it.status == DownloadStatus.Downloading }) return
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
            upsert(entry.copy(status = DownloadStatus.Failed, stage = null, message = error.message, updatedAt = currentTimeMillis()))
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
        return OpenArchive(generation, zip, names).also { archives[id] = it }
    }

    private suspend fun imageUris(storage: String, manifest: DownloadManifest, images: List<DownloadedImage> = manifest.images): Map<String, String> {
        val safe = images.filter { it.fileName.isSafeImageFileName() }
        val urls = storageProvider.looseImages(manifest.key, safe.filterNot { it.packed }.map { it.fileName }).toMutableMap()
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
        val storageId = storage.encodeUtf8().sha256().hex()
        for (image in safe) if (image.packed && image.fileName in names) {
            urls[image.fileName] = image.archiveUri(storageId, manifest.key, generation)
        }
        return urls
    }

    private fun DownloadedImage.archiveUri(storageId: String, key: DownloadTaskKey, generation: String) =
        ArchivedImage(storageId, key, fileName, generation, sourceUrl.encodeUtf8().sha256().hex()).uri()

    private suspend fun orderedUris(manifest: DownloadManifest): List<String> {
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
            val cached = archives[storage to ref.key]
            var member = ref.fileName
            val open = if (cached?.generation == ref.generation && member in cached.names) cached else {
                val saved = checkNotNull(readManifest(ref.key))
                val image = saved.images.first { it.sourceUrl.encodeUtf8().sha256().hex() == ref.sourceId }
                member = image.fileName
                if (!image.packed) {
                    val loose = storageProvider.looseImages(ref.key, listOf(image.fileName)).getValue(image.fileName)
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

    private class ImagePack(val path: Path, val generation: String) {
        private val writer = lazy { ZipOutputStream(FileSystem.SYSTEM.sink(path).buffer()) }
        val checksums = mutableMapOf<String, Long>()
        private var finished = false

        fun add(image: DownloadedImage, bytes: ByteArray): DownloadedImage {
            require(image.fileName.isSafeImageFileName()) { "下載圖片檔名無效" }
            require(image.bytes == bytes.size.toLong()) {
                "${image.fileName} 大小與下載清單不符（預期 ${image.bytes} B，實際 ${bytes.size} B）"
            }
            val checksum = Crypto.crc32(bytes)
            check(checksums.put(image.fileName, checksum) == null) { "下載圖片檔名重複" }
            val output = writer.value
            output.putNextEntry(ZipEntry(image.fileName).apply { method = ZipConstants.STORED; size = image.bytes; crc = checksum })
            output.write(bytes)
            output.closeEntry()
            return image.copy(packed = true)
        }

        fun finish() {
            if (finished) return
            finished = true
            if (writer.isInitialized()) writer.value.close()
        }

        fun verify(source: SeekableSource) {
            val zip = try { ZipFile(source) } catch (error: Throwable) { source.close(); throw error }
            try {
                check(zip.entries.size == checksums.size)
                val discard = ByteArray(64 * 1024)
                for (entry in zip.entries) {
                    check(entry.method == ZipConstants.STORED && entry.crc == checksums.getValue(entry.name))
                    val input = zip.getInputStream(entry)
                    try {
                        var bytes = 0L
                        while (true) {
                            val read = input.read(discard, 0, discard.size)
                            if (read < 0) break
                            check(read > 0)
                            bytes += read
                        }
                        check(bytes == entry.size)
                    } finally { input.close() }
                }
            } finally { zip.close() }
        }
    }

    private suspend fun <T> withPack(block: suspend (ImagePack) -> T): T {
        val generation = Uuid.random().toString()
        FileSystem.SYSTEM.createDirectories(cacheDirectory)
        val path = cacheDirectory / "$generation.cbz.tmp"
        val pack = ImagePack(path, generation)
        try { return block(pack) }
        finally { try { pack.finish() } finally { FileSystem.SYSTEM.delete(path) } }
    }

    private suspend fun publish(storage: String, manifest: DownloadManifest, page: ByteArray?, pack: ImagePack,
                                aliases: Map<String, String> = emptyMap(), verify: Boolean = false) {
        check(storageProvider.getStorageKey() == storage) { "儲存資料夾已變更" }
        pack.finish()
        val bytes = encodeManifest(manifest)
        val catalog = beginCatalogChange()
        archives.remove(storage to manifest.key)?.retire()
        if (aliases.isNotEmpty()) remapCovers(aliases)
        storageProvider.publishItem(storage, manifest.key, bytes, page, pack.path.takeIf { pack.checksums.isNotEmpty() },
            if (verify) pack::verify else null)
        finishCatalogChange(catalog, setOf(manifest.key), manifestGroup(manifest))
        imageRevision.update { it + 1 }
    }

    private fun encodeManifest(manifest: DownloadManifest) = when (manifest) {
        is ThreadPageDownloadManifest -> json.encodeToString(manifest)
        is TagMangaChapterManifest -> json.encodeToString(manifest)
        is RssMangaChapterManifest -> json.encodeToString(manifest)
    }.encodeToByteArray()

    private fun manifestGroup(manifest: DownloadManifest) = when (manifest) {
            is ThreadPageDownloadManifest -> group(manifest)
            is TagMangaChapterManifest -> group(manifest)
            is RssMangaChapterManifest -> group(manifest)
    }

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

    private suspend fun retain(storage: String, old: DownloadManifest, images: List<DownloadedImage>,
                               pack: ImagePack, aliases: MutableMap<String, String>): List<DownloadedImage> {
        val urls = imageUris(storage, old, images)
        val storageId = storage.encodeUtf8().sha256().hex()
        return images.mapNotNull { image ->
            currentCoroutineContext().ensureActive()
            val uri = urls[image.fileName] ?: return@mapNotNull null
            val bytes = if (image.packed) checkNotNull(archive(storage, old)).zip.imageBytes(image.fileName)
                else storageProvider.openLoose(uri).buffer().use { it.readByteArray() }
            if (!image.packed) aliases[uri] = image.archiveUri(storageId, old.key, pack.generation)
            pack.add(image, bytes)
        }
    }

    private suspend fun persistPage(key: ThreadPageDownloadKey, title: String, page: ThreadPage) {
        updateStage(key, DownloadStage.DownloadingText)
        val urls = page.threadImageUrls()
        val storage = checkNotNull(storageProvider.getStorageKey())
        val existing = contentMutex.withLock {
            val previous = readManifest(key) ?: return@withLock emptySet()
            val available = imageUris(storage, previous)
            previous.images.filter { it.fileName in available }.mapTo(hashSetOf()) { it.sourceUrl }
        }
        withPack { pack ->
            val downloaded = downloadImages(key, urls.filterNot { it in existing }, 0, pack)
            val received = downloaded.mapTo(hashSetOf()) { it.sourceUrl }
            val totalPages = page.pageNav?.totalPages ?: 1
            contentMutex.withLock {
                check(storageProvider.getStorageKey() == storage)
                val aliases = mutableMapOf<String, String>()
                val retained = readManifest(key)?.let { old ->
                    retain(storage, old, old.images.filter { it.sourceUrl in urls && it.sourceUrl !in received }, pack, aliases)
                }.orEmpty()
                val manifest = ThreadPageDownloadManifest(key, title, currentTimeMillis(), totalPages,
                    if (key.page >= totalPages) DownloadPageKind.LastAtDownloadTime else DownloadPageKind.Normal,
                    page.thread.forum.fid.value, page.thread.forum.name, retained + downloaded, pack.generation)
                updateStage(key, DownloadStage.Saving, urls.size, urls.size)
                publish(storage, manifest, json.encodeToString(page).encodeToByteArray(), pack, aliases)
            }
        }
        threadRepository.setCachedThread(ThreadId(key.tid), key.authorId?.let(::UserId), key.page, page)
    }

    private suspend fun persistTagMangaChapter(key: TagMangaChapterDownloadKey, tagName: String, title: String,
                                               tagPage: Int, page: ThreadPage) = persistChapter(key, key.authorId, page) { images, order, generation ->
        TagMangaChapterManifest(key, tagName, title = title, tagPage = tagPage, imageCount = order.size,
            downloadedAt = currentTimeMillis(), images = images, archiveId = generation, sourceOrder = order.takeUnless { it.size == images.size }.orEmpty())
    }

    private suspend fun persistRssMangaChapter(key: RssMangaChapterDownloadKey, subscriptionTitle: String,
                                               subscriptionQuery: String, title: String, subscriptionPage: Int, page: ThreadPage) =
        persistChapter(key, key.authorId, page) { images, order, generation ->
            RssMangaChapterManifest(key, subscriptionTitle, subscriptionQuery, title = title, subscriptionPage = subscriptionPage,
                imageCount = order.size, downloadedAt = currentTimeMillis(), images = images, archiveId = generation, sourceOrder = order.takeUnless { it.size == images.size }.orEmpty())
        }

    private suspend fun persistChapter(key: DownloadTaskKey, authorId: Int?, page: ThreadPage,
                                      manifest: (List<DownloadedImage>, List<String>, String) -> DownloadManifest): List<String> {
        val storage = checkNotNull(storageProvider.getStorageKey())
        val order = page.readerImages(authorId?.let(::UserId)).map { it.url }
        val urls = order.distinct().filter { it.startsWith("https://") || it.startsWith("http://") }
        check(order.isNotEmpty()) { "此章節沒有可下載圖片" }
        return withPack { pack ->
            val images = downloadImages(key, urls, 1, pack)
            updateStage(key, DownloadStage.Saving, urls.size, urls.size)
            contentMutex.withLock {
                val old = readManifest(key)
                val aliases = mutableMapOf<String, String>()
                val bySource = images.associateByTo(mutableMapOf()) { it.sourceUrl }
                if (old != null && images.size < urls.size) {
                    val previous = old.images.associateBy { it.sourceUrl }
                    val available = imageUris(storage, old)
                    for ((index, url) in urls.withIndex()) {
                        if (url in bySource) continue
                        val image = previous[url] ?: continue
                        val uri = available[image.fileName] ?: continue
                        val bytes = try {
                            if (image.packed) checkNotNull(archive(storage, old)).zip.imageBytes(image.fileName)
                            else storageProvider.openLoose(uri).buffer().use { it.readByteArray() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Logger.w(TAG, "Cannot reuse chapter image ${image.fileName}", error)
                            continue
                        }
                        if (bytes.size.toLong() == image.bytes) {
                            bySource[url] = pack.add(image.copy(fileName = imageFileName(index + 1, url)), bytes)
                        }
                    }
                }
                val saved = manifest(urls.mapNotNull(bySource::get), order, pack.generation)
                if (old != null && old.images.any { !it.packed }) {
                    val oldUris = storageProvider.looseImages(key, old.images.filterNot { it.packed }.map { it.fileName })
                    val storageId = storage.encodeUtf8().sha256().hex()
                    for (previous in old.images) {
                        val uri = oldUris[previous.fileName] ?: continue
                        val replacement = bySource[previous.sourceUrl] ?: continue
                        aliases[uri] = replacement.archiveUri(storageId, key, pack.generation)
                    }
                }
                publish(storage, saved, null, pack, aliases)
                orderedUris(saved)
            }
        }
    }

    override suspend fun migrateImages(onProgress: (Int, Int) -> Unit): Map<DownloadTaskKey, Throwable> = withContext(Dispatchers.IO) {
        val storage = checkNotNull(storageProvider.getStorageKey())
        val targets = getDownloadedContentGroups().flatMap { g -> g.items.filter { it.needsMigration }.map { it.key } }
        val errors = mutableMapOf<DownloadTaskKey, Throwable>()
        onProgress(0, targets.size)
        for ((index, key) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(storageProvider.getStorageKey() == storage) { "儲存資料夾已變更" }
            downloadResult("migrate ${key.stableId}") {
                contentMutex.withLock {
                    val old = readManifest(key) ?: error("下載內容已移除")
                    if (old.archiveId != null) return@withLock
                    val loose = storageProvider.looseImages(key, null)
                    val expected = old.images.mapTo(hashSetOf()) { it.fileName }
                    check(loose.keys == expected && expected.all { it.isSafeImageFileName() }) { "舊圖片與下載清單不符，已保留原檔" }
                    val page = if (key is ThreadPageDownloadKey) checkNotNull(storageProvider.readThreadPage(key)) else null
                    withPack { pack ->
                        val aliases = mutableMapOf<String, String>()
                        val images = retain(storage, old, old.images, pack, aliases)
                        if (key !is ThreadPageDownloadKey) check(images.size == old.images.size) { "漫畫圖片不完整" }
                        val saved = when (old) {
                            is ThreadPageDownloadManifest -> old.copy(images = images, archiveId = pack.generation)
                            is TagMangaChapterManifest -> old.copy(images = images, archiveId = pack.generation)
                            is RssMangaChapterManifest -> old.copy(images = images, archiveId = pack.generation)
                        }
                        publish(storage, saved, page, pack, aliases, verify = true)
                    }
                }
            }.exceptionOrNull()?.let { errors[key] = it }
            onProgress(index + 1, targets.size)
        }
        errors
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
        val entries = threads.map { thread ->
            val key = tagMangaKey(tagId, thread)
            val title = tagMangaQueueTitle(tagName, thread.title)
            knownTitles[key] = title
            tagMangaTasks[key] = TagMangaTaskInfo(tagName, thread.title, pageNumber)
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
        val entries = threads.map { thread ->
            val key = rssMangaKey(subscriptionId, thread)
            val queueTitle = rssMangaQueueTitle(title, thread.title)
            knownTitles[key] = queueTitle
            rssMangaTasks[key] = RssMangaTaskInfo(title, query, thread.title, pageNumber)
            DownloadQueueEntry(key, queueTitle, DownloadStatus.Queued, updatedAt = currentTimeMillis())
        }
        upsertAll(entries)
    }

    private suspend fun downloadImages(
        key: DownloadTaskKey,
        imageUrls: List<String>,
        startIndex: Int,
        pack: ImagePack,
    ): List<DownloadedImage> {
        updateStage(key, DownloadStage.DownloadingImages, 0, imageUrls.size)
        var completed = 0
        val results = mutableListOf<DownloadedImage>()
        coroutineScope {
            imageUrls.mapIndexed { index, url -> index to url }
                .chunked(3)
                .forEach { chunk ->
                    val chunkResults = chunk.map { (index, url) ->
                        async {
                            val bytes = try {
                                imageFetcher.fetch(url)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Logger.d(TAG, "Leaving unavailable image in download ${key.stableId}", error)
                                return@async null
                            }
                            val fileName = if (key is ThreadPageDownloadKey) threadImageFileName(url)
                                else imageFileName(index + startIndex, url)
                            PendingDownloadedImage(fileName, bytes) to DownloadedImage(url, fileName, bytes.size.toLong())
                        }
                    }.awaitAll()
                    for ((pending, image) in chunkResults.filterNotNull()) results += pack.add(image, pending.bytes)
                    completed += chunkResults.size
                    updateStage(key, DownloadStage.DownloadingImages, completed, imageUrls.size)
                }
        }
        return results
    }

    private fun imageFileName(index: Int, url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "img"
        return "${index.toString().padStart(4, '0')}.$ext"
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
        taskMutex.withLock {
            check(removing.none { it(key) }) { "下載任務正在移除，請稍後重試" }
            // The foreground service observes this flow. Restart resumes the page from the beginning.
            _queue.update { entries ->
                val index = entries.indexOfFirst { it.key == key }
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
        if (!storageProvider.isReady()) error("尚未選擇備份資料夾")
    }

    private suspend fun upsert(entry: DownloadQueueEntry) {
        currentCoroutineContext().ensureActive()
        taskMutex.withLock {
            check(removing.none { it(entry.key) }) { "下載任務正在移除，請稍後重試" }
            _queue.update { entries ->
                val previous = entries.firstOrNull { it.key == entry.key }
                val without = entries.filterNot { it.key == entry.key }
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
        val newEntriesByKey = newEntries.associateBy { it.key }
        currentCoroutineContext().ensureActive()
        taskMutex.withLock {
            check(newEntries.none { entry -> removing.any { it(entry.key) } }) { "下載任務正在移除，請稍後重試" }
            _queue.update { entries ->
                val previousByKey = entries.associateBy { it.key }
                val without = entries.filterNot { it.key in newEntriesByKey }
                val replacements = newEntriesByKey.values.map { it.preserveQueuedAt(previousByKey[it.key]) }
                (without + replacements).sortedWith(compareBy { it.key.stableId })
            }
        }
        backgroundController.onQueueChanged(queue.value)
        persistQueue()
    }

    private fun persistQueue() {
        queueWrites.trySend(Unit)
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

private fun ThreadPage.threadImageUrls(): Set<String> = buildSet {
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
