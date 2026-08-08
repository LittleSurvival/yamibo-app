package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val NOX_COOKIE_NAME = "nox_jst_v1"
internal const val WAF_PREWARM_TIMEOUT_MILLIS = 10_000L

internal suspend fun runWafCookiePrewarm(
    readPlatformCookieHeader: suspend () -> String,
    loadStoredCookieHeader: () -> String?,
    saveStoredCookieHeader: (String) -> Unit,
    importCookieHeader: (String) -> Unit,
    triggerChallenge: suspend () -> Unit,
    timeoutMillis: Long = WAF_PREWARM_TIMEOUT_MILLIS,
    onFailure: (Exception) -> Unit = {},
): Boolean {
    suspend fun importPlatformNox(): Boolean {
        val platformHeader = readPlatformCookieHeader()
        val noxValue = cookieValue(platformHeader, NOX_COOKIE_NAME) ?: return false
        val mergedHeader = mergeCookieValue(
            loadStoredCookieHeader(),
            NOX_COOKIE_NAME,
            noxValue,
        )
        saveStoredCookieHeader(mergedHeader)
        importCookieHeader(mergedHeader)
        return true
    }

    try {
        if (importPlatformNox()) return true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        return false
    }

    try {
        withTimeoutOrNull(timeoutMillis) {
            triggerChallenge()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
    }

    return try {
        importPlatformNox()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        false
    }
}

internal fun cookieValue(cookieHeader: String?, cookieName: String): String? =
    cookiePairs(cookieHeader)
        .lastOrNull { (name, _) -> name.equals(cookieName, ignoreCase = true) }
        ?.second
        ?.takeIf(String::isNotBlank)

internal fun mergeCookieValue(
    cookieHeader: String?,
    cookieName: String,
    cookieValue: String,
): String = buildList {
    addAll(
        cookiePairs(cookieHeader)
            .filterNot { (name, _) -> name.equals(cookieName, ignoreCase = true) }
    )
    add(cookieName to cookieValue)
}.joinToString("; ") { (name, value) -> "$name=$value" }

private fun cookiePairs(cookieHeader: String?): List<Pair<String, String>> =
    cookieHeader
        .orEmpty()
        .split(';')
        .mapNotNull { segment ->
            val separator = segment.indexOf('=')
            if (separator <= 0) return@mapNotNull null

            val name = segment.substring(0, separator).trim()
            val value = segment.substring(separator + 1).trim()
            if (name.isEmpty()) null else name to value
        }
