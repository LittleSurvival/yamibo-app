package me.thenano.yamibo.yamibo_app.profile.settings.access

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MessageNotificationSettingsUiContractTest {
    @Test
    fun backgroundAccessScreenReusesSettingsControlsAndShowsBothSelectorsFirst() {
        val source = source("profile/settings/access/BackgroundAccessSetupScreen.kt")

        assertTrue(source.contains("SettingsToggleRow("))
        assertTrue(source.contains("SettingsChipRow("))
        assertTrue(source.contains("MessageNotificationIntervals.map"))
        assertTrue(source.contains("MessageNotificationDailyLimit.entries.map"))
        assertTrue(source.count { it == '\n' } > 0)
        assertTrue(source.countOccurrences("enabled = messageNotificationsEnabled") == 2)
        assertTrue(source.indexOf("新消息通知") < source.indexOf("系統存取狀態"))
    }

    private fun source(relativePath: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "composeApp/src/commonMain/kotlin").isDirectory }
        return File(
            root,
            "composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/$relativePath",
        ).readText(Charsets.UTF_8)
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
