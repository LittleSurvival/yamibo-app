package me.thenano.yamibo.yamibo_app.repository.appupdate

const val APP_UPDATE_PROXY_TIMEOUT_MILLIS = 5_000L
const val APP_UPDATE_GITHUB_TIMEOUT_MILLIS = 10_000L

data class AppUpdateSource(
    val name: String,
    val manifestUrl: String,
    val requestTimeoutMillis: Long,
)

data class AppUpdateAsset(
    val type: String,
    val url: String,
    val sha256: String?,
    val size: Long?,
)

data class AppUpdateRelease(
    val source: AppUpdateSource,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val minVersionCode: Long?,
    val releaseNotes: String,
    val releaseUrl: String,
    val asset: AppUpdateAsset?,
    val changelogText: String = "",
)

fun AppUpdateRelease.fullVersionName(): String = "$channel-v$versionName"

fun AppUpdateRelease.changelogContent(): String = changelogText.ifBlank { releaseNotes }

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val release: AppUpdateRelease) : AppUpdateCheckResult
    data class UpToDate(val currentVersionName: String) : AppUpdateCheckResult
    data class Ignored(val release: AppUpdateRelease) : AppUpdateCheckResult
    data class Preparing(val channel: String, val versionName: String, val versionCode: Long, val sourceName: String) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Running(val release: AppUpdateRelease, val downloadedBytes: Long, val totalBytes: Long?) : AppUpdateDownloadState
    data class Completed(val release: AppUpdateRelease) : AppUpdateDownloadState
    data class PermissionRequired(val release: AppUpdateRelease) : AppUpdateDownloadState
    data class Failed(val release: AppUpdateRelease?, val message: String) : AppUpdateDownloadState
}
