package me.thenano.yamibo.yamibo_app.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageNotificationNavigationTriggerTest {
    @Test
    fun requestIsConsumedExactlyOnce() {
        val trigger = MessageNotificationNavigationTrigger()

        assertFalse(trigger.pending.value)
        trigger.requestOpen()
        assertTrue(trigger.pending.value)
        assertTrue(trigger.consume())
        assertFalse(trigger.pending.value)
        assertFalse(trigger.consume())
    }
}
