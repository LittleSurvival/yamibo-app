package me.thenano.yamibo.yamibo_app.repository.appupdate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val PROXY_SOURCE_NAMES = listOf(
    "github.cnxiaobai.com",
    "gh.halonice.com",
    "ghproxy.sakuramoe.dev",
    "gh.padao.fun",
    "gh.jasonzeng.dev",
    "ghproxy.mirror.skybyte.me",
)

private val ALL_SOURCE_NAMES = PROXY_SOURCE_NAMES + "GitHub"

class DefaultAppUpdateRepositoryTest {

    @Test
    fun sourcesAreProxyMirrorsFirstThenGitHubDirect() {
        val environment = environment()
        val sources = environment.repository.sources

        assertEquals(ALL_SOURCE_NAMES, sources.map { it.name })
        assertEquals(
            listOf(
                "https://github.cnxiaobai.com/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://gh.halonice.com/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://ghproxy.sakuramoe.dev/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://gh.padao.fun/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://gh.jasonzeng.dev/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://ghproxy.mirror.skybyte.me/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
                "https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            ),
            sources.map { it.manifestUrl },
        )
        assertTrue(sources.dropLast(1).all { it.requestTimeoutMillis == APP_UPDATE_PROXY_TIMEOUT_MILLIS })
        assertEquals(APP_UPDATE_GITHUB_TIMEOUT_MILLIS, sources.last().requestTimeoutMillis)
    }

    @Test
    fun firstProxyMirrorIsUsedFirstByDefault() = runBlocking {
        val environment = environment(currentVersionCode = 8)

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("github.cnxiaobai.com", result.release.source.name)
        assertEquals(9, result.release.versionCode)
        assertEquals(0, environment.settings.appUpdatePreferredSourceIndex.getValue())
        assertTrue("github.cnxiaobai.com" in environment.requests.first())
    }

    @Test
    fun failingPrimaryProxyFallsBackToNextProxy() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            failingSources = setOf("github.cnxiaobai.com"),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("gh.halonice.com", result.release.source.name)
        assertEquals(1, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun allProxiesFailFallsBackToGitHubDirect() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            failingSources = PROXY_SOURCE_NAMES.toSet(),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("GitHub", result.release.source.name)
        assertEquals(6, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun htmlResponseFromMirrorFallsBackToNextMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            htmlSources = setOf("github.cnxiaobai.com"),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("gh.halonice.com", result.release.source.name)
        assertEquals(1, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun allSourcesFailReturnsFailedWithEverySourceName() = runBlocking {
        val environment = environment(
            failingSources = ALL_SOURCE_NAMES.toSet(),
        )

        val result = assertIs<AppUpdateCheckResult.Failed>(environment.repository.checkForUpdate(force = true))

        ALL_SOURCE_NAMES.forEach { sourceName ->
            assertTrue(sourceName in result.message)
        }
    }

    @Test
    fun staleMirrorDoesNotMaskNewerMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            preferredIndex = 1,
            manifests = mapOf(
                "gh.halonice.com" to readyManifest(7),
                "ghproxy.sakuramoe.dev" to readyManifest(9),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("ghproxy.sakuramoe.dev", result.release.source.name)
        assertEquals(9, result.release.versionCode)
        assertEquals(2, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun allSourcesStaleReturnsUpToDate() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            manifests = ALL_SOURCE_NAMES.associateWith { readyManifest(7) },
        )

        val result = assertIs<AppUpdateCheckResult.UpToDate>(environment.repository.checkForUpdate(force = true))
        assertEquals("0.1.6", result.currentVersionName)
        assertEquals(0, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun upToDateRemembersSuccessfulMirrorForNextCheck() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            preferredIndex = 0,
            failingSources = PROXY_SOURCE_NAMES.toSet(),
            manifests = mapOf(
                "GitHub" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpToDate>(environment.repository.checkForUpdate(force = true))
        assertEquals("0.1.6", result.currentVersionName)
        assertEquals(6, environment.settings.appUpdatePreferredSourceIndex.getValue())
        assertTrue(environment.requests.any { "raw.githubusercontent.com" in it })

        val requestsBeforeSecondCheck = environment.requests.size
        assertIs<AppUpdateCheckResult.UpToDate>(environment.repository.checkForUpdate(force = true))
        assertTrue("raw.githubusercontent.com" in environment.requests[requestsBeforeSecondCheck])
    }

    @Test
    fun preparingRemembersSourceForNextCheck() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            preferredIndex = 6,
            manifests = mapOf(
                "GitHub" to readyManifest(7),
                "github.cnxiaobai.com" to notReadyManifest(9),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.Preparing>(environment.repository.checkForUpdate(force = true))
        assertEquals("github.cnxiaobai.com", result.sourceName)
        assertEquals(0, environment.settings.appUpdatePreferredSourceIndex.getValue())

        val requestsBeforeSecondCheck = environment.requests.size
        assertIs<AppUpdateCheckResult.Preparing>(environment.repository.checkForUpdate(force = true))
        assertTrue("github.cnxiaobai.com" in environment.requests[requestsBeforeSecondCheck])
    }

    @Test
    fun ignoredVersionSkipsToNewerMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 7,
            ignoredVersionCode = 8,
            manifests = mapOf(
                "github.cnxiaobai.com" to readyManifest(8),
                "gh.halonice.com" to readyManifest(9),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = false))

        assertEquals(9, result.release.versionCode)
        assertEquals("gh.halonice.com", result.release.source.name)
    }

    @Test
    fun ignoredWithoutNewerVersionWinsOverStaleMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 7,
            ignoredVersionCode = 8,
            manifests = mapOf(
                "github.cnxiaobai.com" to readyManifest(8),
                "gh.halonice.com" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.Ignored>(environment.repository.checkForUpdate(force = false))

        assertEquals(8, result.release.versionCode)
        assertEquals("github.cnxiaobai.com", result.release.source.name)
    }

    @Test
    fun preparingNewerVersionWinsOverStaleReadyMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            manifests = mapOf(
                "github.cnxiaobai.com" to notReadyManifest(9),
                "gh.halonice.com" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.Preparing>(environment.repository.checkForUpdate(force = true))

        assertEquals(9, result.versionCode)
        assertEquals("github.cnxiaobai.com", result.sourceName)
    }

    private fun environment(
        currentVersionCode: Long = 8,
        preferredIndex: Int = 0,
        ignoredVersionCode: Int? = null,
        failingSources: Set<String> = emptySet(),
        htmlSources: Set<String> = emptySet(),
        manifests: Map<String, String> = ALL_SOURCE_NAMES.associateWith { readyManifest(9) },
    ): TestAppUpdateEnvironment {
        val settingsStore = MemorySettingsStore()
        val settings = AppSettingsRepository(settingsStore)
        settings.appUpdatePreferredSourceIndex.setValue(preferredIndex)
        ignoredVersionCode?.let { settings.appUpdateIgnoredVersionCode.setValue(it) }

        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requests += url
            val sourceName = ALL_SOURCE_NAMES.firstOrNull { name ->
                if (name == "GitHub") "raw.githubusercontent.com" in url else name in url
            }
            when {
                sourceName == null -> respond("{}", HttpStatusCode.NotFound)
                "/changelogs/" in url -> respond("changelog", HttpStatusCode.NotFound)
                sourceName in failingSources -> respond("{}", HttpStatusCode.InternalServerError)
                sourceName in htmlSources -> respond(
                    "<html><body>mirror error</body></html>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
                else -> respond(
                    manifests[sourceName] ?: "{}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val platform = FakePlatform(currentVersionCode)
        val repository = DefaultAppUpdateRepository(
            appSettingsRepository = settings,
            platform = platform,
            httpClient = HttpClient(engine),
        )
        return TestAppUpdateEnvironment(settings, repository, requests)
    }

    private data class TestAppUpdateEnvironment(
        val settings: AppSettingsRepository,
        val repository: DefaultAppUpdateRepository,
        val requests: List<String>,
    )

    private class FakePlatform(override val currentVersionCode: Long) : AppUpdatePlatform {
        override val currentVersionName = "0.1.6"
        override val platformKey = "android"
        override val supportedAssetTypes = setOf("universal-apk")

        override suspend fun downloadAndInstall(
            release: AppUpdateRelease,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        ): AppUpdateDownloadState = AppUpdateDownloadState.Completed(release)

        override fun cancelDownload() = Unit
        override fun openReleasePage(url: String) = Unit
    }

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, String>()

        override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }

        override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
        override fun putFloat(key: String, value: Float) {
            values[key] = value.toString()
        }

        override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key]?.toBooleanStrictOrNull() ?: defaultValue

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value.toString()
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun hasKey(key: String): Boolean = key in values
    }
}

private fun readyManifest(
    versionCode: Int,
    assetUrl: String = "https://github.com/lmc2007/yamibo-app/releases/download/$versionCode/yamibo.apk",
): String =
    """{"channel":"stable","versionName":"0.1.$versionCode","versionCode":$versionCode,"isReady":true,"assets":[{"type":"universal-apk","url":"$assetUrl","sha256":"abc","size":100}]}"""

private fun notReadyManifest(versionCode: Int): String =
    """{"channel":"stable","versionName":"0.1.$versionCode","versionCode":$versionCode,"isReady":false,"assets":[]}"""
