package me.thenano.yamibo.yamibo_app.repository.appupdate

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.AppUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class DefaultAppUpdateRepository(
    private val appSettingsRepository: AppSettingsRepository,
    private val platform: AppUpdatePlatform,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = APP_UPDATE_GITHUB_TIMEOUT_MILLIS
            connectTimeoutMillis = APP_UPDATE_GITHUB_TIMEOUT_MILLIS
            socketTimeoutMillis = APP_UPDATE_GITHUB_TIMEOUT_MILLIS
        }
        install(ContentNegotiation) {
            json(json)
        }
    },
) : AppUpdateRepository {
    private val mutableDownloadState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)

    override val downloadState: StateFlow<AppUpdateDownloadState> = mutableDownloadState

    override val sources: List<AppUpdateSource> = listOf(
        AppUpdateSource(
            name = "github.cnxiaobai.com",
            manifestUrl = "https://github.cnxiaobai.com/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "gh.halonice.com",
            manifestUrl = "https://gh.halonice.com/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "ghproxy.sakuramoe.dev",
            manifestUrl = "https://ghproxy.sakuramoe.dev/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "gh.padao.fun",
            manifestUrl = "https://gh.padao.fun/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "gh.jasonzeng.dev",
            manifestUrl = "https://gh.jasonzeng.dev/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "ghproxy.mirror.skybyte.me",
            manifestUrl = "https://ghproxy.mirror.skybyte.me/$GITHUB_RAW_MANIFEST_URL",
            requestTimeoutMillis = APP_UPDATE_PROXY_TIMEOUT_MILLIS,
        ),
        AppUpdateSource(
            name = "GitHub",
            manifestUrl = GITHUB_RAW_MANIFEST_URL,
            requestTimeoutMillis = APP_UPDATE_GITHUB_TIMEOUT_MILLIS,
        ),
    )

    override suspend fun checkForUpdate(force: Boolean): AppUpdateCheckResult {
        appSettingsRepository.appUpdateLastCheckAt.setValue(currentTimeMillis().toString())

        val startIndex = appSettingsRepository.appUpdatePreferredSourceIndex.getValue()
            .coerceIn(0, sources.lastIndex.coerceAtLeast(0))
        val orderedSources = sources.drop(startIndex) + sources.take(startIndex)
        val errors = mutableListOf<String>()
        var preparing: AppUpdateCheckResult.Preparing? = null
        var ignored: AppUpdateRelease? = null
        var sawManifest = false
        var preparingSourceIndex: Int? = null
        // First source that returned a decodable manifest in this check. It becomes the preferred
        // source for the next check when no ready update is found (e.g. UpToDate), so the last
        // successfully detected mirror is tried first next time.
        var firstSuccessfulSource: AppUpdateSource? = null

        for (source in orderedSources) {
            val result = runCatching {
                val response = httpClient.get(source.manifestUrl) {
                    timeout {
                        requestTimeoutMillis = source.requestTimeoutMillis
                        connectTimeoutMillis = source.requestTimeoutMillis
                        socketTimeoutMillis = source.requestTimeoutMillis
                    }
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}")
                }
                json.decodeFromString<AppUpdateManifestDto>(response.bodyAsText())
            }
            val manifest = result
                .onFailure { error ->
                    if (error !is CancellationException) {
                        Logger.w(TAG, "Failed to fetch or decode update manifest source=${source.name}", error)
                    }
                }
                .getOrNull()
            if (manifest == null) {
                if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull() as CancellationException
                val message = result.exceptionOrNull()?.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?: "manifest parse failed"
                errors += "${source.name}: $message"
                continue
            }
            sawManifest = true
            if (firstSuccessfulSource == null) {
                firstSuccessfulSource = source
            }

            if (!manifest.isReady) {
                if (manifest.versionCode > platform.currentVersionCode && preparing == null) {
                    preparing = AppUpdateCheckResult.Preparing(
                        channel = manifest.channel,
                        versionName = manifest.versionName,
                        versionCode = manifest.versionCode,
                        sourceName = source.name,
                    )
                    preparingSourceIndex = sources.indexOf(source).coerceAtLeast(0)
                }
                continue
            }

            // Fetch remote changelog if possible
            val changelogText = runCatching {
                val changelogUrl = resolveChangelogUrl(source.manifestUrl, manifest.versionCode)
                val response = httpClient.get(changelogUrl) {
                    timeout {
                        requestTimeoutMillis = source.requestTimeoutMillis
                        connectTimeoutMillis = source.requestTimeoutMillis
                        socketTimeoutMillis = source.requestTimeoutMillis
                    }
                }
                if (response.status.isSuccess()) {
                    response.bodyAsText()
                } else {
                    null
                }
            }
                .onFailure { Logger.d(TAG, "Failed to fetch update changelog source=${source.name} version=${manifest.versionCode}", it) }
                .getOrNull()

            val release = manifest.toRelease(source, platform, changelogText)

            when {
                release.versionCode <= platform.currentVersionCode -> {
                    // 该源是旧版本或与当前一致：继续尝试其余镜像，避免陈旧镜像掩盖更新或错误报「已是最新」
                    Logger.d(TAG, "Update source is stale source=${source.name} version=${release.versionCode}")
                }
                !force && appSettingsRepository.appUpdateIgnoredVersionCode.getValue().toLong() == release.versionCode -> {
                    if (ignored == null) ignored = release
                }
                else -> {
                    appSettingsRepository.appUpdatePreferredSourceIndex.setValue(
                        sources.indexOf(source).coerceAtLeast(0),
                    )
                    return AppUpdateCheckResult.UpdateAvailable(release)
                }
            }
        }

        preparing?.let {
            preparingSourceIndex?.let { index ->
                appSettingsRepository.appUpdatePreferredSourceIndex.setValue(index)
            }
            return it
        }
        ignored?.let {
            appSettingsRepository.appUpdatePreferredSourceIndex.setValue(
                sources.indexOf(it.source).coerceAtLeast(0),
            )
            return AppUpdateCheckResult.Ignored(it)
        }
        if (sawManifest) {
            firstSuccessfulSource?.let { source ->
                appSettingsRepository.appUpdatePreferredSourceIndex.setValue(
                    sources.indexOf(source).coerceAtLeast(0),
                )
            }
            return AppUpdateCheckResult.UpToDate(platform.currentVersionName)
        }
        return AppUpdateCheckResult.Failed(errors.joinToString(separator = "\n").ifBlank { "No update source available" })
    }

    override suspend fun downloadAndInstall(release: AppUpdateRelease): AppUpdateDownloadState {
        if (release.asset == null) {
            val failed = AppUpdateDownloadState.Failed(release, "No installable asset for ${platform.platformKey}")
            mutableDownloadState.value = failed
            return failed
        }
        mutableDownloadState.value = AppUpdateDownloadState.Running(release, 0L, release.asset.size)
        val result = platform.downloadAndInstall(release) { downloaded, total ->
            mutableDownloadState.value = AppUpdateDownloadState.Running(release, downloaded, total ?: release.asset.size)
        }
        mutableDownloadState.value = result
        return result
    }

    override fun ignoreRelease(release: AppUpdateRelease) {
        appSettingsRepository.appUpdateIgnoredVersionCode.setValue(release.versionCode.toInt())
    }

    override fun cancelDownload() {
        platform.cancelDownload()
        mutableDownloadState.value = AppUpdateDownloadState.Idle
    }

    override fun openReleasePage(release: AppUpdateRelease) {
        platform.openReleasePage(release.releaseUrl)
    }

    override val isInstallPermissionGranted: Boolean
        get() = platform.isInstallPermissionGranted
}

