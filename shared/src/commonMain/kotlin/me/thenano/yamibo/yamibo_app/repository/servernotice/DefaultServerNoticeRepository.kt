package me.thenano.yamibo.yamibo_app.repository.servernotice

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.ServerNoticeRepository
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import kotlin.time.TimeSource

class DefaultServerNoticeRepository(
    private val store: ServerNoticeStore,
    private val sources: List<ServerNoticeSource> = defaultServerNoticeSources,
    private val now: () -> Long = ::currentTimeMillis,
    private val httpClient: HttpClient = HttpClient {
        // Separate from forum clients: no authentication, cookie handshake or WAF recovery.
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 1500
            requestTimeoutMillis = 2500
            socketTimeoutMillis = 2500
        }
    },
    private val fetch: suspend (ServerNoticeSource) -> String? = { source ->
        val response = httpClient.get(source.url)
        if (response.status.isSuccess()) response.bodyAsText() else null
    },
    private val elapsedMillis: () -> Long = monotonicClock(),
) : ServerNoticeRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(store.load())
    private val mutex = Mutex()
    private var lastAttempt: Long? = null

    override val notice: StateFlow<ServerNotice?> = state.asStateFlow()

    override suspend fun refresh(): ServerNotice? = mutex.withLock {
        if (lastAttempt?.let { elapsedMillis() - it < 60_000 } == true) {
            return@withLock state.value
        }
        val candidates = withTimeoutOrNull(3000) {
            coroutineScope {
                sources.map { source ->
                    async {
                        withTimeoutOrNull(2500) {
                            try {
                                val body = fetch(source) ?: return@withTimeoutOrNull null
                                if (body.length > 16_384) return@withTimeoutOrNull null
                                json.decodeFromString<ServerNotice>(body).takeIf(ServerNotice::isValid)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Logger.d("ServerNotice", "Notice unavailable source=${source.name}", error)
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty()
        lastAttempt = elapsedMillis()
        val latest = candidates.maxByOrNull(ServerNotice::revision)
        if (latest != null && latest.revision > (state.value?.revision ?: 0)) {
            state.value = latest
            try {
                store.save(latest)
            } catch (error: Exception) {
                Logger.w("ServerNotice", "Unable to persist notice", error)
            }
        }
        state.value
    }

    override suspend fun active524Message(): String? = refresh()?.activeMessage(now())
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
