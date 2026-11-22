package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.core.flatMapSuccess
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
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
    private data class Snapshot(val checkedAt: Long, val threads: List<ThreadSummary>)
    private val cache = factory.create<Snapshot>("smart_comic", maxSize = 80)

    private val searches = InFlightReads<String, YamiboResult<List<ThreadSummary>>>()

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
            val verified = complete && !conflict
            val accepted = if (conflict) listOf(seed) else ordered
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

    fun chapters(seed: ThreadSummary, candidates: List<ThreadSummary>): List<ThreadSummary> {
        val work = smartComicTitle(seed.title)
        return (listOf(seed) + candidates).distinctBy { it.tid }
            .map { it to smartComicTitle(it.title) }
            .filter { (thread, title) ->
                thread.tid == seed.tid || (work.matches(title) &&
                    (work.credit != null && title.credit != null ||
                        seed.author != null && seed.author?.uid == thread.author?.uid))
            }
            .sortedWith(compareBy({ it.second.chapter ?: Int.MAX_VALUE }, { it.second.part }, { it.first.tid.value }))
            .map { it.first }
    }

    fun workName(seed: ThreadSummary) = smartComicTitle(seed.title).query

    suspend fun load(
        fid: ForumId, seed: ThreadSummary, local: List<ThreadSummary>, forum: ForumRepository,
        auth: AuthRepository, refresh: Boolean, onCached: (List<ThreadSummary>) -> Unit,
        onVerified: () -> Unit = {},
    ): YamiboResult<List<ThreadSummary>> {
        val work = smartComicTitle(seed.title)
        suspend fun ordered(threads: List<ThreadSummary>) = withContext(Dispatchers.Default) { chapters(seed, threads) }
        if (work.nameKey.length < 2) return YamiboResult.Success(ordered(local))
        val account = auth.currentUser()?.uid
        val key = listOf(account?.value, fid.value, work.nameKey, work.credit ?: seed.author?.uid?.value)
            .joinToString("|").encodeUtf8().sha256().hex()
        val snapshot = account?.let { withContext(Dispatchers.IO) { cache.get(key) } }
        val initial = ordered(local + snapshot?.threads.orEmpty())
        if (auth.currentUser()?.uid != account) return YamiboResult.NotLoggedIn
        if (!refresh && snapshot != null && currentTimeMillis() - snapshot.checkedAt in 0L until 2.hours.inWholeMilliseconds) {
            onVerified()
            return YamiboResult.Success(initial)
        }
        onCached(initial)
        return searches.read(key) { search(forum, auth, fid, work.query) }.flatMapSuccess { candidates ->
            val sorted = ordered(local + candidates)
            if (auth.currentUser()?.uid != account) return@flatMapSuccess YamiboResult.NotLoggedIn
            if (account != null) withContext(Dispatchers.IO) { cache.set(key, Snapshot(currentTimeMillis(), sorted)) }
            if (account != null) {
                onVerified()
                val saved = withContext(Dispatchers.IO) {
                    works.memberWork(account.value.toLong(), fid.value.toLong(), seed.tid.value.toLong()).executeAsOneOrNull()
                }
                if (saved != null) saveWork(account, fid, seed, sorted, complete = true)
            }
            YamiboResult.Success(sorted)
        }
    }

    private suspend fun search(forum: ForumRepository, auth: AuthRepository, fid: ForumId, name: String):
        YamiboResult<List<ThreadSummary>> {
        // Discuz limits search text. Match the full title locally, including for truncated queries.
        val query = name.take(32).trimEnd()
        val first = auth.refreshFormHash().flatMapSuccess { forum.fetchSearch(query, fid, it) }
        return first.flatMapSuccess { firstPage ->
            val candidates = mutableListOf<ThreadSummary>()
            val seen = mutableSetOf<Int>()
            var page: SearchPage = firstPage
            var index = 1
            while (true) {
                val fresh = page.threads.filter { seen.add(it.tid.value) }
                if (index > 1 && fresh.isEmpty()) return@flatMapSuccess YamiboResult.Failure(i18n("搜尋分頁未前進"))
                candidates += fresh.filter {
                    val board = it.fid ?: it.tag?.let(YamiboForum::toForumId)
                    board == null || board == fid
                }
                val nav = page.pageNav
                if (nav?.nextPageIndex == null && index >= (nav?.totalPages ?: 1)) break
                val id = firstPage.searchId ?: return@flatMapSuccess YamiboResult.Failure(i18n("缺少搜尋分頁識別碼"))
                index++
                when (val result = forum.fetchSearchById(query, id, index)) {
                    is YamiboResult.Success -> page = result.value
                    else -> return@flatMapSuccess YamiboResult.Failure(result.message())
                }
            }
            YamiboResult.Success(candidates)
        }
    }
}
