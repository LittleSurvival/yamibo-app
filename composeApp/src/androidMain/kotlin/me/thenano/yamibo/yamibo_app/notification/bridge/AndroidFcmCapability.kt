package me.thenano.yamibo.yamibo_app.notification.bridge

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.thenano.yamibo.yamibo_app.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class FcmCapability(val token: String? = null, val reason: String)

internal class AndroidFcmCapability(private val context: Context) {
    private var nextProbeAt = 0L
    private var lastProbeAt = -60_000L
    private var cached = FcmCapability(reason = "尚未檢查 FCM")

    fun initialize(): Boolean {
        if (context.packageName != BuildConfig.FCM_PACKAGE) return false
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .build())
        }
        return true
    }

    fun tokenChanged() { nextProbeAt = 0L }

    fun networkChanged() {
        // Retry on a changed route, but rapid connectivity flaps cannot create token request storms.
        nextProbeAt = (lastProbeAt + 60_000L).coerceAtMost(nextProbeAt)
        cached = FcmCapability(reason = "網路已變更，等待重新檢查 FCM；暫用 SSE")
    }

    @Suppress("DEPRECATION") // Server HTTP v1 uses message.token, not the newer FID target contract.
    suspend fun check(): FcmCapability {
        if (context.packageName != BuildConfig.FCM_PACKAGE) return FcmCapability(reason = "此安裝版本尚未配置 Firebase（debug/run）；使用 SSE")
        if (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            return FcmCapability(reason = "Google Play 服務不可用；使用 SSE")
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextProbeAt) return cached
        lastProbeAt = now
        nextProbeAt = now + 10 * 60_000L
        cached = try {
            initialize()
            val token = withTimeoutOrNull(8_000) { FirebaseMessaging.getInstance().token.awaitValue() }
            when {
                token.isNullOrBlank() -> FcmCapability(reason = "FCM 註冊逾時；使用 SSE")
                !withContext(Dispatchers.IO) { canReachFcm() } -> FcmCapability(reason = "FCM 連線不可用；使用 SSE")
                else -> FcmCapability(token, "FCM 可用，等待伺服器綁定")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            FcmCapability(reason = "FCM 初始化或註冊失敗；使用 SSE")
        }
        return cached
    }

    /** A cached token alone says nothing about the current network. Probe the actual FCM TLS host. */
    private fun canReachFcm(): Boolean = runCatching {
        Socket().use { raw ->
            raw.connect(InetSocketAddress("mtalk.google.com", 5228), 3_000)
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "mtalk.google.com", 5228, true) as SSLSocket).use { tls ->
                tls.soTimeout = 3_000
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
            }
        }
        true
    }.getOrDefault(false)
}

internal suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
        }
    }
}
