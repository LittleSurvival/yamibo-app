package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository

/** Keeps the UI's repository stable while each folder owns an isolated, cancellable session. */
class DesktopDownloadRepository(
    private val folders: StateFlow<String>,
    private val scope: CoroutineScope,
    create: (String, CoroutineScope) -> DownloadRepository,
) : DownloadRepository {
    private class Session(val folder: String, val scope: CoroutineScope, val repository: DownloadRepository)
    private val session = MutableStateFlow<Session?>(null)
    private val entries = MutableStateFlow<List<DownloadQueueEntry>>(emptyList())
    override val queue = entries.asStateFlow()

    init {
        scope.launch {
            folders.collectLatest { folder ->
                // collectLatest waits for cancellation (including blocking IO) before the next folder.
                supervisorScope {
                    val current = Session(folder, this, create(folder, this))
                    session.value = current
                    try {
                        current.repository.queue.collect { entries.value = it }
                    } finally {
                        session.value = null
                        entries.value = emptyList()
                    }
                }
            }
        }
    }

    private suspend fun <T> use(action: suspend DownloadRepository.() -> T): T {
        val current = session.filterNotNull().first { it.folder == folders.value && it.scope.isActive }
        // Both caller cancellation and a folder switch cancel foreground operations as well as workers.
        val operation = current.scope.async { current.repository.action() }
        return try { operation.await() } finally { operation.cancel() }
    }

    override suspend fun isStorageReady() = use { isStorageReady() }
    override suspend fun getSummary() = use { getSummary() }
    override suspend fun getDownloadedContentSummary() = use { getDownloadedContentSummary() }
    override suspend fun getDownloadedContentGroups() = use { getDownloadedContentGroups() }
    override suspend fun getStatus(key: ThreadPageDownloadKey) = use { getStatus(key) }
    override suspend fun getStatus(key: TagMangaChapterDownloadKey) = use { getStatus(key) }
    override suspend fun getStatus(key: RssMangaChapterDownloadKey) = use { getStatus(key) }
    override suspend fun getDownloadedPage(key: ThreadPageDownloadKey) = use { getDownloadedPage(key) }
    override suspend fun getManifest(key: ThreadPageDownloadKey) = use { getManifest(key) }
    override suspend fun getTagMangaChapterImages(key: TagMangaChapterDownloadKey) = use { getTagMangaChapterImages(key) }
    override suspend fun getTagMangaManifest(key: TagMangaChapterDownloadKey) = use { getTagMangaManifest(key) }
    override suspend fun getRssMangaChapterImages(key: RssMangaChapterDownloadKey) = use { getRssMangaChapterImages(key) }
    override suspend fun getRssMangaManifest(key: RssMangaChapterDownloadKey) = use { getRssMangaManifest(key) }
    override suspend fun enqueuePage(tid: ThreadId, title: String, authorId: UserId?, page: Int) = use { enqueuePage(tid, title, authorId, page) }
    override suspend fun enqueueThread(tid: ThreadId, title: String, authorId: UserId?) = use { enqueueThread(tid, title, authorId) }
    override suspend fun enqueueThreadExceptLastPage(tid: ThreadId, title: String, authorId: UserId?) = use { enqueueThreadExceptLastPage(tid, title, authorId) }
    override suspend fun enqueueTagMangaChapter(tagId: TagId, tagName: String, thread: ThreadSummary, tagPage: Int) = use { enqueueTagMangaChapter(tagId, tagName, thread, tagPage) }
    override suspend fun enqueueTagMangaCurrentPage(tagId: TagId, tagName: String, threads: List<ThreadSummary>, tagPage: Int) = use { enqueueTagMangaCurrentPage(tagId, tagName, threads, tagPage) }
    override suspend fun enqueueTagMangaAllPages(tagId: TagId, tagName: String) = use { enqueueTagMangaAllPages(tagId, tagName) }
    override suspend fun enqueueRssMangaChapter(subscriptionId: Long, title: String, query: String, thread: ThreadSummary, page: Int) = use { enqueueRssMangaChapter(subscriptionId, title, query, thread, page) }
    override suspend fun enqueueRssMangaCurrentPage(subscriptionId: Long, title: String, query: String, threads: List<ThreadSummary>, page: Int) = use { enqueueRssMangaCurrentPage(subscriptionId, title, query, threads, page) }
    override suspend fun enqueueRssMangaAllPages(subscriptionId: Long, title: String, query: String) = use { enqueueRssMangaAllPages(subscriptionId, title, query) }
    override suspend fun refreshPage(tid: ThreadId, title: String, authorId: UserId?, page: Int) = use { refreshPage(tid, title, authorId, page) }
    override suspend fun refreshTagMangaChapter(key: TagMangaChapterDownloadKey) = use { refreshTagMangaChapter(key) }
    override suspend fun refreshRssMangaChapter(key: RssMangaChapterDownloadKey) = use { refreshRssMangaChapter(key) }
    override suspend fun markThreadUpdateAvailable(tid: ThreadId, authorId: UserId?) = use { markThreadUpdateAvailable(tid, authorId) }
    override suspend fun clearPage(key: ThreadPageDownloadKey) = use { clearPage(key) }
    override suspend fun clearThread(key: ThreadPageDownloadKey) = use { clearThread(key) }
    override suspend fun clearTagMangaChapter(key: TagMangaChapterDownloadKey) = use { clearTagMangaChapter(key) }
    override suspend fun clearTagManga(tagId: TagId) = use { clearTagManga(tagId) }
    override suspend fun clearRssMangaChapter(key: RssMangaChapterDownloadKey) = use { clearRssMangaChapter(key) }
    override suspend fun clearRssManga(subscriptionId: Long) = use { clearRssManga(subscriptionId) }
    override suspend fun retry(key: DownloadTaskKey) = use { retry(key) }
    override fun pauseAll() { scope.launch { use { pauseAll() } } }
    override fun resumeAll() { scope.launch { use { resumeAll() } } }
}
