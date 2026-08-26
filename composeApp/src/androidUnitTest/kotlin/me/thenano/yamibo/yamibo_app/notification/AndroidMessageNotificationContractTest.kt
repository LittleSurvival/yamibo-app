package me.thenano.yamibo.yamibo_app.notification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker

class AndroidMessageNotificationContractTest {
    @Test
    fun workerRetriesOnlyBoundedRetryableFailures() {
        assertTrue(
            shouldRetryMessageCheck(
                MessageNotificationChecker.Result.FetchFailed(retryable = true),
                runAttemptCount = 0,
            ),
        )
        assertFalse(
            shouldRetryMessageCheck(
                MessageNotificationChecker.Result.FetchFailed(retryable = false),
                runAttemptCount = 0,
            ),
        )
        assertFalse(
            shouldRetryMessageCheck(
                MessageNotificationChecker.Result.FetchFailed(retryable = true),
                runAttemptCount = MessageNotificationWorker.MAX_RETRIES,
            ),
        )
    }

    @Test
    fun schedulerUsesOnePeriodicWorkWithRequiredConstraints() {
        val source = androidSource("notification/AndroidMessageNotificationScheduler.kt")

        assertTrue(source.contains("UNIQUE_PERIODIC_WORK = \"message-notification-check-periodic\""))
        assertTrue(source.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertTrue(source.contains("NetworkType.CONNECTED"))
        assertTrue(source.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(source.contains("cancelUniqueWork(UNIQUE_PERIODIC_WORK)"))
    }

    @Test
    fun notificationHasFixedBodyBothActionsAndDedicatedMetadata() {
        val source = androidSource("notification/AndroidMessageNotificationGateway.kt")

        assertTrue(source.contains(".setContentText(\"您有新的通知，快來看看吧 !\")"))
        assertTrue(source.contains(".addAction(0, \"查看通知\""))
        assertTrue(source.contains(".addAction(0, \"不再提醒（僅限今日）\""))
        assertTrue(source.contains("message_notification_channel"))
        assertEquals(1, Regex("const val NOTIFICATION_ID = 228150").findAll(source).count())
    }

    @Test
    fun receiverAndWarmColdRoutingAreRegistered() {
        val manifest = projectFile("composeApp/src/androidMain/AndroidManifest.xml").readText(Charsets.UTF_8)
        val activity = androidSource("MainActivity.kt")
        val receiver = androidSource("notification/MessageNotificationActionReceiver.kt")

        assertTrue(manifest.contains(".notification.MessageNotificationActionReceiver"))
        assertTrue(activity.contains("EXTRA_FROM_MESSAGE_NOTIFICATION"))
        assertTrue(activity.contains("requestOpenMessageCenterFromNotification()"))
        assertTrue(receiver.contains("createChecker(context).muteToday()"))
    }

    private fun androidSource(relativePath: String): String = projectFile(
        "composeApp/src/androidMain/kotlin/me/thenano/yamibo/yamibo_app/$relativePath",
    ).readText(Charsets.UTF_8)

    private fun projectFile(relativePath: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "composeApp/src/androidMain").isDirectory }
        return File(root, relativePath)
    }
}
