package me.thenano.yamibo.yamibo_app.repository.servernotice

import io.github.littlesurvival.core.YamiboResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.*
import kotlin.time.TimeSource

class ServerNoticeTest {
    private val client = HttpClient(MockEngine { respond("") })
    private val sources = (1..3).map { ServerNoticeSource("$it", "https://notice.example/$it") }
    private val raw = "[HTTP 524] 請求失敗\n  url    : https://bbs.yamibo.com/forum.php?mod=forumdisplay&fid=30\n  body   : Cloudflare timeout"
    private val settings = MemorySettings()
    private var wall = 1_000_000L
    private var elapsed = 0L

    @AfterTest fun close() = client.close()

    private fun notice(revision: Long = 1, enabled: Boolean = true, start: Long = wall, end: Long = wall + 120_000) =
        ServerNotice(1, 524, revision, enabled, start, end, if (enabled) "百合會正在設備升級" else "")
    private fun encoded(value: ServerNotice) = Json.encodeToString(value)
    private fun repo(fetch: suspend (ServerNoticeSource) -> String?) = DefaultServerNoticeRepository(
        store = ServerNoticeStore(settings), sources = sources, now = { wall }, httpClient = client,
        fetch = fetch, elapsedMillis = { elapsed },
    )

    @Test fun recognizesOnlyTrustedHttpEnvelope() {
        assertTrue(isForum524Failure(raw))
        assertTrue(isForum524Failure(raw.replace("bbs.yamibo.com", "BBS.YAMIBO.COM")))
        listOf(
            raw.replace("524", "503"), raw.replace("https:", "http:"),
            raw.replace("bbs.yamibo.com", "bbs.yamibo.com.attacker.example"),
            raw.replace("bbs.yamibo.com", "other.example"),
            raw.replace("bbs.yamibo.com", "user@bbs.yamibo.com"),
            raw.replace("bbs.yamibo.com", "bbs.yamibo.com:444"),
            "[Timeout] $raw", "A body mentioning HTTP 524", "[HTTP 524] no request URL", "prefix $raw",
            "[HTTP 500] failure\n  url : https://bbs.yamibo.com/\n  body : $raw",
        ).forEach { assertFalse(isForum524Failure(it), it) }
    }

    @Test fun intervalIncludesStartAndExcludesEnd() {
        val n = notice(start = 100, end = 200)
        assertNull(n.activeMessage(99)); assertEquals(n.message, n.activeMessage(100))
        assertEquals(n.message, n.activeMessage(199)); assertNull(n.activeMessage(200))
    }

    @Test fun malformedContractsAreRejected() {
        val n = notice()
        listOf(n.copy(schemaVersion = 2), n.copy(httpStatus = 503), n.copy(revision = 0),
            n.copy(startsAt = 0), n.copy(expiresAt = n.startsAt), n.copy(message = " "),
            n.copy(message = "x".repeat(1001))).forEach { assertFalse(it.isValid()) }
        assertTrue(n.copy(enabled = false, expiresAt = n.startsAt, message = "").isValid())
    }

    @Test fun resolverReplacesOnlyFailureAndLeavesResultUntouched(): Unit = runBlocking {
        val originalCause = IllegalStateException("original")
        val failure = YamiboResult.Failure(raw, originalCause)
        val resolver = ForumErrorMessageResolver(repo { encoded(notice()) })
        assertEquals("百合會正在設備升級", resolver.resolve(failure))
        assertEquals(raw, failure.reason); assertSame(originalCause, failure.exception)
        assertEquals(raw, resolver.resolve(YamiboResult.Success(raw)))
        assertEquals(YamiboResult.Maintenance.message(), resolver.resolve(YamiboResult.Maintenance))
        assertEquals("HTTP 524", resolver.resolve(YamiboResult.NoPermission("HTTP 524")))
    }

    @Test fun non524DoesNotFetchNotice(): Unit = runBlocking {
        var requests = 0
        val resolver = ForumErrorMessageResolver(repo { requests++; error("should not fetch") })
        val other = raw.replace("524", "502")
        assertEquals(other, resolver.resolve(YamiboResult.Failure(other)))
        assertEquals(0, requests)
    }

    @Test fun highestRevisionWinsAcrossMirrors(): Unit = runBlocking {
        val repository = repo { encoded(notice(it.name.toLong())) }
        assertEquals(3, repository.refresh()?.revision)
        assertEquals("百合會正在設備升級", repository.active524Message())
    }

