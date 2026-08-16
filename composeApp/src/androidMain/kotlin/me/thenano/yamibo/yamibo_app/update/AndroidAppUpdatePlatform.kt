package me.thenano.yamibo.yamibo_app.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateAsset
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateDownloadState
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdatePlatform
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateRelease
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AndroidAppUpdatePlatform(
    private val context: Context,
) : AppUpdatePlatform {
    @Volatile
    private var canceled = false

    override val currentVersionCode: Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }

    override val currentVersionName: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    override val platformKey: String = "android"
    override val supportedAssetTypes: Set<String> = setOf("universal-apk", "apk")

    override val isInstallPermissionGranted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override suspend fun downloadAndInstall(
        release: AppUpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): AppUpdateDownloadState = withContext(Dispatchers.IO) {
        val asset = release.asset ?: return@withContext AppUpdateDownloadState.Failed(release, "No APK asset")
        canceled = false
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "yamibo-${release.versionName}.apk")

        if (apkFile.isValidCachedApk(asset)) {
            return@withContext requestInstall(apkFile, release)
        }

        val urls = resolveApkDownloadUrls(asset.url)
        if (urls.isEmpty()) {
            return@withContext AppUpdateDownloadState.Failed(release, "APK asset URL is blank")
        }

        var lastError: Throwable? = null
        for (url in urls) {
            if (canceled) {
                lastError = CancellationException()
                break
            }
            val attempt = runCatching {
                downloadApkToFile(url, apkFile, asset, onProgress)
            }
            if (attempt.isSuccess) {
                lastError = null
                break
            }
            lastError = attempt.exceptionOrNull()
            apkFile.delete()
            if (lastError is CancellationException) break
            Logger.w(TAG, "APK download attempt failed; switching to next download URL url=$url", lastError)
        }

        if (lastError != null) {
            apkFile.delete()
            val message = if (lastError is CancellationException) {
                "Download canceled"
            } else {
                lastError.message ?: "Download failed"
            }
            return@withContext AppUpdateDownloadState.Failed(release, message)
        }
        requestInstall(apkFile, release)
    }

    override fun cancelDownload() {
        canceled = true
    }

    override fun openReleasePage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun downloadApkToFile(
        url: String,
        apkFile: File,
        asset: AppUpdateAsset,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        if (apkFile.exists()) apkFile.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("APK download failed: HTTP $status")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.contains("text/html")) {
                error("APK download returned HTML instead of an APK")
            }
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    val total = asset.size ?: connection.contentLengthLong.takeIf { it >= 0L }
                    while (true) {
                        if (canceled) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!apkFile.hasApkZipSignature()) error("Downloaded file is not a valid APK/ZIP payload")
        asset.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = apkFile.sha256()
            if (!actual.equals(expected, ignoreCase = true)) {
                error("APK sha256 mismatch: expected $expected, actual $actual")
            }
        }
    }

    private fun File.isValidCachedApk(asset: AppUpdateAsset): Boolean {
        val sha = asset.sha256
        return exists() && hasApkZipSignature() && runCatching {
            sha.isNullOrBlank() || sha256().equals(sha, ignoreCase = true)
        }
            .onFailure { Logger.d(TAG, "Cached APK validation failed; downloading a fresh asset", it) }
            .getOrDefault(false)
    }

    private fun requestInstall(apkFile: File, release: AppUpdateRelease): AppUpdateDownloadState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            return AppUpdateDownloadState.PermissionRequired(release)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return AppUpdateDownloadState.Completed(release)
    }
}

internal const val GH_PROXY_BASE_URL = "https://gh-proxy.com/"

/**
 * GitHub Release 资产直连失败时，通过 gh-proxy.com 代理重试。
 * 其余来源（Gitee/Gitea 等）保持直连。
 */
internal fun resolveApkDownloadUrls(assetUrl: String): List<String> {
    val normalized = assetUrl.trim()
    if (normalized.isEmpty()) return emptyList()
    val host = runCatching { URL(normalized).host.lowercase() }.getOrNull()
    val isGitHubHosted = host != null &&
        (host == "github.com" || host == "objects.githubusercontent.com" || host.endsWith(".github.com"))
    return if (isGitHubHosted) {
        listOf(normalized, GH_PROXY_BASE_URL + normalized)
    } else {
        listOf(normalized)
    }
}

private const val TAG = "AndroidAppUpdate"

private class CancellationException : Exception()

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun File.hasApkZipSignature(): Boolean {
    return length() >= 4L && inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == header.size &&
            header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }
}
