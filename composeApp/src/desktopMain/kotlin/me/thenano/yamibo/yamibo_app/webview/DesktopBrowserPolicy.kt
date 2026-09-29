package me.thenano.yamibo.yamibo_app.webview

import java.net.URI

/** Keep sign-in clearance on the same user agent as yamibo-api 1.1.28 SignFactory. */
internal fun desktopBrowserUserAgent(url: String): String? {
    if (!isTrustedDesktopBrowserUrl(url)) return null
    val uri = URI(url)
    if (uri.path != "/plugin.php" || uri.rawQuery.orEmpty().split('&').none { it == "id=zqlj_sign" }) return null
    return "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}

internal fun isTrustedDesktopBrowserUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && uri.host.equals("bbs.yamibo.com", true) &&
        (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null
}.getOrDefault(false)

internal fun isDesktopWebUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

/** Challenge frames are not trusted destinations for navigation or authentication cookies. */
internal fun isAllowedDesktopBrowserNavigation(value: String, trustedOnly: Boolean, mainFrame: Boolean): Boolean {
    if (value == "about:blank") return true
    if (!trustedOnly) return isDesktopWebUrl(value)
    if (isTrustedDesktopBrowserUrl(value)) return true
    if (mainFrame) return false
    return runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && uri.host.equals("challenges.cloudflare.com", true) &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null
    }.getOrDefault(false)
}
