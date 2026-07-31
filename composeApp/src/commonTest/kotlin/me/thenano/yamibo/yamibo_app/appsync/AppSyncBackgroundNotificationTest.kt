package me.thenano.yamibo.yamibo_app.appsync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase

class AppSyncBackgroundNotificationTest {
    @Test
    fun notifiesOnlyWhenBackgroundWorkEntersQuarantine() {
        assertTrue(
            shouldNotifyBackgroundQuarantine(
                AppSyncServicePhase.BootstrapRequired,
                AppSyncServicePhase.Quarantined,
            ),
        )
        assertFalse(
            shouldNotifyBackgroundQuarantine(
                AppSyncServicePhase.Quarantined,
                AppSyncServicePhase.Quarantined,
            ),
        )
        assertFalse(
            shouldNotifyBackgroundQuarantine(
                AppSyncServicePhase.Active,
                AppSyncServicePhase.PausedProvider,
            ),
        )
    }
}
