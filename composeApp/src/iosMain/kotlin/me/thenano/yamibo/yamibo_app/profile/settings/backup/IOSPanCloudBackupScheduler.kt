package me.thenano.yamibo.yamibo_app.profile.settings.backup

import me.thenano.yamibo.yamibo_app.repository.settings.BackupInterval

class IOSPanCloudBackupScheduler : BackupScheduler {
    override suspend fun schedule(interval: BackupInterval) {
        // TODO: Wire BGTaskScheduler when the iOS background policy is finalized.
    }

    override suspend fun runNow() {
        // iOS first version keeps manual backup in the foreground UI path.
    }

    override suspend fun cancel() {
        // No periodic background worker is registered in the first iOS version.
    }
}
