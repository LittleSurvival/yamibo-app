package me.thenano.yamibo.yamibo_app.webview

/**
 * Third-party hosts that are unreachable from the app's network (e.g. mainland China)
 * and would otherwise stall page load with a long connection timeout.
 *
 * The login page embeds Google Analytics (googletagmanager.com) which never resolves
 * from the app's network; WebView waits for its timeout before firing onPageFinished,
 * keeping the loading overlay visible for ~15s+. Intercepting these hosts with an empty
 * response lets the page finish immediately.
 *
 * Blocking is opt-in per WebView (`blockThirdPartyHosts = true`); only the login screen
 * enables it. Matching requires an exact host or a subdomain (`host.endsWith(".$domain")`),
 * so lookalike domains like `notgoogletagmanager.com` are never blocked.
 */
internal val BLOCKED_THIRD_PARTY_HOSTS = setOf(
    "googletagmanager.com",
    "google-analytics.com",
    "googleadservices.com",
    "googlesyndication.com",
    "doubleclick.net",
    "gtagjs.com",
)

/** True when [host] is a blocked host or a subdomain of one. */
internal fun isBlockedThirdPartyHost(host: String): Boolean {
    val normalized = host.lowercase()
    return BLOCKED_THIRD_PARTY_HOSTS.any { domain ->
        normalized == domain || normalized.endsWith(".$domain")
    }
}
