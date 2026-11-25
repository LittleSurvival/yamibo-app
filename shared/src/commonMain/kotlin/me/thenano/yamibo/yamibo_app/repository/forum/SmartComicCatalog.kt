package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.core.flatMapSuccess
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.value.SearchId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import io.github.littlesurvival.dto.value.ForumId
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.ForumRepository
import me.thenano.yamibo.yamibo_app.i18n.i18n
import okio.ByteString.Companion.encodeUtf8
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.repository.thread.InFlightReads
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import kotlin.time.Duration.Companion.hours

class SmartComicCatalog(factory: DiskCacheFactory) {
    @Serializable
    private data class Snapshot(val checkedAt: Long, val threads: List<ThreadSummary>, val verified: Boolean = false)
    private val cache = factory.create<Snapshot>("smart_comic", maxSize = 80)

    private val searches = InFlightReads<String, YamiboResult<ComicSearchPages>>()

    private val works = factory.database.smartComicWorkQueries
    private val json = factory.json

    data class SavedWork(val id: Long, val fid: ForumId, val title: String, val lastTid: ThreadId?,
                         val completeAt: Long?, val members: List<ThreadSummary>)

    suspend fun savedWork(owner: UserId, fid: ForumId, tid: ThreadId): SavedWork? = withContext(Dispatchers.IO) {
        val account = owner.value.toLong()
        val id = works.memberWork(account, fid.value.toLong(), tid.value.toLong()).executeAsOneOrNull()
            ?: return@withContext null
        val work = works.workById(id, account).executeAsOneOrNull() ?: return@withContext null
        SavedWork(work.id, fid, work.title, work.lastTid?.let { ThreadId(it.toInt()) }, work.completeAt,
            works.members(work.id).executeAsList().map { json.decodeFromString<ThreadSummary>(it) })
    }

    suspend fun saveWork(owner: UserId, fid: ForumId, seed: ThreadSummary,
                         members: List<ThreadSummary>, complete: Boolean): Long = withContext(Dispatchers.IO) {
        val account = owner.value.toLong()
        val board = fid.value.toLong()
        val ordered = members.distinctBy { it.tid }
        val name = workName(seed)
        works.transactionWithResult {
            val knownMembers = (ordered + seed).map { it.tid.value.toLong() }.distinct().chunked(400).flatMap {
                works.memberOwners(account, board, it).executeAsList()
            }.associate { it.tid to it.workId }
            val seedWork = knownMembers[seed.tid.value.toLong()]
            val known = knownMembers.values.distinct()
            val id = seedWork ?: known.singleOrNull() ?: run {
                works.createWork(account, board, name)
                works.newId().executeAsOne()
            }
            val conflict = known.any { it != id }
            val candidateIds = ordered.mapTo(hashSetOf()) { it.tid.value.toLong() }
            val verified = complete && !conflict && candidateIds.containsAll(works.activeMemberTids(id).executeAsList())
            val accepted = if (conflict) listOf(seed) else ordered
            val previous = works.workById(id, account).executeAsOne()
            // Keep the last verified membership intact when an incomplete search discovers new candidates.
            // Their downloads remain addressable by thread and can join the saved work after verification.
            if (previous.completeAt != null && !verified) return@transactionWithResult id
            if (verified) works.retireMembers(id)
            works.updateTitle(name, currentTimeMillis().takeIf { verified }, id, account)
            val tail = (works.lastOrdinal(id).executeAsOneOrNull() ?: -1L) + 1
            accepted.forEachIndexed { index, chapter ->
                val existingOwner = knownMembers[chapter.tid.value.toLong()]
                if (existingOwner == null || verified && existingOwner == id) {
                    val ordinal = if (verified) index.toLong() else tail + index
                    works.saveMember(account, board, chapter.tid.value.toLong(), id, json.encodeToString(chapter), ordinal)
                }
            }
            id
        }
    }

    suspend fun progress(owner: UserId, fid: ForumId, tids: List<ThreadId>) = withContext(Dispatchers.IO) {
        tids.chunked(400).flatMap { ids ->
            works.progress(owner.value.toLong(), fid.value.toLong(), ids.map { it.value.toLong() }).executeAsList()
        }.associateBy { it.tid }
    }

