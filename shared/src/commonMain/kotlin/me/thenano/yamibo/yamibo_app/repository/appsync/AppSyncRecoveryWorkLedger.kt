package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRecoveryWorkRequest

/** Account-bound durable evidence used by platform schedulers, without provider access. */
interface AppSyncRecoveryWorkLedger {
    fun recoveryWorkRequest(): AppSyncRecoveryWorkRequest?
    fun prepareRecoveryWork(proposedId: String): AppSyncRecoveryWorkRequest?
    fun confirmRecoveryWorkEnqueued(requestId: String): Boolean
    fun beginRecoveryWork(requestId: String): Boolean
    fun retireRecoveryWork(requestId: String)
}
