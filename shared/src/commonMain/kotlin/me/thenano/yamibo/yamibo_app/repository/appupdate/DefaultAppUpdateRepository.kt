package me.thenano.yamibo.yamibo_app.repository.appupdate

import io.ktor.client.HttpClient
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
        install(ContentNegotiation) {
            json(json)
        }
    },
) : AppUpdateRepository {
    private val mutableDownloadState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)

    override val downloadState: StateFlow<AppUpdateDownloadState> = mutableDownloadState

    override val sources: List<AppUpdateSource> = listOf(
        AppUpdateSource(
            name = "GitHub",
            manifestUrl = "https://raw.githubusercontent.com/LittleSurvival/yamibo-app/update-release/update/stable.json",
        ),
        AppUpdateSource(
            name = "Gitee",
            manifestUrl = "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/stable.json",
        ),
        AppUpdateSource(
            name = "Gitea",
            manifestUrl = "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/stable.json?ref=main",
        ),
    )

    override suspend fun checkForUpdate(force: Boolean): AppUpdateCheckResult {
        appSettingsRepository.appUpdateLastCheckAt.setValue(currentTimeMillis().toString())

        val startIndex = appSettingsRepository.appUpdatePreferredSourceIndex.getValue()
            .coerceIn(0, sources.lastIndex.coerceAtLeast(0))
        val orderedSources = sources.drop(startIndex) + sources.take(startIndex)
        val errors = mutableListOf<String>()
        var preparing: AppUpdateCheckResult.Preparing? = null
        var sawManifest = false

        for (source in orderedSources) {
            val result = runCatching {
                val response = httpClient.get(source.manifestUrl)
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}")
                }
                val manifest = json.decodeFromString<AppUpdateManifestDto>(response.bodyAsText())
                manifest
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

            if (!manifest.isReady) {
                if (manifest.versionCode > platform.currentVersionCode && preparing == null) {
                    preparing = AppUpdateCheckResult.Preparing(
                        channel = manifest.channel,
                        versionName = manifest.versionName,
                        versionCode = manifest.versionCode,
                        sourceName = source.name,
                    )
                }
                continue
            }

            // Fetch remote changelog if possible
            val changelogText = runCatching {
                val changelogUrl = resolveChangelogUrl(source.manifestUrl, manifest.versionCode)
                val response = httpClient.get(changelogUrl)
                if (response.status.isSuccess()) {
                    response.bodyAsText()
                } else {
                    null
                }
            }
                .onFailure { Logger.d(TAG, "Failed to fetch update changelog source=${source.name} version=${manifest.versionCode}", it) }
                .getOrNull()

            val release = manifest.toRelease(source, platform, changelogText)
            appSettingsRepository.appUpdatePreferredSourceIndex.setValue(sources.indexOf(source).coerceAtLeast(0))

            return when {
                release.versionCode <= platform.currentVersionCode -> AppUpdateCheckResult.UpToDate(platform.currentVersionName)
                !force && appSettingsRepository.appUpdateIgnoredVersionCode.getValue().toLong() == release.versionCode -> {
                    AppUpdateCheckResult.Ignored(release)
                }
                else -> AppUpdateCheckResult.UpdateAvailable(release)
            }
        }

        preparing?.let { return it }
        if (sawManifest) {
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
