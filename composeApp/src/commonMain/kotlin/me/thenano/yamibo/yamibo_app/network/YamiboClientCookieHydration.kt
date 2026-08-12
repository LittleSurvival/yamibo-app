package me.thenano.yamibo.yamibo_app.network

import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap

internal fun composeYamiboClientCookieHeader(
    authenticationCookieHeader: String?,
    platformCookieHeader: String?,
): String {
    val authenticationCookies = parseCookieStringToMap(authenticationCookieHeader)
        .filterKeys { !it.equals(NOX_COOKIE_NAME, ignoreCase = true) }
    val noxCookie = parseCookieStringToMap(platformCookieHeader)
        .entries
        .lastOrNull { it.key.equals(NOX_COOKIE_NAME, ignoreCase = true) }

    return buildList {
        authenticationCookies.forEach { (name, value) -> add("$name=$value") }
        noxCookie?.let { (name, value) -> add("$name=$value") }
    }.joinToString("; ")
}

internal const val NOX_COOKIE_NAME = "nox_jst_v1"
