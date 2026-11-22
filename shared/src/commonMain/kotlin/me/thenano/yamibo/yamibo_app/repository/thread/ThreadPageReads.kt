package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository.ThreadCacheKey
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.hours

fun readerAccessScope(header: String): String = parseCookieStringToMap(header)
    .filterKeys { it == AuthRepository.AUTH_COOKIE_KEY || it == AuthRepository.SALT_KEY_COOKIE_KEY || "fidpw" in it }
    .entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }.encodeUtf8().sha256().hex()

internal class ThreadPageReads(factory: DiskCacheFactory, private val cookies: CookieStore) {
    private val cache = factory.create<ThreadPage>("thread_page", maxSize = 50, expiration = 24.hours)
    private val floors = factory.database.readerFloorPageQueries
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
            if (readerAccessScope(cookies.load().orEmpty()) != scope) return@read YamiboResult.NotLoggedIn
            if (!reverse && result is YamiboResult.Success) store(tid, author, page, scope, result.value)
            result
        }
    }

    fun get(tid: ThreadId, author: UserId?, page: Int): ThreadPage? =
        cache.get(key(tid, author, page, readerAccessScope(cookies.load().orEmpty())))
            ?.takeIf { it.isValidThreadPage(tid, page = page, authorId = author) }

    fun pageOf(tid: ThreadId, author: UserId?, pid: PostId): Int? =
        floors.getPage(readerAccessScope(cookies.load().orEmpty()), tid.value.toLong(),
            author?.value?.toLong() ?: 0L, pid.value.toLong()).executeAsOneOrNull()?.toInt()

    private fun store(tid: ThreadId, author: UserId?, page: Int, scope: String, value: ThreadPage) {
        val thread = tid.value.toLong()
        val authorScope = author?.value?.toLong() ?: 0L
        floors.transaction {
            floors.clearPage(scope, thread, authorScope, page.toLong())
            value.posts.forEach { floors.rememberFloor(scope, thread, authorScope, it.pid.value.toLong(), page.toLong()) }
        }
        cache.set(key(tid, author, page, scope), value)
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
