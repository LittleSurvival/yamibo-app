package me.thenano.yamibo.yamibo_app.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncRecoveryWorkLedger
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

interface AppSyncBackgroundScheduler {
    fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval)
    fun runNow()
    val ownsManualExecution: Boolean get() = false
    suspend fun runManual() = runNow()
    suspend fun reconcileRecoveryWork(service: AppSyncRecoveryWorkLedger) = Unit
}

internal fun shouldNotifyBackgroundQuarantine(
    previous: AppSyncServicePhase,
    current: AppSyncServicePhase,
): Boolean = previous != AppSyncServicePhase.Quarantined &&
    previous != AppSyncServicePhase.RecoveryNeedsAttention &&
    current in setOf(
        AppSyncServicePhase.Quarantined,
        AppSyncServicePhase.RecoveryNeedsAttention,
    )
