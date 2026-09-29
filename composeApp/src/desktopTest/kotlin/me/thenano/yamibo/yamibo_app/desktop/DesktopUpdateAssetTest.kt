package me.thenano.yamibo.yamibo_app.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateAsset

class DesktopUpdateAssetTest {
    @Test fun verifiedDownloadReplacesTargetOnlyAfterHashAndSizeMatch() = runBlocking {
        val directory = Files.createTempDirectory("yamibo-update-test-").toFile()
        try {
            val target = directory.resolve("update.msi").apply { writeText("original") }
            val bytes = "verified package".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val asset = AppUpdateAsset("msi", "unused", hash, bytes.size.toLong())
            for (invalid in listOf(asset.copy(sha256 = "0".repeat(64)), asset.copy(size = 1), asset.copy(size = 999))) {
                assertFailsWith<IllegalArgumentException> { saveVerifiedDesktopUpdate(bytes.inputStream(), target, invalid) { _, _ -> } }
                assertEquals("original", target.readText())
                assertEquals(listOf("update.msi"), directory.listFiles()!!.map { it.name })
            }
            saveVerifiedDesktopUpdate(bytes.inputStream(), target, asset) { _, _ -> }
            assertEquals("verified package", target.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationKeepsOriginalAndRemovesPartialDownload() = runBlocking {
        val directory = Files.createTempDirectory("yamibo-update-cancel-").toFile()
        try {
            val target = directory.resolve("update.msi").apply { writeText("original") }
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                saveVerifiedDesktopUpdate(ByteArray(100).inputStream(), target, AppUpdateAsset("msi", "unused", "0".repeat(64), 100)) { _, _ -> cancel() }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals("original", target.readText())
            assertEquals(listOf("update.msi"), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
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
        val type = platform.supportedAssetTypes.firstOrNull() ?: return
        val foreign = if (platform.platformKey == "windows") "linux" else "windows"
        assertFalse(platform.supportsAsset(type, foreign, null))
        assertTrue(platform.supportsAsset(type, platform.platformKey, null))
        assertTrue(platform.supportsAsset(type, null, null))
        assertFalse(platform.supportsAsset("apk", platform.platformKey, null))
    }
}
