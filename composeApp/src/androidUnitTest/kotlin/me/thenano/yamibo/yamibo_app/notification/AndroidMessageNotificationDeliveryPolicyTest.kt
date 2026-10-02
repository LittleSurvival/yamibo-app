package me.thenano.yamibo.yamibo_app.notification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AndroidMessageNotificationDeliveryPolicyTest {
    @Test
    fun duplicateEventsSurvivePolicyRecreationAndAreScopedBySiteAndAccount() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store)
        assertFalse(policy.hasProcessed("yamibo", 11, "same-event"))
        policy.markProcessed("yamibo", 11, "same-event")

        val reloaded = AndroidMessageNotificationDeliveryPolicy(store)
        assertTrue(reloaded.hasProcessed("yamibo", 11, "same-event"))
        assertFalse(reloaded.hasProcessed("yamibo", 12, "same-event"))
        assertFalse(reloaded.hasProcessed("another-site", 11, "same-event"))
        assertFalse(reloaded.hasProcessed("yamibo", 11, "another-event"))
        assertFalse(store.values.values.any { it.contains("same-event") })
    }

    @Test
    fun eventLedgerIsBoundedAcrossAllAccountsAndKeepsTheNewestEvents() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store)
        repeat(AndroidMessageNotificationDeliveryPolicy.MAX_PROCESSED_EVENTS + 20) { event ->
            policy.markProcessed("yamibo", event + 1, "event-$event")
        }

        assertFalse(policy.hasProcessed("yamibo", 1, "event-0"))
        assertTrue(policy.hasProcessed("yamibo", 148, "event-147"))
        assertEquals(AndroidMessageNotificationDeliveryPolicy.MAX_PROCESSED_EVENTS,
            store.values.values.single().lines().size)
    }

    @Test
    fun repeatedAcknowledgementDoesNotGrowLedgerOrLoseOtherEvents() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store)
        policy.markProcessed("yamibo", 11, "one")
        policy.markProcessed("yamibo", 11, "two")
        repeat(10) { policy.markProcessed("yamibo", 11, "one") }

        assertTrue(policy.hasProcessed("yamibo", 11, "one"))
        assertTrue(policy.hasProcessed("yamibo", 11, "two"))
        assertEquals(2, store.values.values.single().lines().size)
    }

    @Test
    fun unacknowledgedEventRemainsRetryableAfterRestart() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store)
        // Fetch/post failures deliberately never reach markProcessed.
        repeat(3) { assertFalse(policy.hasProcessed("yamibo", 11, "failed-delivery")) }
        assertFalse(AndroidMessageNotificationDeliveryPolicy(store)
            .hasProcessed("yamibo", 11, "failed-delivery"))
    }

    @Test
    fun deliveryOrOpeningCoalescesPollingAndSignalsForOneMinute() {
        val store = MemorySettings()
        var now = 1_000_000L
        val policy = AndroidMessageNotificationDeliveryPolicy(store) { now }
        assertFalse(policy.isCoolingDown("yamibo", 11))
        policy.recordDeliveryOrOpen("yamibo", 11)
        assertTrue(policy.isCoolingDown("yamibo", 11))
        assertFalse(policy.isCoolingDown("yamibo", 12))
        assertFalse(policy.isCoolingDown("another-site", 11))
        now += AndroidMessageNotificationDeliveryPolicy.COOLDOWN_MILLIS - 1
        assertTrue(AndroidMessageNotificationDeliveryPolicy(store) { now }
            .isCoolingDown("yamibo", 11))
        now += 1
        assertFalse(policy.isCoolingDown("yamibo", 11))
    }

    @Test
    fun reopeningExtendsCooldownButWallClockRollbackDoesNotSuppressIndefinitely() {
        val store = MemorySettings()
        var now = 1_000_000L
        val policy = AndroidMessageNotificationDeliveryPolicy(store) { now }
        policy.recordDeliveryOrOpen("yamibo", 11)
        now += 30_000L
        policy.recordDeliveryOrOpen("yamibo", 11)
        now += 30_001L
        assertTrue(policy.isCoolingDown("yamibo", 11))
        now = 500_000L
        assertFalse(policy.isCoolingDown("yamibo", 11))
    }

    @Test
    fun cooldownStorageRemainsBoundedWhenAccountsChange() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store) { 1_000_000L }
        repeat(100) { policy.recordDeliveryOrOpen("yamibo", it + 1) }

        assertFalse(policy.isCoolingDown("yamibo", 1))
        assertTrue(policy.isCoolingDown("yamibo", 100))
        assertEquals(16, store.values.values.single().lines().size)
    }

    @Test
    fun delimitersAndUnicodeCannotAliasEventScope() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store)
        policy.markProcessed("yamibo", 11, "event:12:\n提醒")
        assertTrue(policy.hasProcessed("yamibo", 11, "event:12:\n提醒"))
        assertFalse(policy.hasProcessed("yamibo:11", 12, "event:\n提醒"))
        assertEquals(129, store.values.values.single().length)
    }

    @Test
    fun resyncClearsOnlyTheVerifiedAccountAndPreservesCooldown() {
        val store = MemorySettings()
        val policy = AndroidMessageNotificationDeliveryPolicy(store) { 1_000_000L }
        policy.markProcessed("yamibo", 11, "event")
        policy.markProcessed("yamibo", 12, "event")
        policy.markProcessed("another-site", 11, "event")
        policy.recordDeliveryOrOpen("yamibo", 11)
        policy.clearProcessed("yamibo", 11)

        assertFalse(policy.hasProcessed("yamibo", 11, "event"))
        assertTrue(policy.hasProcessed("yamibo", 12, "event"))
        assertTrue(policy.hasProcessed("another-site", 11, "event"))
        assertTrue(policy.isCoolingDown("yamibo", 11))
    }

    @Test
    fun staleMuteActionsCannotMuteTheNewAccount() {
        assertTrue(messageNotificationMuteMatchesAccount(11, 11))
        assertFalse(messageNotificationMuteMatchesAccount(11, 12))
        assertFalse(messageNotificationMuteMatchesAccount(11, null))
        assertFalse(messageNotificationMuteMatchesAccount(null, 11))
        assertFalse(messageNotificationMuteMatchesAccount(0, 0))
        assertFalse(messageNotificationMuteMatchesAccount(-1, -1))
    }

    @Test
    fun legacyDismissalRecognizesOnlyFirebaseGeneratedTags() {
        assertTrue(isLegacyFirebaseMessageNotification("FCM-Notification:123456"))
        assertFalse(isLegacyFirebaseMessageNotification(null))
        assertFalse(isLegacyFirebaseMessageNotification("backup-notification"))
        assertFalse(isLegacyFirebaseMessageNotification("favorite-update"))
        assertFalse(isLegacyFirebaseMessageNotification("FCM-NotificationWithoutColon"))
    }

    @Test
    fun muteIsNotBlockedByPendingUnreadFetchAndStopsItsEventualPost() {
        val fence = AndroidMessageNotificationPostingFence()
        val fetchStarted = CountDownLatch(1)
        val finishFetch = CountDownLatch(1)
        var muted = false
        var posted = false
        val checking = CompletableFuture.supplyAsync {
            fetchStarted.countDown()
            check(finishFetch.await(5, TimeUnit.SECONDS))
            fence.postIfAllowed({ !muted }) { posted = true }
        }
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
        fence.update { muted = true }
        finishFetch.countDown()
        assertFalse(checking.get(5, TimeUnit.SECONDS))
        assertFalse(posted)
    }

    @Test
    fun muteAfterAPostDismissesUnderTheSameFenceAndPreventsLaterPosts() {
        val fence = AndroidMessageNotificationPostingFence()
        var muted = false
        var visible = false
        assertTrue(fence.postIfAllowed({ !muted }) { visible = true })
        fence.update {
            muted = true
            visible = false
        }
        assertFalse(fence.postIfAllowed({ !muted }) { visible = true })
        assertFalse(visible)
    }

    @Test
    fun legacyDismissalRecognizesServerUuidOnlyWithItsGenericPayload() {
        val tag = "12345678-1234-4123-8123-123456789abc"
        assertTrue(isLegacyFirebaseMessageNotification(tag, "Yamibo", "你有新的通知"))
        assertFalse(isLegacyFirebaseMessageNotification(tag, "backup", "你有新的通知"))
        assertFalse(isLegacyFirebaseMessageNotification(tag, "Yamibo", "unrelated reminder"))
        assertFalse(isLegacyFirebaseMessageNotification(tag))
        assertFalse(isLegacyFirebaseMessageNotification("12345678-1234-1123-8123-123456789abc",
            "Yamibo", "你有新的通知"))
    }

    @Test
    fun openingDuringPendingUnreadFetchImmediatelyPreventsRepost() {
        val fence = AndroidMessageNotificationPostingFence()
        val policy = AndroidMessageNotificationDeliveryPolicy(MemorySettings()) { 1_000_000L }
        val fetchStarted = CountDownLatch(1)
        val finishFetch = CountDownLatch(1)
        var visible = true
        val checking = CompletableFuture.supplyAsync {
            fetchStarted.countDown()
            check(finishFetch.await(5, TimeUnit.SECONDS))
            fence.postIfAllowed({ !policy.isCoolingDown("yamibo", 11) }) { visible = true }
        }
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
        fence.update {
            policy.recordDeliveryOrOpen("yamibo", 11)
            visible = false
        }
        finishFetch.countDown()
        assertFalse(checking.get(5, TimeUnit.SECONDS))
        assertFalse(visible)
    }

    private class MemorySettings : SettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getInt(key: String, defaultValue: Int) = values[key]?.toIntOrNull() ?: defaultValue
        override fun putInt(key: String, value: Int) { values[key] = value.toString() }
        override fun getFloat(key: String, defaultValue: Float) = values[key]?.toFloatOrNull() ?: defaultValue
        override fun putFloat(key: String, value: Float) { values[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean) =
            values[key]?.toBooleanStrictOrNull() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun remove(key: String) { values.remove(key) }
        override fun hasKey(key: String) = key in values
    }
}
