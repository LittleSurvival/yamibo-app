package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WafCookiePrewarmTest {
    @Test
    fun readsCookieValueContainingEquals() {
        assertEquals(
            "part1=part2",
            cookieValue("session=abc; nox_jst_v1=part1=part2", NOX_COOKIE_NAME),
        )
    }

    @Test
    fun returnsNullForMissingOrBlankCookie() {
        assertNull(cookieValue("session=abc", NOX_COOKIE_NAME))
        assertNull(cookieValue("nox_jst_v1=", NOX_COOKIE_NAME))
    }

    @Test
    fun addsNoxCookieToEmptyHeader() {
        assertEquals(
            "nox_jst_v1=fresh",
            mergeCookieValue(null, NOX_COOKIE_NAME, "fresh"),
        )
    }

    @Test
    fun preservesAuthenticationCookiesWhenAddingNox() {
        assertEquals(
            "auth=token; theme=dark; nox_jst_v1=fresh",
            mergeCookieValue("auth=token; theme=dark", NOX_COOKIE_NAME, "fresh"),
        )
    }

    @Test
    fun replacesNoxCaseInsensitivelyWithoutDuplicates() {
        assertEquals(
            "auth=token; theme=dark; nox_jst_v1=fresh",
            mergeCookieValue(
                "auth=token; NOX_JST_V1=old; theme=dark; nox_jst_v1=older",
                NOX_COOKIE_NAME,
                "fresh",
            ),
        )
    }

    @Test
    fun validatesPlatformNoxBeforeCompletingPrewarm() = runBlocking {
        var challengeTriggered = false
        var savedHeader = ""
        var importedHeader = ""

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "nox_jst_v1=fresh" },
            loadStoredCookieHeader = { "auth=token; nox_jst_v1=old" },
            saveStoredCookieHeader = { savedHeader = it },
            importCookieHeader = { importedHeader = it },
            triggerChallenge = {
                challengeTriggered = true
                true
            },
        )

        assertTrue(result)
        assertTrue(challengeTriggered)
        assertEquals("auth=token; nox_jst_v1=fresh", savedHeader)
        assertEquals(savedHeader, importedHeader)
    }

    @Test
    fun returnsFalseWhenPlatformNoxFailsValidation() = runBlocking {
        var savedHeader: String? = null

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "nox_jst_v1=stale" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = { savedHeader = it },
            importCookieHeader = {},
            triggerChallenge = { false },
        )

        assertFalse(result)
        assertNull(savedHeader)
    }

    @Test
    fun importsNoxWrittenByChallenge() = runBlocking {
        var platformHeader = ""
        var importedHeader = ""

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { platformHeader },
            loadStoredCookieHeader = { "auth=token" },
            saveStoredCookieHeader = {},
            importCookieHeader = { importedHeader = it },
            triggerChallenge = {
                platformHeader = "nox_jst_v1=fresh"
                true
            },
        )

        assertTrue(result)
        assertEquals("auth=token; nox_jst_v1=fresh", importedHeader)
    }

    @Test
    fun returnsFalseAfterChallengeTimeout() = runBlocking {
        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = {},
            importCookieHeader = {},
            triggerChallenge = {
                delay(100)
                true
            },
            timeoutMillis = 1,
        )

        assertFalse(result)
    }

    @Test
    fun returnsFalseWhenChallengeFails() = runBlocking {
        var observedFailure: Exception? = null

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = {},
            importCookieHeader = {},
            triggerChallenge = { error("network failed") },
            onFailure = { observedFailure = it },
        )

        assertFalse(result)
        assertEquals("network failed", observedFailure?.message)
    }

    @Test
    fun propagatesCancellation() {
        assertFailsWith<CancellationException> {
            runBlocking {
                runWafCookiePrewarm(
                    readPlatformCookieHeader = { "" },
                    loadStoredCookieHeader = { null },
                    saveStoredCookieHeader = {},
                    importCookieHeader = {},
                    triggerChallenge = { throw CancellationException("cancelled") },
                )
            }
        }
    }
}
