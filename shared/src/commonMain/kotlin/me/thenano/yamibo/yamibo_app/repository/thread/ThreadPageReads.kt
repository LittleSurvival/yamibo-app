package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.ReaderFloorPageQueries
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository.ThreadCacheKey
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.hours

private val guestReaderScope = "".encodeUtf8().sha256().hex()

fun readerAccessScope(header: String): String {
    if (header.isBlank()) return guestReaderScope
    val credentials = parseCookieStringToMap(header)
        .filterKeys { it == AuthRepository.AUTH_COOKIE_KEY || it == AuthRepository.SALT_KEY_COOKIE_KEY || "fidpw" in it }
    if (credentials.isEmpty()) return guestReaderScope
    return credentials.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }.encodeUtf8().sha256().hex()
}

internal fun ReaderFloorPageQueries.cachedThreadPages(tid: ThreadId, author: UserId?, scope: String): List<Int> {
    val prefix = ThreadCacheKey.keyPrefix(tid.value)
    val suffix = "_${author?.value ?: "all"}_$scope"
    val keys = try {
        getReaderPageKeys("$prefix[0-9]*$suffix").executeAsList()
    } catch (error: Exception) {
        Logger.w("ThreadPageReads", "Failed to list cached thread pages", error)
        return emptyList()
    }
    return keys.mapNotNull { it.removePrefix(prefix).removeSuffix(suffix).toIntOrNull() }
        .filter { it > 0 }.distinct().sorted()
}

internal class ThreadPageReads(factory: DiskCacheFactory, private val cookies: CookieStore) {
    private val floors = factory.database.readerFloorPageQueries
    private val cache = factory.create<ThreadPage>("thread_page", maxSize = 50, expiration = 24.hours) { key ->
        // Older cache keys have no access scope or associated PID index.
        val parts = key.split('_', limit = 4)
        if (parts.size == 4) {
            val thread = parts[0].toLongOrNull()
            val page = parts[1].toLongOrNull()
            val author = if (parts[2] == "all") 0L else parts[2].toLongOrNull()
            if (thread != null && page != null && author != null) floors.clearPage(parts[3], thread, author, page)
        }
    }
    private val pending = InFlightReads<String, YamiboResult<ThreadPage>>()

    private fun key(tid: ThreadId, author: UserId?, page: Int, scope: String) =
        ThreadCacheKey(tid.value, page, author?.value).toCacheKey() + "_" + scope

    suspend fun fetch(
        tid: ThreadId, author: UserId?, page: Int, reverse: Boolean,
        request: suspend (String) -> YamiboResult<ThreadPage>,
    ): YamiboResult<ThreadPage> = withContext(Dispatchers.Default) {
        val session = cookies.load().orEmpty()
        val scope = readerAccessScope(session)
        val key = key(tid, author, page, scope)
        pending.read("${key}_${reverse}") {
            val result = request(session).validatedThreadPage(tid, page = page, authorId = author)
            val currentSession = cookies.load().orEmpty()
            if (currentSession != session && readerAccessScope(currentSession) != scope) return@read YamiboResult.NotLoggedIn
            if (!reverse && result is YamiboResult.Success) store(tid, author, page, scope, result.value)
            result
        }
    }

    fun get(tid: ThreadId, author: UserId?, page: Int): ThreadPage? =
        cache.get(key(tid, author, page, readerAccessScope(cookies.load().orEmpty())))
            ?.takeIf { it.isValidThreadPage(tid, page = page, authorId = author) }

    fun cachedPages(tid: ThreadId, author: UserId?) =
        floors.cachedThreadPages(tid, author, readerAccessScope(cookies.load().orEmpty()))

    fun pageOf(tid: ThreadId, author: UserId?, pid: PostId): Int? =
        floors.getPage(readerAccessScope(cookies.load().orEmpty()), tid.value.toLong(),
            author?.value?.toLong() ?: 0L, pid.value.toLong()).executeAsOneOrNull()?.toInt()

    private fun store(tid: ThreadId, author: UserId?, page: Int, scope: String, value: ThreadPage) {
        val thread = tid.value.toLong()
        val authorScope = author?.value?.toLong() ?: 0L
        cache.set(key(tid, author, page, scope), value) {
            floors.clearPage(scope, thread, authorScope, page.toLong())
            value.posts.forEach { floors.rememberFloor(scope, thread, authorScope, it.pid.value.toLong(), page.toLong()) }
        }
    }

    fun set(tid: ThreadId, author: UserId?, page: Int, value: ThreadPage) {
        if (value.isValidThreadPage(tid, page = page, authorId = author)) {
            store(tid, author, page, readerAccessScope(cookies.load().orEmpty()), value)
        }
    }

    fun clear(tid: ThreadId) {
        floors.clearThread(tid.value.toLong())
        cache.removeByPrefix(ThreadCacheKey.keyPrefix(tid.value))
    }
}
