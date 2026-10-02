package me.thenano.yamibo.yamibo_app.notification.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationBridgeContractTest {
    @Test
    fun firebaseRemainsRegistrationTokenBasedAndServiceIsPrivate() {
        val manifest = file("composeApp/src/androidMain/AndroidManifest.xml")
        assertTrue(manifest.contains("firebase_messaging_installation_id_enabled\" android:value=\"false\""))
        assertTrue(manifest.contains("firebase_messaging_auto_init_enabled\" android:value=\"false\""))
        assertTrue(manifest.contains("android:name=\".notification.bridge.YamiboFirebaseMessagingService\"\n                android:exported=\"false\""))
        assertTrue(source("AndroidFcmCapability.kt").contains("FirebaseMessaging.getInstance().token.awaitValue()"))
        assertTrue(source("AndroidFcmCapability.kt").contains("context.packageName != BuildConfig.FCM_PACKAGE"))
    }

    @Test
    fun authHookCannotMakeNativeLogoutDependOnRemoteSuccessOrUiScope() {
        val auth = file("shared/src/androidMain/kotlin/me/thenano/yamibo/yamibo_app/repository/AndroidAuthRepository.kt")
        assertTrue(auth.contains("override suspend fun logOut() = withContext(NonCancellable)"))
        assertTrue(auth.contains("withTimeoutOrNull(6_000) { beforeLogout() }"))
        assertTrue(auth.indexOf("beforeLogout()", auth.indexOf("override suspend fun logOut")) < auth.indexOf("cookieStore.clear()"))
        assertTrue(auth.indexOf("cookieStore.clear()") < auth.indexOf("clearAllExceptNox(YamiboRoute.Domain.build())"))
    }

    @Test
    fun bridgeUsesOneForegroundOwnerAndDoesNotStartPersistentService() {
        val bridge = source("AndroidNotificationBridge.kt")
        val lifecycle = bridge.substringAfter("fun onForeground(").substringBefore("fun onSettingsChanged")
        assertTrue(lifecycle.indexOf("foreground = started") < lifecycle.indexOf("scope.launch"))
        assertTrue(lifecycle.contains("updateNetworkObserver(value, foreground)"))
        assertTrue(bridge.contains("loop?.cancelAndJoin()"))
        assertFalse(bridge.contains("startForegroundService"))
        assertFalse(bridge.contains("messageNotificationDailyLimit"))
        assertTrue(bridge.contains("previous ?: try")) // Cold-process unbind can exchange a new session.
        assertTrue(bridge.contains("requireHandled(AndroidMessageNotificationRuntime.check(app, uid, realtime = true))"))
    }

    @Test
    fun tokenProofAndRequestsDoNotLeakThroughQueryOrPersistentBearer() {
        val client = source("NotificationBridgeClient.kt")
        assertTrue(client.contains("followRedirects = false"))
        assertTrue(client.contains("header(\"Last-Event-ID\", it)"))
        assertTrue(client.contains("header(\"X-Yamibo-Installation-Secret\", it.secret)"))
        assertTrue(client.contains("json[\"binding_secret\"]?.jsonPrimitive?.contentOrNull ?: proof?.secret"))
        assertFalse(client.contains("parameters.append"))
        val store = source("NotificationInstallationStore.kt")
        assertTrue(store.contains("context.noBackupFilesDir"))
        assertTrue(store.contains("AES/GCM/NoPadding"))
        assertFalse(store.contains("put(\"bearer\""))
        assertFalse(store.contains("put(\"token\""))
    }

    @Test
    fun incomingPushOnlyQueuesAnInvalidationAndReauthenticatesBeforeDelivery() {
        val service = source("YamiboFirebaseMessagingService.kt")
        assertTrue(service.contains("message.data[\"event\"], message.data[\"signal\"]"))
        val worker = source("NotificationSignalWorker.kt")
        assertTrue(worker.indexOf("api.exchange(cookie, uid)") < worker.indexOf("AndroidMessageNotificationRuntime.check"))
        assertTrue(worker.contains("catch (cancel: CancellationException)"))
        assertTrue(worker.contains("NetworkType.CONNECTED"))
        assertFalse(worker.contains("notification_id"))
        assertFalse(worker.contains("plid"))
    }

    private fun source(name: String): String = file(
        "composeApp/src/androidMain/kotlin/me/thenano/yamibo/yamibo_app/notification/bridge/$name",
    )

    private fun file(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "composeApp/src/androidMain").isDirectory }
        return File(root, path).readText(Charsets.UTF_8)
    }
}