    suspend fun markUnread(owner: UserId, fid: ForumId, tid: ThreadId) = withContext(Dispatchers.IO) {
        works.setUnread(currentTimeMillis(), owner.value.toLong(), fid.value.toLong(), tid.value.toLong())
    }

    suspend fun recordVisit(owner: UserId, fid: ForumId, tid: ThreadId, at: Long,
                            page: Int, pageCount: Int, image: Int, total: Int) = withContext(Dispatchers.IO) {
        val account = owner.value.toLong()
        val board = fid.value.toLong()
        val chapter = tid.value.toLong()
        works.transaction {
            val old = works.progress(account, board, listOf(chapter)).executeAsOneOrNull()
            if (old != null && old.observedAt > at) return@transaction
            val finished = old?.finished == 1L || page == pageCount && image == total - 1
            works.saveProgress(account, board, chapter, page.toLong(), image.toLong(), total.toLong(), if (finished) 1 else 0, at)
            works.memberWork(account, board, chapter).executeAsOneOrNull()?.let { id ->
                works.visit(chapter, at, id, account)
            }
        }
    }

    private suspend fun compatibleMembership(owner: UserId, fid: ForumId, chapters: List<ThreadSummary>,
                                             coverSaved: Boolean = false) = withContext(Dispatchers.IO) {
        val memberships = chapters.chunked(400).flatMap { batch ->
            works.memberOwners(owner.value.toLong(), fid.value.toLong(), batch.map { it.tid.value.toLong() }).executeAsList()
        }
        val workIds = memberships.map { it.workId }.distinct()
        if (workIds.size > 1) return@withContext false
        val id = workIds.singleOrNull() ?: return@withContext true
        !coverSaved || chapters.mapTo(hashSetOf()) { it.tid.value.toLong() }.containsAll(works.activeMemberTids(id).executeAsList())
    }

    fun chapters(seed: ThreadSummary, candidates: List<ThreadSummary>): List<ThreadSummary> {
        return orderedComicChapters(seed, seed.fid, candidates)
    }

    fun workName(seed: ThreadSummary) = smartComicTitle(seed.title).query

    suspend fun load(
        fid: ForumId, seed: ThreadSummary, local: List<ThreadSummary>, forum: ForumRepository,
        auth: AuthRepository, refresh: Boolean, onCached: (List<ThreadSummary>) -> Unit,
        onVerified: () -> Unit = {},
    ): YamiboResult<List<ThreadSummary>> {
        val work = smartComicTitle(seed.title)
        suspend fun ordered(threads: List<ThreadSummary>, saved: List<ThreadSummary> = emptyList()) =
            withContext(Dispatchers.Default) { orderedComicChapters(seed, fid, threads, saved) }
        if (work.nameKey.length < 2) return YamiboResult.Success(ordered(local))
        val account = auth.currentUser()?.uid
        val key = listOf(account?.value, fid.value, work.nameKey, work.credit ?: seed.author?.uid?.value)
            .joinToString("|").encodeUtf8().sha256().hex()
        val snapshot = account?.let { withContext(Dispatchers.IO) { cache.get(key) } }
        if (!refresh && snapshot?.verified == true &&
            currentTimeMillis() - snapshot.checkedAt in 0L until 2.hours.inWholeMilliseconds) {
            val cached = ordered(local, snapshot.threads)
            val ids = snapshot.threads.mapTo(hashSetOf()) { it.tid }
            // A warm hit needs only the saved member IDs, never another decode of the complete directory.
            if (cached.all { it.tid in ids } && compatibleMembership(account, fid, cached, coverSaved = true)) {
                if (auth.currentUser()?.uid != account) return YamiboResult.NotLoggedIn
                onVerified()
                return YamiboResult.Success(cached)
            }
        }
        val saved = account?.let { savedWork(it, fid, seed.tid) }
        val known = snapshot?.threads.orEmpty() + saved?.members.orEmpty()
        val initial = ordered(local, known)
        if (auth.currentUser()?.uid != account) return YamiboResult.NotLoggedIn
        onCached(initial)
        return searches.read(key) { search(forum, auth, fid, work.query) }.flatMapSuccess { pages ->
            if (auth.currentUser()?.uid != account) return@flatMapSuccess YamiboResult.NotLoggedIn
            val sorted = ordered(pages.threads + local, known)
            if (auth.currentUser()?.uid != account) return@flatMapSuccess YamiboResult.NotLoggedIn
            val returned = pages.threads.mapTo(hashSetOf()) { it.tid }
            val covered = pages.error == null && sorted.all { it.tid in returned }
            if (!covered) {
                onCached(sorted)
                return@flatMapSuccess YamiboResult.Failure(pages.error ?: i18n("目前找到 {} 話，目錄尚未確認完整", sorted.size))
            }
            if (account != null && compatibleMembership(account, fid, sorted)) {
                withContext(Dispatchers.IO) { cache.set(key, Snapshot(currentTimeMillis(), sorted, verified = true)) }
                if (saved != null) saveWork(account, fid, seed, sorted, complete = true)
                if (auth.currentUser()?.uid != account) return@flatMapSuccess YamiboResult.NotLoggedIn
                onVerified()
            }
            YamiboResult.Success(sorted)
        }
    }

