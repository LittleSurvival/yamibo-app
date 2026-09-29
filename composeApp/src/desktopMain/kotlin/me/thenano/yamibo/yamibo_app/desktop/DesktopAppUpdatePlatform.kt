package me.thenano.yamibo.yamibo_app.desktop

import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.*
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.repository.appupdate.*
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile

internal class DesktopAppUpdatePlatform : AppUpdatePlatform {
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
        else -> setOf("deb", "rpm", "AppImage")
    }
    @Volatile private var downloadJob: Job? = null

    override fun supportsAsset(type: String, platform: String?, abi: String?): Boolean =
        super.supportsAsset(type, platform, abi) && desktopUpdateArchitectureMatches(abi, System.getProperty("os.arch"))

    override suspend fun downloadAndInstall(release: AppUpdateRelease, onProgress: (Long, Long?) -> Unit): AppUpdateDownloadState = coroutineScope {
        downloadJob = currentCoroutineContext()[Job]
        try {
            val asset = requireNotNull(release.asset) { "此更新尚未提供桌面檔案" }
            require(asset.type in supportedAssetTypes) { "更新檔不適用於此平台" }
            val hash = requireNotNull(asset.sha256).lowercase()
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "更新檔缺少有效的 SHA-256" }
            val target = withContext(Dispatchers.Main) {
                chooseDesktopFile(save = true, name = "Yamibo-update.${asset.type}")
            } ?: return@coroutineScope AppUpdateDownloadState.Idle
            withContext(Dispatchers.IO) {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                var url = URI(asset.url)
                var response: HttpResponse<java.io.InputStream>? = null
                for (attempt in 0..5) {
                    ensureActive()
                    require(url.scheme.equals("https", true) && url.userInfo == null) { "更新下載僅允許 HTTPS" }
                    val next = runInterruptible {
                        client.send(HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                    }
                    if (next.statusCode() in setOf(301, 302, 303, 307, 308)) {
                        next.body().close()
                        url = url.resolve(next.headers().firstValue("Location").orElseThrow())
                    } else { response = next; break }
                }
                val result = requireNotNull(response) { "下載重新導向過多" }
                result.body().use { input ->
                    require(result.statusCode() == 200) { "更新下載失敗：HTTP ${result.statusCode()}" }
                    val temp = Files.createTempFile(target.toPath().toAbsolutePath().parent, ".yamibo-update-", ".tmp")
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        Files.newOutputStream(temp).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                ensureActive()
                                val read = runInterruptible { input.read(buffer) }
                                if (read < 0) break
                                output.write(buffer, 0, read); digest.update(buffer, 0, read); count += read
                                onProgress(count, asset.size)
                            }
                        }
                        require(asset.size == null || asset.size == count) { "更新檔大小不符" }
                        require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "更新檔 SHA-256 不符" }
                        Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temp) }
                }
            }
            // Only the user's explicit update action opens the verified installer.
            withContext(Dispatchers.IO) {
                ensureActive()
                if (platformKey == "linux" && asset.type == "AppImage") {
                    val path = target.toPath().toAbsolutePath()
                    Files.setPosixFilePermissions(path,
                        Files.getPosixFilePermissions(path) + PosixFilePermission.OWNER_EXECUTE)
                    // A single argument, no shell interpretation of spaces or metacharacters.
                    ProcessBuilder(path.toString()).start()
                } else {
                    Desktop.getDesktop().open(target)
                }
            }
            AppUpdateDownloadState.Completed(release)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { AppUpdateDownloadState.Failed(release, error.message ?: "桌面更新未完成") }
        finally { downloadJob = null }
    }
    override fun cancelDownload() { downloadJob?.cancel() }
    override fun openReleasePage(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", true) && uri.userInfo == null)
        Desktop.getDesktop().browse(uri)
    }
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
