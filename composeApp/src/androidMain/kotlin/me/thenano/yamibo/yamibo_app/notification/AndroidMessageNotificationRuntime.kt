package me.thenano.yamibo.yamibo_app.notification

import android.content.Context
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.network.AndroidYamiboClientProvider
import me.thenano.yamibo.yamibo_app.notification.bridge.AndroidNotificationBridge
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationDeliveryStateStore
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKey

/** The only Android entry point for polling and bridge-triggered local notification delivery. */
internal object AndroidMessageNotificationRuntime {
    // Checker instances have their own lock. This also serializes separate workers, SSE and FCM.
    private val mutex = Mutex()
    private val postingFence = AndroidMessageNotificationPostingFence()

    suspend fun check(
        context: Context,
        expectedUserId: Int? = null,
        eventId: String? = null,
        realtime: Boolean = eventId != null,
    ): MessageNotificationChecker.Result = mutex.withLock {
        val appContext = context.applicationContext
        val rawSettings = AndroidSettingsStore(appContext)
        val settings = AppSettingsRepository(rawSettings)
        if (!settings.messageNotificationEnabled.getValue()) {
            return@withLock MessageNotificationChecker.Result.Disabled
        }
        val cookieStore = AndroidCookieStore(appContext)
        val userStore = AndroidUserStore(appContext)
        val userId = userStore.load()?.uid?.value?.takeIf { it > 0 }
            ?: return@withLock MessageNotificationChecker.Result.MissingAccount
        if (expectedUserId != null && userId != expectedUserId) {
            return@withLock MessageNotificationChecker.Result.MissingAccount
        }
        val accountCookie = cookieStore.load().orEmpty()
        fun accountStillMatches(): Boolean =
            userStore.load()?.uid?.value == userId && cookieStore.load().orEmpty() == accountCookie

        val policy = AndroidMessageNotificationDeliveryPolicy(rawSettings)
        val siteId = AndroidMessageNotificationDeliveryPolicy.SITE_ID
        if (eventId != null && policy.hasProcessed(siteId, userId, eventId)) {
            return@withLock MessageNotificationChecker.Result.NoNewMessage
        }
        val deliveryState = MessageNotificationDeliveryStateStore(rawSettings)
        var coalesced = false
        val gateway = AndroidMessageNotificationGateway(appContext, userId, postingFence) {
            val allowed = accountStillMatches() && settings.messageNotificationEnabled.loadFromStore() &&
                !deliveryState.stateFor(userId, currentLocalDateKey()).muted
            if (allowed && policy.isCoolingDown(siteId, userId)) {
                coalesced = true
                false
            } else {
                allowed
            }
        }
        val fetchHomePage: suspend () -> YamiboResult<HomePage> = {
            if (!accountStillMatches()) {
                YamiboResult.NotLoggedIn
            } else {
                val client = AndroidYamiboClientProvider.get(appContext)
                client.setCookie(accountCookie)
                val result = client.fetchHomePage()
                // Never post for account A after the user switched or logged out during IO.
                if (accountStillMatches()) result else YamiboResult.NotLoggedIn
            }
        }
        val guardedGateway = object : MessageNotificationGateway {
            override suspend fun showMessageNotification(): Boolean {
                if (!accountStillMatches() || !settings.messageNotificationEnabled.loadFromStore()) return false
                if (policy.isCoolingDown(siteId, userId)) {
                    coalesced = true
                    return false
                }
                return gateway.showMessageNotification()
            }

            override suspend fun dismissMessageNotification() = gateway.dismissMessageNotification()
        }
        val checked = if (realtime) {
            // The user's daily limit is for periodic polling only; realtime does not consume it.
            checkRealtimeMessageNotification(
                enabled = settings.messageNotificationEnabled.getValue(),
                userId = userId.takeIf { accountStillMatches() },
                fetchHomePage = fetchHomePage,
                isMutedToday = { deliveryState.stateFor(userId, currentLocalDateKey()).muted },
                notificationGateway = guardedGateway,
            )
        } else {
            MessageNotificationChecker(
                settings = settings,
                deliveryStateStore = deliveryState,
                currentUserId = { userId.takeIf { accountStillMatches() } },
                fetchHomePage = fetchHomePage,
                notificationGateway = guardedGateway,
            ).check()
        }
        if (!accountStillMatches()) return@withLock MessageNotificationChecker.Result.MissingAccount
        val result = if (coalesced) MessageNotificationChecker.Result.NoNewMessage else checked
        if (result == MessageNotificationChecker.Result.Delivered) {
            postingFence.update { policy.recordDeliveryOrOpen(siteId, userId) }
        }
        // Failed fetches (including nonretryable auth/WAF responses) and failed posting are not ACKs.
        if (eventId != null && result.isTerminalMessageNotificationCheck()) {
            policy.markProcessed(siteId, userId, eventId)
        }
        result
    }

    suspend fun muteToday(context: Context, expectedUserId: Int? = null): Boolean {
        val appContext = context.applicationContext
        // Do not queue a user's mute behind a potentially 60-second unread fetch. This only
        // writes the independent muted-date field; the final posting gate uses the same fence.
        val muted = postingFence.update {
            val userId = AndroidUserStore(appContext).load()?.uid?.value?.takeIf { it > 0 }
            if (expectedUserId != null && !messageNotificationMuteMatchesAccount(expectedUserId, userId)) {
                return@update false
            }
            if (userId != null) {
                MessageNotificationDeliveryStateStore(AndroidSettingsStore(appContext))
                    .muteToday(userId, currentLocalDateKey())
            }
            dismissActiveMessageNotification(appContext)
            userId != null
        }
        if (muted) AndroidNotificationBridge.onSettingsChanged(appContext)
        return muted
    }

    suspend fun resetReplay(context: Context, expectedUserId: Int): Boolean = mutex.withLock {
        val appContext = context.applicationContext
        if (expectedUserId <= 0 || AndroidUserStore(appContext).load()?.uid?.value != expectedUserId) {
            return@withLock false
        }
        AndroidMessageNotificationDeliveryPolicy(AndroidSettingsStore(appContext))
            .clearProcessed(AndroidMessageNotificationDeliveryPolicy.SITE_ID, expectedUserId)
        true
    }

    suspend fun onOpened(context: Context) = postingFence.update {
        val appContext = context.applicationContext
        AndroidUserStore(appContext).load()?.uid?.value?.takeIf { it > 0 }?.let { userId ->
            AndroidMessageNotificationDeliveryPolicy(AndroidSettingsStore(appContext))
                .recordDeliveryOrOpen(AndroidMessageNotificationDeliveryPolicy.SITE_ID, userId)
        }
        dismissActiveMessageNotification(appContext)
    }
}