    @Test fun cancellationWinsOverOlderActiveMirrorsAndSurvivesRestart(): Unit = runBlocking {
        val repository = repo { encoded(notice(if (it.name == "2") 5 else 4, enabled = it.name != "2")) }
        assertNull(repository.active524Message()); assertEquals(5, repository.notice.value?.revision)
        val restarted = repo { encoded(notice(4)) }
        assertNull(restarted.active524Message()); assertEquals(5, restarted.notice.value?.revision)
    }

    @Test fun newestExpiredDocumentDoesNotFallBackToOlderActiveNotice(): Unit = runBlocking {
        val repository = repo { encoded(if (it.name == "3") notice(9, start = wall - 20, end = wall - 1) else notice(8)) }
        assertNull(repository.active524Message()); assertEquals(9, repository.notice.value?.revision)
    }

    @Test fun futureDocumentDoesNotDisplayEarly(): Unit = runBlocking {
        assertNull(repo { encoded(notice(start = wall + 1)) }.active524Message())
    }

    @Test fun concurrentFailuresShareRefreshAndSixtySecondCache(): Unit = runBlocking {
        var requests = 0
        val repository = repo { requests++; delay(20); encoded(notice()) }
        coroutineScope { (1..20).map { async { repository.active524Message() } }.awaitAll() }
        assertEquals(3, requests)
        elapsed = 59_999; repository.refresh(); assertEquals(3, requests)
        elapsed = 60_000; repository.refresh(); assertEquals(6, requests)
    }

    @Test fun failedRefreshUsesUnexpiredCacheButChecksExpiryEveryTime(): Unit = runBlocking {
        var online = true
        val repository = repo { if (online) encoded(notice()) else throw IllegalStateException("offline") }
        assertNotNull(repository.active524Message())
        online = false; elapsed = 60_000; wall += 60_000
        assertNotNull(repository.active524Message())
        wall += 60_000
        assertNull(repository.active524Message())
    }

    @Test fun allSourcesFailedKeepsOriginalText(): Unit = runBlocking {
        assertEquals(raw, ForumErrorMessageResolver(repo { null }).resolve(YamiboResult.Failure(raw)))
    }

    @Test fun invalidAndOversizedDocumentsDoNotHideOriginalError(): Unit = runBlocking {
        val repository = repo {
            when (it.name) { "1" -> "not json"; "2" -> "x".repeat(16_385); else -> encoded(notice().copy(schemaVersion = 9)) }
        }
        assertNull(repository.refresh()); assertEquals(raw, ForumErrorMessageResolver(repository).resolve(raw))
    }

    @Test fun aSlowMirrorDoesNotDiscardFastResultAndFinishesWithinBudget(): Unit = runBlocking {
        val repository = repo { if (it.name == "3") delay(10_000); encoded(notice()) }
        val start = TimeSource.Monotonic.markNow()
        assertNotNull(repository.active524Message())
        assertTrue(start.elapsedNow().inWholeMilliseconds < 3500)
    }

    @Test fun callerCancellationPropagatesAndDoesNotThrottleNextRefresh(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); var blocked = true
        val repository = repo { if (blocked) { started.complete(Unit); awaitCancellation() }; encoded(notice()) }
        val pending = launch { repository.refresh() }
        started.await(); pending.cancelAndJoin(); blocked = false
        assertNotNull(repository.active524Message())
    }

    @Test fun equalOrOlderRevisionsCannotMutateCachedNotice(): Unit = runBlocking {
        var current = notice(10)
        val repository = repo { encoded(current) }
        repository.refresh(); elapsed = 60_000; current = notice(10, enabled = false)
        assertNotNull(repository.active524Message())
        elapsed = 120_000; current = notice(9, enabled = false)
        assertNotNull(repository.active524Message())
    }

    @Test fun unknownJsonFieldsAreForwardCompatible(): Unit = runBlocking {
        val text = encoded(notice()).dropLast(1) + ",\"extra\":true}"
        assertNotNull(repo { text }.active524Message())
    }

    @Test fun corruptPersistedCacheDoesNotBlockRefresh(): Unit = runBlocking {
        settings.putString("server_notice.latest_524.v1", "broken")
        assertNotNull(repo { encoded(notice()) }.active524Message())
    }
}

private class MemorySettings : SettingsStore {
    private val values = mutableMapOf<String, Any>()
    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun hasKey(key: String) = key in values
}
