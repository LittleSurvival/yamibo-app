package me.thenano.yamibo.yamibo_app.notification.bridge

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.min
import kotlin.random.Random

internal const val NOTIFICATION_SITE = "yamibo"
internal const val MAX_FRAME_CHARS = 32_768
internal val notificationJson = Json { ignoreUnknownKeys = true }
internal val cursorPattern = Regex("[0-9a-f]{48}")
internal val secretPattern = Regex("[0-9a-f]{64}")
internal val eventIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
internal val eventTypes = setOf("notification.upsert", "pm.created", "pm.announcement")

internal data class NotificationSession(val bearer: String, val uid: Int, val expiresAtMillis: Long)
internal data class InstallationProof(val id: String, val secret: String)
internal data class NotificationSignal(val type: String, val eventId: String)
internal data class NotificationFrame(val id: String?, val type: String, val data: String)

/** Cookies may only be exchanged at the existing native forum's exact HTTPS origin. */
internal fun trustedNotificationOrigin(configured: String, forum: String): String? {
    if (configured.isBlank()) return null
    fun origin(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
            it.rawQuery == null && it.rawFragment == null && (it.rawPath.isNullOrEmpty() || it.rawPath == "/")
    }
    val candidate = origin(configured) ?: return null
    val trusted = origin(forum) ?: return null
    if (!candidate.host.equals(trusted.host, ignoreCase = true)) return null
    fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    if (port(candidate) != port(trusted)) return null
    return configured.removeSuffix("/")
}

internal fun parseNotificationSignal(type: String, raw: String): NotificationSignal? {
    if (type !in eventTypes || raw.length > MAX_FRAME_CHARS) return null
    return runCatching {
        val body = notificationJson.parseToJsonElement(raw).jsonObject
        require(body["version"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull == 1)
        require(body["site_id"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull == NOTIFICATION_SITE)
        require((body["timestamp"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: -1) >= 0)
        val eventId = requireNotNull(body["event_id"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull)
        require(eventIdPattern.matches(eventId))
        val source = body["source"]?.jsonObject ?: error("Missing source")
        fun positive(name: String) = (source[name]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: 0L) > 0L
        require(when (type) {
            "notification.upsert" -> positive("notification_id")
            "pm.created" -> positive("pmid") && positive("plid")
            else -> positive("gpmid")
        })
        // Deliberately retain no content, links or source IDs from this invalidation hint.
        NotificationSignal(type, eventId)
    }.getOrNull()
}

/** Bounded SSE parser: comments, CRLF and multi-line data; never interpret a cursor as an event ID. */
internal class NotificationFrameParser {
    private var id: String? = null
    private var type = "message"
    private val data = StringBuilder()
    private var length = 0

    fun accept(rawLine: String): NotificationFrame? {
        val line = rawLine.removeSuffix("\r")
        length += line.length
        require(length <= MAX_FRAME_CHARS) { "Oversized notification frame" }
        if (line.isEmpty()) {
            val frame = if (data.isNotEmpty()) NotificationFrame(id, type, data.toString().removeSuffix("\n")) else null
            id = null
            type = "message"
            data.clear()
            length = 0
            return frame
        }
        if (line.startsWith(":")) return null
        val name = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (name) {
            "id" -> if ('\u0000' !in value) id = value
            "event" -> type = value
            "data" -> data.append(value).append('\n')
        }
        return null
    }
}

internal fun reconnectDelayMillis(attempt: Int, random: Random = Random.Default): Long {
    val ceiling = min(60_000L, 1_000L shl attempt.coerceIn(0, 6))
    return random.nextLong(ceiling / 2, ceiling + 1)
}

internal fun renewalDelayMillis(expiresAtMillis: Long, nowMillis: Long): Long =
    (expiresAtMillis - nowMillis - 20_000L).coerceIn(1_000L, 280_000L)
