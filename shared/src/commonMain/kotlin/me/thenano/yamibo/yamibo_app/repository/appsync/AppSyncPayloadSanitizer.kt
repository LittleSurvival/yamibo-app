package me.thenano.yamibo.yamibo_app.repository.appsync

/**
 * AppSync must only transport remotely fetchable thread covers. Local data URIs are cache
 * payloads, not portable reading-history metadata, and can be large enough to overflow a journal.
 */
internal fun appSyncThreadCoverOrNull(value: String?): String? {
    val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeLength = when {
        candidate.startsWith("https://", ignoreCase = true) -> "https://".length
        candidate.startsWith("http://", ignoreCase = true) -> "http://".length
        else -> return null
    }
    val address = candidate.substring(schemeLength)
    if (address.isBlank() || address.startsWith("data:", ignoreCase = true)) return null
    if (candidate.any(Char::isWhitespace)) return null
    return candidate
}
