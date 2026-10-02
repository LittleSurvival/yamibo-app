package me.thenano.yamibo.yamibo_app.notification.bridge

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class YamiboFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        // Never log or persist the registration token separately from the Firebase SDK.
        AndroidNotificationBridge.onNewToken(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AndroidNotificationBridge.onPush(this, message.data["event"], message.data["signal"])
    }

    override fun onDeletedMessages() {
        // Provider dropped pending messages: a foreground session plus ordinary polling resyncs state.
        AndroidNotificationBridge.onSettingsChanged(this)
    }
}
