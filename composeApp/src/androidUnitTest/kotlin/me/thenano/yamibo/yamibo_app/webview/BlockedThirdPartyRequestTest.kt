package me.thenano.yamibo.yamibo_app.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockedThirdPartyRequestTest {
    @Test
    fun blocksKnownUnreachableSubresources() {
        assertTrue(
            shouldBlockThirdPartyRequest(
                targetUrl = "https://www.googletagmanager.com/gtag/js?id=test",
                isForMainFrame = false,
            ),
        )
    }

    @Test
    fun allowsMainFrameNavigationToBlockedHosts() {
        assertFalse(
            shouldBlockThirdPartyRequest(
                targetUrl = "https://googleadservices.com/pagead/aclk?target=yamibo",
                isForMainFrame = true,
            ),
        )
    }
}
