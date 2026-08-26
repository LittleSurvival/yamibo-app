package me.thenano.yamibo.yamibo_app.notification

import android.content.Context
import me.thenano.yamibo.yamibo_app.network.AndroidYamiboClientProvider
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationDeliveryStateStore
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

internal object AndroidMessageNotificationRuntime {
    fun createChecker(context: Context): MessageNotificationChecker {
        val appContext = context.applicationContext
        val rawSettings = AndroidSettingsStore(appContext)
        val cookieStore = AndroidCookieStore(appContext)
        val userStore = AndroidUserStore(appContext)
        val client = AndroidYamiboClientProvider.get(appContext)
        return MessageNotificationChecker(
            settings = AppSettingsRepository(rawSettings),
            deliveryStateStore = MessageNotificationDeliveryStateStore(rawSettings),
            currentUserId = { userStore.load()?.uid?.value },
            fetchHomePage = {
                client.setCookie(cookieStore.load().orEmpty())
                client.fetchHomePage()
            },
            notificationGateway = AndroidMessageNotificationGateway(appContext),
        )
    }
}
