package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.flow.StateFlow
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateCheckResult
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateDownloadState
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateRelease
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateSource

interface AppUpdateRepository {
    val downloadState: StateFlow<AppUpdateDownloadState>
    val sources: List<AppUpdateSource>
    val manualDownloadUrl: String? get() = null
    fun openManualDownloadPage() {}

    suspend fun checkForUpdate(force: Boolean = false): AppUpdateCheckResult
    suspend fun downloadAndInstall(release: AppUpdateRelease): AppUpdateDownloadState
    fun ignoreRelease(release: AppUpdateRelease)
    fun cancelDownload()
    fun openReleasePage(release: AppUpdateRelease)

    val isInstallPermissionGranted: Boolean get() = true
}
