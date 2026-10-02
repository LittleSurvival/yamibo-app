package me.thenano.yamibo.yamibo_app.notification.bridge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.WorkManager
import io.github.littlesurvival.YamiboRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.thenano.yamibo.yamibo_app.BuildConfig
import me.thenano.yamibo.yamibo_app.notification.AndroidMessageNotificationGateway
import me.thenano.yamibo.yamibo_app.notification.AndroidMessageNotificationRuntime
import me.thenano.yamibo.yamibo_app.notification.dismissActiveMessageNotification
import me.thenano.yamibo.yamibo_app.notification.requestOpenMessageCenterFromNotification
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationDeliveryStateStore
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKey

class YamiboNotificationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidNotificationBridge.initialize(this)
    }
}

/** One process-scoped owner; background SSE is intentionally unsupported. */
internal object AndroidNotificationBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Mutex()
    private val mutableStatus = MutableStateFlow("尚未設定通知服務網址；保留定期檢查")
    val status: StateFlow<String> = mutableStatus
    private var context: Context? = null
    private var client: NotificationBridgeClient? = null
    private var fcm: AndroidFcmCapability? = null
    @Volatile private var foreground = false
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var loop: Job? = null
    private var activeSession: NotificationSession? = null
    private var activeCookie: String? = null
    private var cursor: String? = null
    private var cursorUid: Int? = null
    private var bindBlocked = false

    @Synchronized
    fun initialize(value: Context) {
        if (context != null) return
        val app = value.applicationContext
        context = app
        AndroidMessageNotificationGateway.ensureChannel(app)
        val origin = trustedNotificationOrigin(BuildConfig.NOTIFICATION_ORIGIN, YamiboRoute.Domain.build())
        if (origin == null) {
            mutableStatus.value = if (BuildConfig.NOTIFICATION_ORIGIN.isBlank()) {
                "尚未設定通知服務網址；保留定期檢查"
            } else {
                "通知網址必須是論壇的同源 HTTPS 網址；保留定期檢查"
            }
            return
        }
        client = NotificationBridgeClient(origin)
        fcm = AndroidFcmCapability(app).also {
            runCatching { it.initialize() }
        }
    }

    fun onForeground(value: Context, started: Boolean) {
        initialize(value)
        // Capture latest lifecycle state synchronously. IO launches may acquire the lock out of order.
        foreground = started
        scope.launch {
            control.withLock {
                updateNetworkObserver(value, foreground)
                restartLocked()
            }
        }
    }

    fun onSettingsChanged(value: Context) {
        initialize(value)
        scope.launch {
            if (!settings(value).messageNotificationEnabled.getValue() || isMuted(value)) {
                AndroidMessageNotificationRuntime.onOpened(value)
            }
            control.withLock {
                if (!settings(value).messageNotificationEnabled.getValue() || isMuted(value)) {
                    loop?.cancelAndJoin()
                    loop = null
                    WorkManager.getInstance(value.applicationContext).cancelAllWorkByTag("notification-bridge-signal")
                    revokeLocked()
                    dismissActiveMessageNotification(value)
                }
                restartLocked()
            }
        }
    }

    fun onAuthenticationChanged(value: Context) {
        initialize(value)
        scope.launch {
            control.withLock {
                val changed = activeSession?.uid != currentUid(value) || activeCookie != cookie(value)
                if (changed) {
                    loop?.cancelAndJoin()
                    loop = null
                    dismissActiveMessageNotification(value)
                    revokeLocked()
                    cursor = null
                    cursorUid = null
                }
                if (changed || loop == null) restartLocked()
            }
        }
    }

    /** Called before native credentials are cleared, including expiry-driven repository logout. */
    suspend fun beforeLogout(value: Context) {
        initialize(value)
        AndroidMessageNotificationRuntime.onOpened(value)
        control.withLock {
            WorkManager.getInstance(value.applicationContext).cancelAllWorkByTag("notification-bridge-signal")
            loop?.cancelAndJoin()
            loop = null
            revokeLocked()
            cursor = null
            cursorUid = null
            mutableStatus.value = "尚未登入；通知橋接已停止"
            dismissActiveMessageNotification(value)
        }
    }

    fun onLocalNotificationOpened(value: Context) {
        scope.launch { AndroidMessageNotificationRuntime.onOpened(value) }
    }

    fun onNewToken(value: Context) {
        initialize(value)
        scope.launch {
            control.withLock {
                fcm?.tokenChanged()
                bindBlocked = false
                restartLocked()
            }
        }
    }

    fun onPush(value: Context, event: String?, raw: String?) {
        if (event == null || raw == null) return
        val signal = parseNotificationSignal(event, raw) ?: return
        initialize(value)
        if (client == null) return
        NotificationSignalWorker.enqueue(value, signal.eventId)
    }

    /** Mixed FCM background notifications arrive as Activity extras, without onMessageReceived. */
    fun onNotificationIntent(value: Context, intent: Intent?) {
        val event = intent?.getStringExtra("event") ?: return
        val raw = intent.getStringExtra("signal") ?: return
        intent.removeExtra("event")
        intent.removeExtra("signal")
        if (parseNotificationSignal(event, raw) == null) return
        initialize(value)
        scope.launch {
            val api = client ?: return@launch
            val uid = currentUid(value) ?: return@launch
            val cookies = cookie(value) ?: return@launch
            val verified = try { api.exchange(cookies, uid) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { return@launch }
            try {
                if (currentUid(value) == verified.uid && cookie(value) == cookies) {
                    AndroidMessageNotificationRuntime.onOpened(value)
                    requestOpenMessageCenterFromNotification()
                }
            } finally {
                try { api.revoke(verified) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
        }
    }

    private fun updateNetworkObserver(app: Context, started: Boolean) {
        if (!started) {
            networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
            networkCallback = null
            return
        }
        if (networkCallback != null || client == null) return
        connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var network: Network? = connectivity?.activeNetwork
            override fun onAvailable(value: Network) {
                if (network == value) return
                network = value
                reconnect()
            }
            override fun onLost(value: Network) {
                if (network != value) return
                network = null
                reconnect()
            }
            private fun reconnect() {
                scope.launch {
                    control.withLock {
                        fcm?.networkChanged()
                        if (foreground) restartLocked()
                    }
                }
            }
        }
        networkCallback = callback
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onFailure { networkCallback = null }
    }

    private suspend fun restartLocked() {
        loop?.cancelAndJoin()
        loop = null
        val app = context ?: return
        if (client == null) return
        if (!foreground) {
            mutableStatus.value = "背景保留定期檢查；FCM 資格最長五分鐘，SSE 已暫停"
            return
        }
        loop = scope.launch { runLoop(app) }
    }

    private suspend fun runLoop(app: Context) {
        val api = client ?: return
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (!settings(app).messageNotificationEnabled.getValue()) {
                mutableStatus.value = "新消息通知已關閉"
                return
            }
            val uid = currentUid(app)
            val cookies = cookie(app)
            if (uid == null || cookies.isNullOrBlank()) {
                mutableStatus.value = "尚未登入；通知橋接已停止"
                return
            }
            if (cursorUid != uid) { cursor = null; cursorUid = uid }
            try {
                val session = api.exchange(cookies, uid)
                if (currentUid(app) != uid || cookie(app) != cookies) return
                val previous = activeSession
                activeSession = session
                activeCookie = cookies
                val proofStore = NotificationInstallationStore(app)
                val proof = try { proofStore.load() } catch (_: Exception) {
                    bindBlocked = true
                    mutableStatus.value = "裝置綁定憑證無法讀取；需恢復綁定，暫用 SSE"
                    null
                }
                val capability = if (isMuted(app)) FcmCapability(reason = "今日已靜音；使用 SSE")
                    else if (bindBlocked) FcmCapability(reason = "裝置綁定需恢復；使用 SSE")
                    else requireNotNull(fcm).check()
                var usingFcm = false
                var reason = capability.reason
                if (capability.token != null) {
                    try {
                        proofStore.save(api.bind(session, capability.token, proof))
                        usingFcm = true
                    } catch (error: NotificationApiException) {
                        if (error.status in setOf(401, 403)) throw error
                        if (error.status == 409) bindBlocked = true
                        reason = when (error.status) {
                            409 -> "裝置綁定衝突；保留憑證並使用 SSE"
                            422 -> "伺服器未啟用 FCM；使用 SSE"
                            else -> "FCM 綁定暫時失敗；使用 SSE"
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        reason = "FCM 綁定或憑證儲存失敗；使用 SSE"
                    }
                }
                if (!usingFcm && proof != null) {
                    try { api.unbind(session, proof) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Existing eligibility still expires at its original deadline. */ }
                }
                // Only after rebind: revoking a session disables devices last bound through that session.
                if (previous != null) {
                    try { api.revoke(previous) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                }
                if (usingFcm) {
                    attempt = 0
                }
                if (usingFcm) {
                    mutableStatus.value = "FCM 已綁定；前景自動續期，背景資格最長五分鐘"
                    delay(renewalDelayMillis(session.expiresAtMillis, System.currentTimeMillis()))
                } else {
                    mutableStatus.value = reason
                    withTimeoutOrNull(renewalDelayMillis(session.expiresAtMillis, System.currentTimeMillis())) {
                        api.stream(session, cursor, onConnected = {
                            requireHandled(AndroidMessageNotificationRuntime.check(app, uid, realtime = true))
                        }) { frame ->
                            if (currentUid(app) != uid || cookie(app) != cookies) throw CancellationException("Account changed")
                            if (frame.type == "resync") {
                                cursor = null
                                AndroidMessageNotificationRuntime.resetReplay(app, uid)
                                requireHandled(AndroidMessageNotificationRuntime.check(app, uid, realtime = true))
                            } else {
                                val signal = parseNotificationSignal(frame.type, frame.data) ?: return@stream
                                val result = AndroidMessageNotificationRuntime.check(app, uid, signal.eventId)
                                requireHandled(result)
                                frame.id?.takeIf(cursorPattern::matches)?.let { cursor = it }
                            }
                        }
                    }
                    // EOF is a disconnect too: avoid a hot reconnect loop from a failing proxy.
                    delay(reconnectDelayMillis(attempt++))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: NotificationApiException) {
                if (error.status in setOf(401, 403)) {
                    mutableStatus.value = "通知登入驗證失敗；請重新登入或重回前景"
                    return
                }
                mutableStatus.value = "通知服務暫時不可用；保留定期檢查並稍後重連"
                delay(reconnectDelayMillis(attempt++))
            } catch (_: Exception) {
                mutableStatus.value = "通知連線中斷；保留定期檢查並稍後重連"
                delay(reconnectDelayMillis(attempt++))
            }
        }
    }

    private fun requireHandled(result: MessageNotificationChecker.Result) {
        if (result == MessageNotificationChecker.Result.MissingAccount) throw CancellationException("Account changed")
        if (result is MessageNotificationChecker.Result.FetchFailed || result == MessageNotificationChecker.Result.DeliveryUnavailable) {
            throw IllegalStateException("Notification refresh not processed")
        }
    }

    private suspend fun revokeLocked() {
        val previous = activeSession
        activeSession = null
        activeCookie = null
        val api = client ?: return
        val app = context ?: return
        withTimeoutOrNull(5_000) {
            // A cold-process mute/OFF/logout still has proof + native cookies, but no memory bearer.
            val session = previous ?: try {
                val uid = currentUid(app) ?: return@withTimeoutOrNull
                val cookies = cookie(app)?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull
                api.exchange(cookies, uid)
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { return@withTimeoutOrNull }
            try {
                NotificationInstallationStore(app).load()?.let { api.unbind(session, it) }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            try { api.revoke(session) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        }
    }

    private fun settings(app: Context) = AppSettingsRepository(AndroidSettingsStore(app))
    private fun currentUid(app: Context) = AndroidUserStore(app).load()?.uid?.value
    private fun cookie(app: Context) = AndroidCookieStore(app).load()
    private fun isMuted(app: Context): Boolean {
        val uid = currentUid(app) ?: return true
        return MessageNotificationDeliveryStateStore(AndroidSettingsStore(app)).stateFor(uid, currentLocalDateKey()).muted
    }
}
