package me.thenano.yamibo.yamibo_app.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopBrowserPolicyTest {
    @Test fun signPageUsesApiUserAgentWithoutChangingOtherOrigins() {
        val expected = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        assertEquals(expected, desktopBrowserUserAgent("https://bbs.yamibo.com/plugin.php?id=zqlj_sign"))
        assertEquals(expected, desktopBrowserUserAgent("https://bbs.yamibo.com/plugin.php?x=1&id=zqlj_sign&sign=1"))
        listOf("https://bbs.yamibo.com/forum.php", "https://bbs.yamibo.com/plugin.php?id=other",
            "https://bbs.yamibo.com/plugin.php?id=zqlj_sign_fake", "http://bbs.yamibo.com/plugin.php?id=zqlj_sign",
            "https://evil.test/plugin.php?id=zqlj_sign").forEach { assertNull(desktopBrowserUserAgent(it), it) }
    }
    @Test fun cloudflareChallengeIsAllowedOnlyAsAnHttpsSubframe() {
        val challenge = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/turnstile/"
        assertTrue(isAllowedDesktopBrowserNavigation(challenge, trustedOnly = true, mainFrame = false))
        assertTrue(isAllowedDesktopBrowserNavigation("https://CHALLENGES.CLOUDFLARE.COM:443/", true, false))
        assertFalse(isAllowedDesktopBrowserNavigation(challenge, trustedOnly = true, mainFrame = true))
        assertFalse(isTrustedDesktopBrowserUrl(challenge))
        listOf("http://challenges.cloudflare.com/", "https://challenges.cloudflare.com:8443/",
            "https://challenges.cloudflare.com.evil.test/", "https://evil.test@challenges.cloudflare.com/",
            "https://evil.test/", "file:///C:/secret", "javascript:alert(1)", "data:text/html,test")
            .forEach { assertFalse(isAllowedDesktopBrowserNavigation(it, true, false), it) }
        assertTrue(isAllowedDesktopBrowserNavigation("https://bbs.yamibo.com/", true, true))
        assertTrue(isAllowedDesktopBrowserNavigation("about:blank", true, false))
    }
    @Test fun authenticationIsLimitedToExactHttpsOrigin() {
        assertTrue(isTrustedDesktopBrowserUrl("https://bbs.yamibo.com/forum.php?mod=forumdisplay"))
        assertTrue(isTrustedDesktopBrowserUrl("https://BBS.YAMIBO.COM:443/"))
        listOf("http://bbs.yamibo.com/", "https://bbs.yamibo.com.evil.test/", "https://evil.test@bbs.yamibo.com/",
            "https://bbs.yamibo.com:8443/", "file:///etc/passwd", "javascript:alert(1)", "https://yamibo.com/",
            "https://bbs.yamibo.com\\@evil.test/").forEach { assertFalse(isTrustedDesktopBrowserUrl(it), it) }
    }
    @Test fun externalNavigationRejectsLocalFilesAndExecutableSchemes() {
        assertTrue(isDesktopWebUrl("https://example.com/"))
        listOf("file:///C:/secret", "javascript:alert(1)", "mailto:me@example.com", "data:text/html,test")
            .forEach { assertFalse(isDesktopWebUrl(it), it) }
    }
}
