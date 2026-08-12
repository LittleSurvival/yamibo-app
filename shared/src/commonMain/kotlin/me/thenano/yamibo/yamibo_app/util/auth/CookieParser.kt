package me.thenano.yamibo.yamibo_app.util.auth

fun parseCookieStringToMap(cookieString: String?): Map<String, String> {
    if (cookieString.isNullOrEmpty()) return emptyMap()
    return cookieString
        .split(";")
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null

            val key = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()

            if (key.isEmpty()) null else key to value
        }
        .toMap()
}

fun composeYamiboClientCookieHeader(
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

private const val NOX_COOKIE_NAME = "nox_jst_v1"