private const val TAG = "AppUpdateRepository"

private const val GITHUB_RAW_MANIFEST_URL =
    "https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json"

@Serializable
private data class AppUpdateManifestDto(
    val channel: String = "stable",
    val versionName: String,
    val versionCode: Long,
    val isReady: Boolean = false,
    val minVersionCode: Long? = null,
    val releaseNotes: String? = null,
    val releaseUrl: String? = null,
    val assets: List<AppUpdateAssetDto> = emptyList(),
)

@Serializable
private data class AppUpdateAssetDto(
    val type: String,
    val url: String,
    val sha256: String? = null,
    val size: Long? = null,
    val platform: String? = null,
    val abi: String? = null,
    @SerialName("fileName") val fileName: String? = null,
)

private fun AppUpdateManifestDto.toRelease(
    source: AppUpdateSource,
    platform: AppUpdatePlatform,
    remoteChangelog: String? = null,
): AppUpdateRelease {
    val selectedAsset = assets.firstOrNull { asset ->
        val platformMatches = asset.platform == null || asset.platform.equals(platform.platformKey, ignoreCase = true)
        platformMatches && asset.type in platform.supportedAssetTypes
    } ?: assets.firstOrNull { it.type in platform.supportedAssetTypes }

    return AppUpdateRelease(
        source = source,
        channel = channel,
        versionName = versionName,
        versionCode = versionCode,
        minVersionCode = minVersionCode,
        releaseNotes = releaseNotes.orEmpty(),
        releaseUrl = releaseUrl ?: source.manifestUrl,
        asset = selectedAsset?.let {
            AppUpdateAsset(
                type = it.type,
                url = it.url,
                sha256 = it.sha256,
                size = it.size,
            )
        },
        changelogText = remoteChangelog?.takeIf { it.isNotBlank() } ?: releaseNotes.orEmpty(),
    )
}

internal fun resolveChangelogUrl(manifestUrl: String, versionCode: Long): String {
    val parts = manifestUrl.split("?", limit = 2)
    val baseUrl = parts[0]
    val query = parts.getOrNull(1)

    val lastSlashIndex = baseUrl.lastIndexOf('/')
    val parentUrl = if (lastSlashIndex != -1) baseUrl.substring(0, lastSlashIndex) else baseUrl
    val newBase = "$parentUrl/changelogs/$versionCode.changelog"

    return if (query != null) "$newBase?$query" else newBase
}
