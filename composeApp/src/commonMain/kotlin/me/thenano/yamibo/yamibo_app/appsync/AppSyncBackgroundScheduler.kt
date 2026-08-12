package me.thenano.yamibo.yamibo_app.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

interface AppSyncBackgroundScheduler {
    fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval)
    fun runNow()
}

internal fun shouldNotifyBackgroundQuarantine(
    previous: AppSyncServicePhase,
    current: AppSyncServicePhase,
): Boolean = previous != AppSyncServicePhase.Quarantined &&
    current == AppSyncServicePhase.Quarantined
