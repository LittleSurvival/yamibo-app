package me.thenano.yamibo.yamibo_app.profile.settings.backup

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme

class BackupSettingsUiContractTest {
    @Test
    fun expandedScopeAndRestoreSummaryNameEveryUserOwnedArea() {
        val source = backupScreenSource()
        assertTrue(source.contains("""i18n("本地資料備份")"""))
        assertTrue(source.contains("備份範圍包含收藏、設定、筆記、書籤、歷史進度與更新紀錄。"))
        assertTrue(source.contains("還原完成：收藏 {}，設定 {}，歷史進度 {}，更新紀錄 {}"))
        assertTrue(source.contains("summary.favorites"))
        assertTrue(source.contains("summary.settings"))
        assertTrue(source.contains("summary.readingHistory"))
        assertTrue(source.contains("summary.updateRecords"))
        assertTrue(source.contains("略過無法對應的輔助紀錄 {}"))
        assertTrue(source.contains("summary.skippedRecords"))
    }

    @Test
    fun screenUsesYamiboTokensAndTextRemainsReadableInLightAndDarkSchemes() {
        val source = backupScreenSource()
        assertTrue(source.contains("val colors = YamiboTheme.colors"))
        assertTrue(source.contains("color = colors.textDark"))
        assertTrue(source.contains("color = colors.textStrong"))
        assertFalse(source.contains("Color.Black"))
        assertFalse(Regex("""Color\(0x[0-9A-Fa-f]+""").containsMatchIn(source))

        listOf(YamiboColorScheme.Default, YamiboColorScheme.DefaultDark).forEach { scheme ->
            assertTrue(
                contrastRatio(scheme.textDark, scheme.creamBackground) >= 4.5,
                "${scheme.name} text/background contrast is below 4.5",
            )
            assertTrue(
                contrastRatio(scheme.textDark, scheme.creamSurface) >= 4.5,
                "${scheme.name} text/surface contrast is below 4.5",
            )
        }
    }

    @Test
    fun summaryWithoutSkippedRecordsDoesNotRenderAWarningSuffix() {
        val source = backupScreenSource()

        assertTrue(source.contains("if (summary.skippedRecords > 0)"))
        assertTrue(Regex("""}\s*else\s*\{\s*base\s*}""").containsMatchIn(source))
    }

    private fun backupScreenSource(): String = repoRoot()
        .resolve(
            "composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/" +
                "profile/settings/backup/BackupSettingsScreen.kt",
        )
        .readText(Charsets.UTF_8)

    private fun repoRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private fun contrastRatio(foreground: Long, background: Long): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
