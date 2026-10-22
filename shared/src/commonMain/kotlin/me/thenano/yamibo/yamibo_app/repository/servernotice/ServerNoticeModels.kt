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
    ServerNoticeSource("GitHub", "https://raw.githubusercontent.com/LittleSurvival/yamibo-app/update-release/status/forum-524.json"),
    ServerNoticeSource("Gitee", "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/status/forum-524.json"),
    ServerNoticeSource("Gitea", "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/status/forum-524.json?ref=main"),
)
