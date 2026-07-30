package me.thenano.yamibo.yamibo_app.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncAutomaticTrigger
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServiceStatus

class AppSyncLifecycleController(
    private val statusProvider: () -> AppSyncServiceStatus,
    private val triggerRequest: (AppSyncAutomaticTrigger) -> Long?,
    private val scheduler: AppSyncBackgroundScheduler,
) {
    constructor(
        service: AppSyncService,
        scheduler: AppSyncBackgroundScheduler,
    ) : this(service::currentStatus, service::requestAutomaticTrigger, scheduler)

    fun reconcileRegistration() {
        val status = statusProvider()
        scheduler.setEnabled(
            enabled = status.automaticEnabled,
            interval = status.scheduleSettings.periodicInterval,
        )
        if (status.pendingTriggerGeneration != null) {
            scheduler.runNow()
        }
    }

    fun onForegroundSessionStarted(): Boolean =
        request(AppSyncAutomaticTrigger.AppStartup)

    fun onForegroundSessionExited(): Boolean =
        request(AppSyncAutomaticTrigger.ForegroundExit)

    private fun request(trigger: AppSyncAutomaticTrigger): Boolean {
        if (triggerRequest(trigger) == null) return false
        scheduler.runNow()
        return true
    }
}
