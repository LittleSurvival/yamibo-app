package me.thenano.yamibo.yamibo_app.desktop

import java.awt.Desktop
import java.net.URI
import java.net.HttpURLConnection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.repository.appupdate.*
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile

internal class DesktopAppUpdatePlatform(
    private val requestInstall: (File) -> Boolean = { false },
) : AppUpdatePlatform {
    override val currentVersionCode = AppVersion.VersionCode.toLong()
    override val currentVersionName = AppVersion.VersionName
    override val platformKey = when {
        System.getProperty("os.name").startsWith("Windows", true) -> "windows"
        System.getProperty("os.name").startsWith("Mac", true) -> "macos"
        else -> "linux"
    }
    override val supportedAssetTypes = when (platformKey) {
        "windows" -> setOf("exe", "msi")
        "macos" -> setOf("dmg", "pkg")
        else -> when {
            File("/etc/debian_version").isFile -> setOf("deb")
            File("/etc/redhat-release").isFile -> setOf("rpm")
            else -> emptySet()
        }
    }
    @Volatile private var downloadJob: Job? = null

    override fun supportsAsset(type: String, platform: String?, abi: String?): Boolean =
        super.supportsAsset(type, platform, abi) && desktopUpdateArchitectureMatches(abi, System.getProperty("os.arch"))

    override suspend fun downloadAndInstall(release: AppUpdateRelease, onProgress: (Long, Long?) -> Unit): AppUpdateDownloadState = coroutineScope {
        val activeJob = currentCoroutineContext()[Job]
        downloadJob = activeJob
        try {
            val asset = requireNotNull(release.asset) { "此更新尚未提供桌面檔案" }
            require(asset.type in supportedAssetTypes) { "更新檔不適用於此平台" }
            val hash = requireNotNull(asset.sha256).lowercase()
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "更新檔缺少有效的 SHA-256" }
            val initialUrl = URI(asset.url)
            require(initialUrl.scheme.equals("https", true) && initialUrl.userInfo == null &&
                initialUrl.host.equals("github.com", true) && initialUrl.path.startsWith("/LittleSurvival/yamibo-app/releases/download/")) {
                "桌面更新僅從 GitHub 發布頁下載"
            }
            val target = withContext(Dispatchers.Main) {
                chooseDesktopFile(save = true, name = "Yamibo-update.${asset.type}")
            } ?: return@coroutineScope AppUpdateDownloadState.Idle
            withTimeout(30 * 60_000L) { withContext(Dispatchers.IO) {
                var url = initialUrl
                var response: HttpURLConnection? = null
                for (attempt in 0..5) {
                    ensureActive()
                    require(url.scheme.equals("https", true) && url.userInfo == null) { "更新下載僅允許 HTTPS" }
                    val next = (url.toURL().openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    try {
                        val status = runInterruptible { next.responseCode }
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            url = url.resolve(requireNotNull(next.getHeaderField("Location")))
                        } else {
                            require(status == 200) { "更新下載失敗：HTTP $status" }
                            response = next
                            break
                        }
                    } finally { if (response !== next) next.disconnect() }
                }
                val result = requireNotNull(response) { "下載重新導向過多" }
                try { result.inputStream.use { input ->
                    saveVerifiedDesktopUpdate(input, target, asset, onProgress)
                } } finally { result.disconnect() }
            } }
            val accepted = withContext(Dispatchers.Main) {
                ensureActive()
                requestInstall(target)
            }
            if (accepted) AppUpdateDownloadState.Completed(release) else AppUpdateDownloadState.Idle
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            AppUpdateDownloadState.Failed(release, "更新下載逾時，請重試或前往 GitHub 手動下載。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { AppUpdateDownloadState.Failed(release, error.message ?: "桌面更新未完成") }
        finally { if (downloadJob === activeJob) downloadJob = null }
    }
    override fun cancelDownload() { downloadJob?.cancel() }
    override fun openReleasePage(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", true) && uri.userInfo == null)
        try { Desktop.getDesktop().browse(uri) }
        catch (_: Exception) {
            javax.swing.JOptionPane.showMessageDialog(null, "無法開啟瀏覽器，請複製下載連結並手動開啟：\n$url",
                "手動下載", javax.swing.JOptionPane.WARNING_MESSAGE)
        }
    }
}

internal suspend fun saveVerifiedDesktopUpdate(
    input: java.io.InputStream, target: File, asset: AppUpdateAsset, onProgress: (Long, Long?) -> Unit,
) {
    val temp = Files.createTempFile(target.toPath().toAbsolutePath().parent, ".yamibo-update-", ".tmp")
    val expectedSize = asset.size
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newOutputStream(temp).use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = runInterruptible { input.read(buffer) }
                if (read < 0) break
                count += read
                require(expectedSize == null || count <= expectedSize) { "更新檔大小不符" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                onProgress(count, asset.size)
            }
        }
        currentCoroutineContext().ensureActive()
        require(asset.size == null || asset.size == count) { "更新檔大小不符" }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(asset.sha256, true)) { "更新檔 SHA-256 不符" }
        Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}

internal fun desktopUpdateArchitectureMatches(assetAbi: String?, hostArchitecture: String): Boolean {
    if (assetAbi == null || assetAbi.equals("universal", ignoreCase = true)) return true
    fun normalized(value: String) = when (value.lowercase()) {
        "amd64", "x64", "x86_64" -> "x86_64"
        "aarch64", "arm64", "arm64-v8a" -> "arm64"
        "x86", "i386", "i686" -> "x86"
        else -> value.lowercase()
    }
    return normalized(assetAbi) == normalized(hostArchitecture)
}