    private suspend fun search(forum: ForumRepository, auth: AuthRepository, fid: ForumId, name: String):
        YamiboResult<ComicSearchPages> {
        val query = name.take(32).trimEnd()
        return auth.refreshFormHash().flatMapSuccess { forum.fetchSearch(query, fid, it) }.flatMapSuccess { first ->
            YamiboResult.Success(collectComicSearch(first, fid) { id, index -> forum.fetchSearchById(query, id, index) })
        }
    }
}

private fun ThreadSummary.belongsTo(board: ForumId?): Boolean {
    val declared = fid ?: tag?.let(YamiboForum::toForumId)
    return declared == null || board == null || declared == board
}

internal fun orderedComicChapters(seed: ThreadSummary, board: ForumId?, candidates: List<ThreadSummary>,
                                  saved: List<ThreadSummary> = emptyList()): List<ThreadSummary> {
    val work = smartComicTitle(seed.title)
    val known = saved.mapTo(hashSetOf()) { it.tid }
    return (listOf(seed) + candidates + saved).asSequence().filter { it.belongsTo(board) }.distinctBy { it.tid }
        .map { it to smartComicTitle(it.title) }
        .filter { (thread, title) -> thread.tid == seed.tid || thread.tid in known || work.matches(title) &&
            (work.credit != null && title.credit != null || seed.author != null && seed.author?.uid == thread.author?.uid) }
        .sortedWith(compareBy({ it.second.chapter ?: Int.MAX_VALUE }, { it.second.part }, { it.first.tid.value }))
        .map { it.first }.toList()
}

internal data class ComicSearchPages(val threads: List<ThreadSummary>, val error: String? = null)

internal suspend fun collectComicSearch(first: SearchPage, board: ForumId,
    fetch: suspend (SearchId, Int) -> YamiboResult<SearchPage>): ComicSearchPages {
    val threads = mutableListOf<ThreadSummary>()
    val seen = mutableSetOf<ThreadId>()
    var page = first
    var index = 1
    var promisedPages = 1
    var promisedCount = 0
    fun incomplete() = ComicSearchPages(threads, i18n("搜尋分頁未前進"))
    while (true) {
        val nav = page.pageNav
        if (nav?.currentPage?.let { it != index } == true ||
            page.forumId?.let { it != board } == true ||
            page.searchId?.let { first.searchId != null && it != first.searchId } == true) return incomplete()
        val fresh = page.threads.filter { seen.add(it.tid) }
        threads += fresh.filter { it.belongsTo(board) }
        if (index > 1 && fresh.isEmpty()) return incomplete()
        promisedCount = maxOf(promisedCount, page.totalCount)
        promisedPages = maxOf(promisedPages, nav?.totalPages ?: 1)
        if (nav?.nextPageIndex == null && nav?.nextUrl.isNullOrBlank() && index >= promisedPages) {
            return if (seen.size < promisedCount) incomplete() else ComicSearchPages(threads)
        }
        if (nav?.nextPageIndex?.let { it != index + 1 } == true) return incomplete()
        val id = first.searchId ?: return ComicSearchPages(threads, i18n("缺少搜尋分頁識別碼"))
        when (val next = fetch(id, ++index)) {
            is YamiboResult.Success -> page = next.value
            else -> return ComicSearchPages(threads, next.message())
        }
    }
}
