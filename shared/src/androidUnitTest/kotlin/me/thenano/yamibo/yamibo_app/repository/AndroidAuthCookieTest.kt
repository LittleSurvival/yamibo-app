package me.thenano.yamibo.yamibo_app.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAuthCookieTest {
    @Test
    fun onlyAuthenticationPairGetsRollingExpiry() {
        val maxAge = AuthRepository.PERSISTENT_COOKIE_MAX_AGE_SECONDS
        assertEquals(
            "EeqY_2132_auth=auth; Max-Age=$maxAge; Path=/; Secure; HttpOnly",
            AndroidAuthRepository.cookieDirective(AuthRepository.AUTH_COOKIE_KEY, "auth", persist = true),
        )
        assertEquals(
            "EeqY_2132_saltkey=salt; Max-Age=$maxAge; Path=/; Secure; HttpOnly",
            AndroidAuthRepository.cookieDirective(AuthRepository.SALT_KEY_COOKIE_KEY, "salt", persist = true),
        )
        assertEquals(
            "EeqY_2132_auth=auth; Path=/; Secure",
            AndroidAuthRepository.cookieDirective(AuthRepository.AUTH_COOKIE_KEY, "auth", persist = false),
        )
        assertEquals(
            "EeqY_2132_sid=sid; Path=/; Secure",
            AndroidAuthRepository.cookieDirective("EeqY_2132_sid", "sid", persist = false),
        )
        assertEquals(
            "nox_jst_v1=nox; Path=/; Secure",
            AndroidAuthRepository.cookieDirective("nox_jst_v1", "nox", persist = false),
        )
    }
}
