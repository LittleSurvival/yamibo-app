package me.thenano.yamibo.yamibo_app.profile.settings.sign

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignReminderWorkerTest {
    @Test
    fun ordinaryReminderIsSuppressedOnlyWhenLocallySigned() {
        assertFalse(shouldPostSignReminder(isTest = false, knownSignedToday = true))
        assertTrue(shouldPostSignReminder(isTest = false, knownSignedToday = false))
        assertTrue(shouldPostSignReminder(isTest = false, knownSignedToday = null))
    }

    @Test
    fun explicitTestReminderBypassesSignedGuard() {
        assertTrue(shouldPostSignReminder(isTest = true, knownSignedToday = true))
    }
}
