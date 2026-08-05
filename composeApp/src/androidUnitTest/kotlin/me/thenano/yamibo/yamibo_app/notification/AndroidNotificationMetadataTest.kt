package me.thenano.yamibo.yamibo_app.notification

import me.thenano.yamibo.yamibo_app.R
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidNotificationMetadataTest {
    @Test
    fun sharedMetadataUsesDedicatedIconAndStableSignIdentity() {
        assertEquals(R.drawable.ic_stat_yamibo, AndroidNotificationMetadata.SMALL_ICON_RES_ID)
        assertEquals("yamibo_sign_reminder_channel", AndroidNotificationMetadata.SIGN_REMINDER_CHANNEL_ID)
        assertEquals(240619, AndroidNotificationMetadata.SIGN_REMINDER_NOTIFICATION_ID)
    }

    @Test
    fun dismissingAnAbsentOrExistingReminderIsIdempotentAtTheBoundary() {
        val cancelledIds = mutableListOf<Int>()
        val controller = SignReminderNotificationController(cancelledIds::add)

        controller.dismissActiveReminder()
        controller.dismissActiveReminder()

        assertEquals(listOf(240619, 240619), cancelledIds)
    }
}
