package me.thenano.yamibo.yamibo_app.repository.appupdate

interface AppUpdatePlatform {
    val currentVersionCode: Long
    val currentVersionName: String
    val platformKey: String
    val supportedAssetTypes: Set<String>

    fun supportsAsset(type: String, platform: String?, abi: String?): Boolean =
        type in supportedAssetTypes && (platform == null || platform.equals(platformKey, ignoreCase = true))

    suspend fun downloadAndInstall(
        release: AppUpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): AppUpdateDownloadState

    fun cancelDownload()
    fun openReleasePage(url: String)

    val isInstallPermissionGranted: Boolean get() = true
}
