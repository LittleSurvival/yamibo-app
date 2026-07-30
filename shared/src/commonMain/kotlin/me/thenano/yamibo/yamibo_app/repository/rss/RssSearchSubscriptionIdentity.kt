package me.thenano.yamibo.yamibo_app.repository.rss

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint

internal fun normalizeRssSearchKeyword(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase()

internal fun rssSearchSubscriptionSyncId(
    query: String,
    forumId: Long?,
): String = "rss:${stableAppSyncFingerprint("${normalizeRssSearchKeyword(query)}|${forumId ?: ""}")}"
