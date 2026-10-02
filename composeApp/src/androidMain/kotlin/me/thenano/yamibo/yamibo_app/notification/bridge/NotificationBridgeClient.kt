package me.thenano.yamibo.yamibo_app.notification.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class NotificationApiException(val status: Int) : Exception("Notification API status $status")

internal class NotificationBridgeClient(private val origin: String) {
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
    private val streamClient = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 45_000
        }
    }

    suspend fun exchange(cookie: String, expectedUid: Int): NotificationSession {
        val json = call("session", HttpMethod.Post, body = buildJsonObject { put("site_id", NOTIFICATION_SITE) }, cookie = cookie)
        val uid = json["uid"]?.jsonPrimitive?.longOrNull
        require(uid == expectedUid.toLong() && uid > 0) { "Notification identity mismatch" }
        require(json["site_id"]?.jsonPrimitive?.contentOrNull == NOTIFICATION_SITE)
        require(json["token_type"]?.jsonPrimitive?.contentOrNull == "Bearer")
        val bearer = requireNotNull(json["access_token"]?.jsonPrimitive?.contentOrNull)
        require(bearer.isNotBlank() && bearer.length <= 4096 && bearer.none { it.isISOControl() })
        val expiry = requireNotNull(json["expires_at"]?.jsonPrimitive?.longOrNull) * 1000
        // Fail closed on invalid lifetime or clock skew, never extend server eligibility locally.
        require(expiry - System.currentTimeMillis() in 1_000..300_000)
        return NotificationSession(bearer, expectedUid, expiry)
    }

    suspend fun bind(session: NotificationSession, token: String, proof: InstallationProof?): InstallationProof {
        val json = call("devices", HttpMethod.Post, session, buildJsonObject {
            put("provider", "fcm")
            put("token", token)
            proof?.let { put("installation_id", it.id); put("binding_secret", it.secret) }
        })
        val id = requireNotNull(json["installation_id"]?.jsonPrimitive?.contentOrNull)
        val secret = json["binding_secret"]?.jsonPrimitive?.contentOrNull ?: proof?.secret
        require(cursorPattern.matches(id) && secret != null && secretPattern.matches(secret))
        require(proof == null || proof.id == id)
        val validUntil = requireNotNull(json["valid_until"]?.jsonPrimitive?.longOrNull) * 1000
        require(validUntil == session.expiresAtMillis)
        require((json["generation"]?.jsonPrimitive?.longOrNull ?: 0) > 0)
        return InstallationProof(id, secret)
    }

    suspend fun unbind(session: NotificationSession, proof: InstallationProof) {
        call("devices/${proof.id}", HttpMethod.Delete, session, proof = proof)
    }

    suspend fun revoke(session: NotificationSession) { call("session", HttpMethod.Delete, session) }

    suspend fun stream(
        session: NotificationSession,
        cursor: String?,
        onConnected: suspend () -> Unit,
        onFrame: suspend (NotificationFrame) -> Unit,
    ) {
        streamClient.prepareGet("$origin/notification-api/events") {
            header(HttpHeaders.Authorization, "Bearer ${session.bearer}")
            header(HttpHeaders.Accept, "text/event-stream")
            cursor?.takeIf(cursorPattern::matches)?.let { header("Last-Event-ID", it) }
        }.execute { response ->
            if (response.status.value != 200) throw NotificationApiException(response.status.value)
            require(response.headers[HttpHeaders.ContentType]?.substringBefore(';') == "text/event-stream")
            onConnected()
            val parser = NotificationFrameParser()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line(MAX_FRAME_CHARS) ?: break
                parser.accept(line)?.let { onFrame(it) }
            }
        }
    }

    private suspend fun call(
        path: String,
        method: HttpMethod,
        session: NotificationSession? = null,
        body: JsonObject? = null,
        cookie: String? = null,
        proof: InstallationProof? = null,
    ): JsonObject {
        val response = client.request("$origin/notification-api/$path") {
            this.method = method
            header("X-Yamibo-Session", "1")
            session?.let { header(HttpHeaders.Authorization, "Bearer ${it.bearer}") }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            proof?.let { header("X-Yamibo-Installation-Secret", it.secret) }
            body?.let {
                header(HttpHeaders.ContentType, "application/json")
                setBody(it.toString())
            }
        }
        if (response.status.value !in 200..299) throw NotificationApiException(response.status.value)
        if (response.status.value == 204) return buildJsonObject { }
        val text = response.bodyAsText()
        require(text.length <= MAX_FRAME_CHARS)
        return notificationJson.parseToJsonElement(text).jsonObject
    }

    fun close() { client.close(); streamClient.close() }
}
