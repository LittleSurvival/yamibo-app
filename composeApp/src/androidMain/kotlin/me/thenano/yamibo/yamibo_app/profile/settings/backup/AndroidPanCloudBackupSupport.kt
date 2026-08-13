package me.thenano.yamibo.yamibo_app.profile.settings.backup

import android.content.Context
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudAccountRepository
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudApiClient
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

internal data class PanCloudBackupComponents(
    val backupRepository: BackupRepositoryImpl,
    val accountRepository: PanCloudAccountRepository,
)

internal object AndroidPanCloudBackupSupport {
    fun createComponents(context: Context): PanCloudBackupComponents {
        val appContext = context.applicationContext
        val settingsStore = AndroidSettingsStore(appContext)
        val appSettingsRepository = AppSettingsRepository(settingsStore)
        val novelSettingsRepository = NovelReaderSettingsRepository(settingsStore)
        val mangaSettingsRepository = MangaReaderSettingsRepository(settingsStore)
        val db = Database(DatabaseFactory(appContext).createDriver())
        val apiClient = PanCloudApiClient(HttpClientFactory.create())
        val accountRepository = PanCloudAccountRepository(apiClient, appSettingsRepository)
        val backupRepository = BackupRepositoryImpl(
            db = db,
            settingsStore = settingsStore,
            settingsRegistries = listOf(appSettingsRepository, novelSettingsRepository, mangaSettingsRepository),
            storageProvider = PanCloudBackupStorageProvider(apiClient, accountRepository),
            appVersionCode = AppVersion.VersionCode.toInt(),
        )
        return PanCloudBackupComponents(backupRepository, accountRepository)
    }
}
