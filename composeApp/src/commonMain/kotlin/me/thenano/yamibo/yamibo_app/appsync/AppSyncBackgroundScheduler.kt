package me.thenano.yamibo.yamibo_app.appsync

import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

interface AppSyncBackgroundScheduler {
    fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval)
    fun runNow()
}
