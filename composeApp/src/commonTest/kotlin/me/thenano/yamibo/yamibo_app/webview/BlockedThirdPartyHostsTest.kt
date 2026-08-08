package me.thenano.yamibo.yamibo_app.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockedThirdPartyHostsTest {
    @Test
    fun blocksKnownUnreachableHostsExactly() {
        assertTrue(isBlockedThirdPartyHost("googletagmanager.com"))
        assertTrue(isBlockedThirdPartyHost("google-analytics.com"))
        assertTrue(isBlockedThirdPartyHost("googleadservices.com"))
        assertTrue(isBlockedThirdPartyHost("googlesyndication.com"))
        assertTrue(isBlockedThirdPartyHost("doubleclick.net"))
        assertTrue(isBlockedThirdPartyHost("gtagjs.com"))
    }

    @Test
    fun blocksSubdomainsOfBlockedHosts() {
        assertTrue(isBlockedThirdPartyHost("www.googletagmanager.com"))
        assertTrue(isBlockedThirdPartyHost("ssl.google-analytics.com"))
        assertTrue(isBlockedThirdPartyHost("adservice.googleadservices.com"))
    }

    @Test
    fun blocksCaseInsensitively() {
        assertTrue(isBlockedThirdPartyHost("WWW.GOOGLETAGMANAGER.COM"))
        assertTrue(isBlockedThirdPartyHost("GoOgLeTaGmAnAgEr.com"))
    }

    @Test
    fun doesNotBlockLookalikeHostsEndingWithBlockedDomain() {
        assertFalse(isBlockedThirdPartyHost("notgoogletagmanager.com"))
        assertFalse(isBlockedThirdPartyHost("mydoubleclick.net"))
        assertFalse(isBlockedThirdPartyHost("fakegtagjs.com"))
    }

    @Test
    fun doesNotBlockSuffixesAfterBlockedDomain() {
        assertFalse(isBlockedThirdPartyHost("googletagmanager.com.evil.com"))
        assertFalse(isBlockedThirdPartyHost("doubleclick.net.attacker.io"))
    }

    @Test
    fun neverBlocksYamiboSubresources() {
        assertFalse(isBlockedThirdPartyHost("bbs.yamibo.com"))
        assertFalse(isBlockedThirdPartyHost("static.yamibo.com"))
        assertFalse(isBlockedThirdPartyHost("img.yamibo.com"))
    }
}
