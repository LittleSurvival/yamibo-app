package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.core.flatMapSuccess
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
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
            return YamiboResult.Success(initial)
        }
        onCached(initial)
        return searches.read(key) { search(forum, auth, fid, work.query) }.flatMapSuccess { candidates ->
            val sorted = ordered(local + candidates)
            if (auth.currentUser()?.uid != account) return@flatMapSuccess YamiboResult.NotLoggedIn
            if (account != null) withContext(Dispatchers.IO) { cache.set(key, Snapshot(currentTimeMillis(), sorted)) }
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
