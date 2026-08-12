package me.thenano.yamibo.yamibo_app.profile.settings.sign

import me.thenano.yamibo.yamibo_app.repository.settings.SignReminderFrequency

class IOSSignReminderScheduler : SignReminderScheduler {
    override suspend fun schedule(frequency: SignReminderFrequency) {
        // Stub on iOS
    }

    override suspend fun runNow() {
        // Stub on iOS
    }

    override suspend fun cancel() {
        // Stub on iOS
    }

    override suspend fun dismissActiveReminder() {
        // Android-only notification; intentionally a no-op on iOS.
    }
}
