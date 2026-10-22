package me.thenano.yamibo.yamibo_app.util.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class CookieParserTest {
    @Test
    fun serviceCookieUsesPersistedAuthenticationAndPlatformNox() {
        assertEquals(
            "auth=account; sid=session; nox_jst_v1=fresh-clearance",
            composeYamiboClientCookieHeader(
                authenticationCookieHeader = "auth=account; sid=session; nox_jst_v1=stale-clearance",
                platformCookieHeader = "sid=webview-session; nox_jst_v1=fresh-clearance",
            ),
        )
    }

    @Test
    fun serviceCookieDoesNotImportPlatformAuthenticationCookies() {
        assertEquals(
            "nox_jst_v1=clearance",
            composeYamiboClientCookieHeader(
                authenticationCookieHeader = null,
                platformCookieHeader = "auth=stale-account; sid=stale-session; nox_jst_v1=clearance",
            ),
        )
    }
}
