package me.thenano.yamibo.yamibo_app.appsync

interface AppSyncBackgroundScheduler {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun runNow()
}
