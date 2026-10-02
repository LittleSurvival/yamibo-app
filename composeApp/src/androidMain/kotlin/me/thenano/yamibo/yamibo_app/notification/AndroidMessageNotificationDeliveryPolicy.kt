package me.thenano.yamibo.yamibo_app.notification

import java.security.MessageDigest
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

/** Runtime serializes event writes with its check mutex and cooldown writes with its posting fence. */
internal class AndroidMessageNotificationDeliveryPolicy(
    private val store: SettingsStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun hasProcessed(siteId: String, userId: Int, eventId: String): Boolean =
        eventKey(siteId, userId, eventId) in processedEvents()

    /** Call only after a successful delivery or an intentional terminal suppression. */
    fun markProcessed(siteId: String, userId: Int, eventId: String) {
        val digest = eventKey(siteId, userId, eventId)
        val events = processedEvents().filterNot { it == digest } + digest
        store.putString(EVENTS_KEY, events.takeLast(MAX_PROCESSED_EVENTS).joinToString("\n"))
    }

    fun clearProcessed(siteId: String, userId: Int) {
        val prefix = "${accountDigest(siteId, userId)}:"
        store.putString(EVENTS_KEY, processedEvents().filterNot { it.startsWith(prefix) }.joinToString("\n"))
    }

    fun isCoolingDown(siteId: String, userId: Int): Boolean {
        val last = cooldowns()[accountDigest(siteId, userId)] ?: return false
        val now = nowMillis()
        // A backwards wall-clock adjustment must not suppress notifications indefinitely.
        return now >= last && now - last < COOLDOWN_MILLIS
    }

    fun recordDeliveryOrOpen(siteId: String, userId: Int) {
        val account = accountDigest(siteId, userId)
        val entries = cooldowns().apply {
            remove(account)
            put(account, nowMillis())
        }
        store.putString(
            COOLDOWNS_KEY,
            entries.entries.toList().takeLast(MAX_COOLDOWN_ACCOUNTS)
                .joinToString("\n") { (key, time) -> "$key:$time" },
        )
    }

    private fun processedEvents(): List<String> = store.getString(EVENTS_KEY, "")
        .lineSequence().filter { line ->
            line.length == 129 && isDigest(line.substringBefore(':')) &&
                isDigest(line.substringAfter(':', ""))
        }.toList().takeLast(MAX_PROCESSED_EVENTS)

    private fun cooldowns(): LinkedHashMap<String, Long> = linkedMapOf<String, Long>().apply {
        store.getString(COOLDOWNS_KEY, "").lineSequence().take(MAX_COOLDOWN_ACCOUNTS)
            .forEach { line ->
                val key = line.substringBefore(':')
                val time = line.substringAfter(':', "").toLongOrNull()
                if (isDigest(key) && time != null && time >= 0) put(key, time)
            }
    }

    private fun eventKey(siteId: String, userId: Int, eventId: String): String =
        "${accountDigest(siteId, userId)}:${digest("${eventId.length}:$eventId")}"

    private fun accountDigest(siteId: String, userId: Int): String =
        digest("${siteId.length}:$siteId:$userId")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isDigest(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    companion object {
        const val SITE_ID = "yamibo"
        const val COOLDOWN_MILLIS = 60_000L
        const val MAX_PROCESSED_EVENTS = 128
        private const val MAX_COOLDOWN_ACCOUNTS = 16
        private const val EVENTS_KEY = "message_notifications.android.processed_events"
        private const val COOLDOWNS_KEY = "message_notifications.android.cooldowns"
    }
}

internal fun messageNotificationMuteMatchesAccount(expectedUserId: Int?, currentUserId: Int?): Boolean =
    expectedUserId != null && expectedUserId > 0 && expectedUserId == currentUserId

// Firebase Messaging 25.1.0 CommonNotificationBuilder.getTag() generates this prefix.
internal fun isLegacyFirebaseMessageNotification(
    tag: String?,
    title: String? = null,
    body: String? = null,
): Boolean = tag?.startsWith("FCM-Notification:") == true || (
    // The deployed server supplies event_id as an explicit tag, overriding the SDK prefix.
    tag != null && LEGACY_SERVER_EVENT_TAG.matches(tag) && title == "Yamibo" && body == "你有新的通知"
)

private val LEGACY_SERVER_EVENT_TAG =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

/** A short critical section for final posting vs. mute; never hold it during network IO. */
internal class AndroidMessageNotificationPostingFence {
    private val lock = Any()

    fun postIfAllowed(canPost: () -> Boolean, post: () -> Unit): Boolean = synchronized(lock) {
        if (!canPost()) return@synchronized false
        post()
        true
    }

    fun <T> update(action: () -> T): T = synchronized(lock, action)
}
