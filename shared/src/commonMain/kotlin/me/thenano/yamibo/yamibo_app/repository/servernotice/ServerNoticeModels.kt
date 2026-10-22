package me.thenano.yamibo.yamibo_app.repository.servernotice

import kotlinx.serialization.Serializable

@Serializable
data class ServerNotice(
    val schemaVersion: Int,
    val httpStatus: Int,
    val revision: Long,
    val enabled: Boolean,
    val startsAt: Long,
    val expiresAt: Long,
    val message: String,
) {
    fun isValid(): Boolean = schemaVersion == 1 && httpStatus == 524 && revision > 0 &&
        startsAt > 0 && expiresAt >= startsAt && message.length <= 1000 &&
        (!enabled || (message.isNotBlank() && expiresAt > startsAt))

    fun activeMessage(now: Long): String? = message.takeIf {
        isValid() && enabled && now >= startsAt && now < expiresAt
    }
}

data class ServerNoticeSource(val name: String, val url: String)

val defaultServerNoticeSources = listOf(
    ServerNoticeSource("GitHub", "http://10.0.2.2:8768/notice/1"),
    ServerNoticeSource("Gitee", "http://10.0.2.2:8768/notice/2"),
    ServerNoticeSource("Gitea", "http://10.0.2.2:8768/notice/3"),
)
