package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun stripsCrLfBetweenSegmentsBeforeParsing() {
        assertEquals(
            "2",
            cookieValue("a=1;\r\nb=2", "b"),
        )
        assertEquals(
            "2",
            cookieValue("a=1;b=2\r\n", "b"),
        )
    }

    @Test
    fun rejectsCookieNamesContainingWhitespaceOrControlCharacters() {
        val header = "bad name=1; bad\u0001name=2; ok=3"
        assertNull(cookieValue(header, "bad name"))
        assertNull(cookieValue(header, "bad\u0001name"))
        assertEquals("3", cookieValue(header, "ok"))
    }

    @Test
    fun skipsSegmentsWithoutSeparator() {
        val header = "a=1; malformed; c=3"
        assertNull(cookieValue(header, "malformed"))
        assertEquals("3", cookieValue(header, "c"))
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
            },
        )

        assertEquals(WafPrewarmResult.Success, result)
        assertTrue(challengeTriggered)
        assertEquals("auth=token; nox_jst_v1=fresh", savedHeader)
        assertEquals(savedHeader, importedHeader)
    }

    @Test
    fun returnsFailedWhenPlatformStoreHasNoNoxAfterAttempt() = runBlocking {
        var savedHeader: String? = null

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = { savedHeader = it },
            importCookieHeader = {},
            triggerChallenge = {},
        )

        assertEquals(WafPrewarmResult.Failed, result)
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
            },
        )

        assertEquals(WafPrewarmResult.Success, result)
        assertEquals("auth=token; nox_jst_v1=fresh", importedHeader)
    }

    @Test
    fun returnsTimedOutWhenChallengeDoesNotSettle() = runBlocking {
        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = {},
            importCookieHeader = {},
            triggerChallenge = {
                delay(100)
            },
            timeoutMillis = 1,
        )

        assertEquals(WafPrewarmResult.TimedOut, result)
    }

    @Test
    fun returnsFailedWhenChallengeThrows() = runBlocking {
        var observedFailure: Exception? = null

        val result = runWafCookiePrewarm(
            readPlatformCookieHeader = { "" },
            loadStoredCookieHeader = { null },
            saveStoredCookieHeader = {},
            importCookieHeader = {},
            triggerChallenge = { error("network failed") },
            onFailure = { observedFailure = it },
        )

        assertEquals(WafPrewarmResult.Failed, result)
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
