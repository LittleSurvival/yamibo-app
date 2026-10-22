package me.thenano.yamibo.yamibo_app.util.auth

import io.github.littlesurvival.YamiboClient
import me.thenano.yamibo.yamibo_app.repository.AuthRepository

/** WebView display preferences must not select the parser used by native requests. */
fun nativeYamiboCookieHeader(
    cookieHeader: String?,
    cookiePrefix: String = AuthRepository.AUTH_COOKIE_KEY.removeSuffix("auth"),
): String = parseCookieStringToMap(cookieHeader)
    .filterKeys { !it.equals("${cookiePrefix}mobile", ignoreCase = true) }
    .entries.joinToString("; ") { (name, value) -> "$name=$value" }

/** Keep the persisted WebView snapshot intact; normalize every native import. */
fun YamiboClient.setNativeCookies(cookieHeader: String?, importNox: Boolean = false) {
    setCookie(nativeYamiboCookieHeader(cookieHeader), importNox = importNox)
}
