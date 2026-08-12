package me.thenano.yamibo.yamibo_app.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositoryCookieTest {
    @Test
    fun loginRequiresBothAuthenticationAndSaltKeyCookies() {
        assertTrue(
            AuthRepository.hasCompleteAuthenticationCookies(
                "EeqY_2132_auth=auth-value; EeqY_2132_saltkey=salt-value",
            ),
        )
        assertFalse(AuthRepository.hasCompleteAuthenticationCookies("EeqY_2132_auth=auth-value"))
        assertFalse(AuthRepository.hasCompleteAuthenticationCookies("EeqY_2132_saltkey=salt-value"))
        assertFalse(
            AuthRepository.hasCompleteAuthenticationCookies(
                "EeqY_2132_auth=; EeqY_2132_saltkey=salt-value",
            ),
        )
    }
}
