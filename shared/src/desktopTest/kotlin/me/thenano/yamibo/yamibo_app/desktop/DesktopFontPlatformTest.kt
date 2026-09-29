package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.font.DesktopFontPlatform
import me.thenano.yamibo.yamibo_app.repository.font.FontImportResult
import me.thenano.yamibo.yamibo_app.repository.font.LoadedFont
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import java.nio.file.Path

class DesktopFontPlatformTest {
    @Test
    fun nativeFontImportSurvivesReopenAndDeletesOnlyItsCopy() = runBlocking {
        val fontPath = System.getProperty("yamibo.test.font")
        assumeTrue("Supply an existing local .ttf/.otf file for native font acceptance", fontPath != null)
        val source = Path.of(requireNotNull(fontPath))
        val original = Files.readAllBytes(source)
        val root = Files.createTempDirectory("yamibo-font-native-")
        var imported: Path? = null
        try {
            val result = assertIs<FontImportResult.Success>(
                DesktopFontPlatform(root).importFont(source.toUri().toString(), source.fileName.toString(), "native"),
            )
            imported = Path.of(result.platformPath)
            assertEquals(root.toRealPath(), imported.parent)
            assertTrue(original.contentEquals(Files.readAllBytes(imported)))
            Files.newInputStream(imported).use {
                assertTrue(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, it).family.isNotBlank())
            }
            val reopened = DesktopFontPlatform(root)
            assertIs<FontImportResult.Failure>(reopened.importFont(source.toString(), source.fileName.toString(), "native"))
            assertTrue(reopened.deleteFont(LoadedFont("native", result.name, result.fileName, result.platformPath, 0)))
            assertFalse(Files.exists(imported))
            assertTrue(original.contentEquals(Files.readAllBytes(source)))
        } finally {
            imported?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun invalidFontLeavesNoPartialFileAndCannotDeleteOutsideFontDirectory() = runBlocking {
        val root = Files.createTempDirectory("yamibo-font-test-")
        val fonts = root.resolve("fonts")
        val source = Files.write(root.resolve("非字型.ttf"), byteArrayOf(1, 2, 3))
        try {
            val platform = DesktopFontPlatform(fonts)
            assertIs<FontImportResult.Failure>(platform.importFont(source.toUri().toString(), null, "one"))
            assertIs<FontImportResult.Failure>(platform.importFont(source.toString(), null, "../escape"))
            Files.list(fonts).use { assertEquals(0L, it.count()) }
            assertFalse(platform.deleteFont(LoadedFont("one", "foreign", "foreign.ttf", source.toString(), 0)))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(fonts)
            Files.deleteIfExists(root)
        }
    }
}
