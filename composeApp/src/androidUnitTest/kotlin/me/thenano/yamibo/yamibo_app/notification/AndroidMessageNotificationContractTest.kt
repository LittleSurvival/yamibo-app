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
        assertTrue(source.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertTrue(source.contains(".setCategory(NotificationCompat.CATEGORY_MESSAGE)"))
        assertTrue(source.contains("putExtra(MessageNotificationActionReceiver.EXTRA_USER_ID, it)"))
        assertTrue(source.contains("fun ensureChannel(context: Context)"))
        assertTrue(source.contains("systemManager?.activeNotifications?.filter"))
        assertTrue(source.contains("it.notification.channelId == AndroidMessageNotificationGateway.CHANNEL_ID"))
        assertTrue(source.contains("manager.cancel(notification.tag, notification.id)"))
        assertTrue(source.contains("isLegacyFirebaseMessageNotification("))
        assertTrue(source.contains("postingFence.postIfAllowed(canPost)"))
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
        assertTrue(receiver.contains("AndroidMessageNotificationRuntime.muteToday(context, expectedUserId)"))
        assertTrue(receiver.contains("intent.getIntExtra(EXTRA_USER_ID"))
        assertTrue(receiver.contains("if (expectedUserId == null)"))
    }

    @Test
    fun pollingAndMuteUseTheSerializedRuntimeAndCancellationPropagates() {
        val runtime = androidSource("notification/AndroidMessageNotificationRuntime.kt")
        val worker = androidSource("notification/MessageNotificationWorker.kt")

        assertTrue(runtime.contains("private val mutex = Mutex()"))
        assertTrue(runtime.contains("MessageNotificationChecker.Result = mutex.withLock"))
        assertTrue(runtime.contains("val muted = postingFence.update"))
        assertTrue(runtime.contains("suspend fun onOpened(context: Context) = postingFence.update"))
        assertTrue(runtime.contains("postingFence.update { policy.recordDeliveryOrOpen(siteId, userId) }"))
        assertTrue(runtime.contains("!deliveryState.stateFor(userId, currentLocalDateKey()).muted"))
        assertTrue(runtime.contains("accountStillMatches()"))
        assertTrue(runtime.contains("client.fetchHomePage()"))
        assertTrue(runtime.contains("realtime: Boolean = eventId != null"))
        assertTrue(runtime.contains("val checked = if (realtime)"))
        assertTrue(runtime.contains("checkRealtimeMessageNotification("))
        assertTrue(runtime.contains("expectedUserId != null && userId != expectedUserId"))
        assertTrue(runtime.contains("messageNotificationMuteMatchesAccount(expectedUserId, userId)"))
        assertFalse(runtime.contains("fetchNotice"))
        assertFalse(runtime.contains("fetchPrivateMessage"))
        assertTrue(worker.contains("AndroidMessageNotificationRuntime.check(applicationContext)"))
        assertTrue(worker.contains("catch (cancelled: CancellationException)"))
        assertTrue(worker.contains("throw cancelled"))
    }

    @Test
    fun failedAndUnverifiedChecksDoNotAcknowledgeEvents() {
        assertFalse(MessageNotificationChecker.Result.FetchFailed(true).isTerminalMessageNotificationCheck())
        assertFalse(MessageNotificationChecker.Result.FetchFailed(false).isTerminalMessageNotificationCheck())
        assertFalse(MessageNotificationChecker.Result.DeliveryUnavailable.isTerminalMessageNotificationCheck())
        assertFalse(MessageNotificationChecker.Result.MissingAccount.isTerminalMessageNotificationCheck())
        assertTrue(MessageNotificationChecker.Result.Delivered.isTerminalMessageNotificationCheck())
        assertTrue(MessageNotificationChecker.Result.NoNewMessage.isTerminalMessageNotificationCheck())
        assertTrue(MessageNotificationChecker.Result.MutedToday.isTerminalMessageNotificationCheck())
        assertTrue(MessageNotificationChecker.Result.DailyLimitReached.isTerminalMessageNotificationCheck())
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
