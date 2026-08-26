package me.thenano.yamibo.yamibo_app.notification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class IOSMessageNotificationContractTest {
    @Test
    fun plistAndSchedulerUseDedicatedRefreshRegistration() {
        val plist = projectFile("iosApp/iosApp/Info.plist").readText(Charsets.UTF_8)
        val kotlin = iosSource("notification/IOSMessageNotificationBackground.kt")
        val swift = projectFile("iosApp/iosApp/iOSApp.swift").readText(Charsets.UTF_8)

        assertTrue(plist.contains("me.thenano.yamibo.yamibo-app.message-notification"))
        assertTrue(plist.contains("<string>fetch</string>"))
        assertTrue(kotlin.contains("BGAppRefreshTaskRequest"))
        assertTrue(kotlin.contains("interval.duration.inWholeSeconds"))
        assertTrue(kotlin.contains("cancelTaskRequestWithIdentifier"))
        assertTrue(swift.contains("BGAppRefreshTask"))
        assertTrue(swift.contains("guard !completed else { return }"))
        assertTrue(swift.contains("expirationHandler"))
    }

    @Test
    fun localNotificationAndExportedActionBridgesAreWired() {
        val kotlin = iosSource("notification/IOSMessageNotificationBackground.kt")
        val swift = projectFile("iosApp/iosApp/iOSApp.swift").readText(Charsets.UTF_8)
        val permission = iosSource("profile/settings/access/BackgroundAccessPlatformSupport.ios.kt")

        assertTrue(kotlin.contains("setBody(\"您有新的通知，快來看看吧 !\")"))
        assertTrue(kotlin.contains("UNAuthorizationStatusAuthorized"))
        assertTrue(kotlin.contains("fun openMessageCenterFromIOSNotification()"))
        assertTrue(kotlin.contains("fun muteMessageNotificationsToday"))
        assertTrue(swift.contains("查看通知"))
        assertTrue(swift.contains("不再提醒（僅限今日）"))
        assertTrue(swift.contains("openMessageCenterFromIOSNotification"))
        assertTrue(swift.contains("muteMessageNotificationsToday"))
        assertTrue(permission.contains("UNAuthorizationStatusNotDetermined"))
        assertTrue(permission.contains("requestAuthorizationWithOptions"))
    }

    private fun iosSource(relativePath: String): String = projectFile(
        "composeApp/src/iosMain/kotlin/me/thenano/yamibo/yamibo_app/$relativePath",
    ).readText(Charsets.UTF_8)

    private fun projectFile(relativePath: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "composeApp/src/iosMain").isDirectory }
        return File(root, relativePath)
    }
}
