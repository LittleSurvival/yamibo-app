package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val NOX_COOKIE_NAME = "nox_jst_v1"

/**
 * Bounds the login WAF prewarm attempt. The SDK hidden challenger completes in ~1-3s on
 * reachable networks, so 5s covers slow-but-working networks while keeping the fail-open
 * fallback (the visible login WebView) prompt.
 *
 * The app's main [io.github.littlesurvival.YamiboClient] is configured with
 * `WafRecoveryConfig(challengeTimeoutMillis = this)` so an abandoned prewarm cannot leave an
 * orphan hidden WebView challenging in parallel with the visible login WebView.
 */
const val WAF_PREWARM_TIMEOUT_MILLIS = 5_000L

/** Outcome of a best-effort WAF cookie prewarm attempt. */
sealed interface WafPrewarmResult {
    /** The platform WebView cookie store holds a non-empty nox_jst_v1 after the attempt. */
    data object Success : WafPrewarmResult

    /** The attempt ran but no usable nox_jst_v1 was produced (e.g. the WAF was not cleared). */
    data object Failed : WafPrewarmResult

    /** The challenge request did not settle within the prewarm timeout. */
    data object TimedOut : WafPrewarmResult
}

/**
 * Prewarms the WAF clearance cookie so the login WebView can load the login route directly
 * instead of running the slow 405 challenge dance itself.
 *
 * [triggerChallenge] fires a request through the SDK fetch pipeline against the login route;
 * a 405 there engages the SDK hidden challenger, which completes in seconds and writes
 * `nox_jst_v1` into the platform WebView cookie store. Success is decided solely by the
 * post-challenge check that the platform store now holds a non-empty `nox_jst_v1` — the
 * pipeline result of the probe itself is irrelevant (the login page does not parse as any
 * SDK page type).
 */
internal suspend fun runWafCookiePrewarm(
    readPlatformCookieHeader: suspend () -> String,
    loadStoredCookieHeader: () -> String?,
    saveStoredCookieHeader: (String) -> Unit,
    importCookieHeader: (String) -> Unit,
    triggerChallenge: suspend () -> Unit,
    timeoutMillis: Long = WAF_PREWARM_TIMEOUT_MILLIS,
    onFailure: (Exception) -> Unit = {},
): WafPrewarmResult {
    suspend fun mergedPlatformNoxHeader(): String? {
        val platformHeader = readPlatformCookieHeader()
        val noxValue = cookieValue(platformHeader, NOX_COOKIE_NAME) ?: return null
        return mergeCookieValue(
            loadStoredCookieHeader(),
            NOX_COOKIE_NAME,
            noxValue,
        )
    }

    try {
        mergedPlatformNoxHeader()?.let(importCookieHeader)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        return WafPrewarmResult.Failed
    }

    val challengeSettled = try {
        withTimeoutOrNull(timeoutMillis) { triggerChallenge() } != null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        return WafPrewarmResult.Failed
    }
    if (!challengeSettled) return WafPrewarmResult.TimedOut

    return try {
        val verifiedHeader = mergedPlatformNoxHeader() ?: return WafPrewarmResult.Failed
        saveStoredCookieHeader(verifiedHeader)
        importCookieHeader(verifiedHeader)
        WafPrewarmResult.Success
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        WafPrewarmResult.Failed
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

/**
 * Mirrors the SDK's `ClientCookieStore.parsePairs`: strips CR/LF, rejects segments without a
 * name, and rejects names containing whitespace or control characters. Keeping the local
 * parser in sync matters because the app persists and re-injects cookie headers that the SDK
 * later parses.
 */
private fun cookiePairs(cookieHeader: String?): List<Pair<String, String>> =
    cookieHeader
        .orEmpty()
        .replace("\r", "")
        .replace("\n", "")
        .split(';')
        .mapNotNull { segment ->
            val separator = segment.indexOf('=')
            if (separator <= 0) return@mapNotNull null

            val name = segment.substring(0, separator).trim()
            val value = segment.substring(separator + 1).trim()
            if (name.isEmpty() || name.any { it.isWhitespace() || it <= '\u001f' }) null
            else name to value
        }
