package me.thenano.yamibo.yamibo_app.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MessageNotificationNavigationTrigger {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun requestOpen() {
        _pending.value = true
    }

    fun consume(): Boolean {
        if (!_pending.value) return false
        _pending.value = false
        return true
    }
}

internal val messageNotificationNavigationTrigger = MessageNotificationNavigationTrigger()

fun requestOpenMessageCenterFromNotification() {
    messageNotificationNavigationTrigger.requestOpen()
}
