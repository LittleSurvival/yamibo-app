package me.thenano.yamibo.yamibo_app.profile.settings.sign

import me.thenano.yamibo.yamibo_app.repository.settings.SignReminderFrequency

interface SignReminderScheduler {
    suspend fun schedule(frequency: SignReminderFrequency)
    suspend fun runNow()
    suspend fun cancel()
    suspend fun dismissActiveReminder()
}
