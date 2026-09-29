package me.thenano.yamibo.yamibo_app.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateAssetTest {
    @Test fun architectureAliasesMatchButOtherArchitecturesDoNot() {
        assertTrue(desktopUpdateArchitectureMatches("x86_64", "amd64"))
        assertTrue(desktopUpdateArchitectureMatches("arm64", "aarch64"))
        assertTrue(desktopUpdateArchitectureMatches("ARM64-V8A", "arm64"))
        assertTrue(desktopUpdateArchitectureMatches("universal", "aarch64"))
        assertTrue(desktopUpdateArchitectureMatches(null, "amd64"))
        assertFalse(desktopUpdateArchitectureMatches("arm64", "amd64"))
        assertFalse(desktopUpdateArchitectureMatches("x86", "amd64"))
        assertFalse(desktopUpdateArchitectureMatches("unknown", "aarch64"))
    }

    @Test fun explicitlyForeignPlatformIsNeverAnInstallerCandidate() {
        val platform = DesktopAppUpdatePlatform()
        val type = platform.supportedAssetTypes.first()
        val foreign = if (platform.platformKey == "windows") "linux" else "windows"
        assertFalse(platform.supportsAsset(type, foreign, null))
        assertTrue(platform.supportsAsset(type, platform.platformKey, null))
        assertTrue(platform.supportsAsset(type, null, null))
        assertFalse(platform.supportsAsset("apk", platform.platformKey, null))
    }
}
