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

class DefaultAppUpdateRepositoryTest {

    @Test
    fun sourcesAreGitHubDirectThenThirdPartyGitHubMirrors() {
        val environment = environment()

        assertEquals(
            listOf("GitHub", "gh-proxy.com", "ghfast.top", "gh.llkk.cc", "gh.ddlc.top"),
            environment.repository.sources.map { it.name },
        )
        assertEquals(
            "https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            environment.repository.sources[0].manifestUrl,
        )
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            environment.repository.sources[1].manifestUrl,
        )
        assertEquals(
            "https://ghfast.top/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            environment.repository.sources[2].manifestUrl,
        )
        assertEquals(
            "https://gh.llkk.cc/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            environment.repository.sources[3].manifestUrl,
        )
        assertEquals(
            "https://gh.ddlc.top/https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json",
            environment.repository.sources[4].manifestUrl,
        )
    }

    @Test
    fun failingGitHubFallsBackToGhProxyMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            failingSources = setOf("GitHub"),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("gh-proxy.com", result.release.source.name)
        assertEquals(9, result.release.versionCode)
        assertEquals(1, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun htmlResponseFromMirrorFallsBackToNextMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            failingSources = setOf("GitHub"),
            htmlSources = setOf("gh-proxy.com"),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("ghfast.top", result.release.source.name)
        assertEquals(2, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun allSourcesFailReturnsFailedWithEverySourceName() = runBlocking {
        val environment = environment(
            failingSources = setOf("GitHub", "gh-proxy.com", "ghfast.top", "gh.llkk.cc", "gh.ddlc.top"),
        )

        val result = assertIs<AppUpdateCheckResult.Failed>(environment.repository.checkForUpdate(force = true))

        assertTrue("GitHub" in result.message)
        assertTrue("gh-proxy.com" in result.message)
        assertTrue("ghfast.top" in result.message)
        assertTrue("gh.llkk.cc" in result.message)
        assertTrue("gh.ddlc.top" in result.message)
    }

    @Test
    fun staleMirrorDoesNotMaskNewerMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            preferredIndex = 1,
            manifests = mapOf(
                "gh-proxy.com" to readyManifest(7),
                "ghfast.top" to readyManifest(9),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = true))

        assertEquals("ghfast.top", result.release.source.name)
        assertEquals(9, result.release.versionCode)
        assertEquals(2, environment.settings.appUpdatePreferredSourceIndex.getValue())
    }

    @Test
    fun allSourcesStaleReturnsUpToDate() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            manifests = mapOf(
                "GitHub" to readyManifest(7),
                "gh-proxy.com" to readyManifest(7),
                "ghfast.top" to readyManifest(7),
                "gh.llkk.cc" to readyManifest(7),
                "gh.ddlc.top" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpToDate>(environment.repository.checkForUpdate(force = true))
        assertEquals("0.1.6", result.currentVersionName)
    }

    @Test
    fun ignoredVersionSkipsToNewerMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 7,
            ignoredVersionCode = 8,
            manifests = mapOf(
                "GitHub" to readyManifest(8),
                "gh-proxy.com" to readyManifest(9),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.UpdateAvailable>(environment.repository.checkForUpdate(force = false))

        assertEquals(9, result.release.versionCode)
        assertEquals("gh-proxy.com", result.release.source.name)
    }

    @Test
    fun ignoredWithoutNewerVersionWinsOverStaleMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 7,
            ignoredVersionCode = 8,
            manifests = mapOf(
                "GitHub" to readyManifest(8),
                "gh-proxy.com" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.Ignored>(environment.repository.checkForUpdate(force = false))

        assertEquals(8, result.release.versionCode)
        assertEquals("GitHub", result.release.source.name)
    }

    @Test
    fun preparingNewerVersionWinsOverStaleReadyMirror() = runBlocking {
        val environment = environment(
            currentVersionCode = 8,
            manifests = mapOf(
                "GitHub" to notReadyManifest(9),
                "gh-proxy.com" to readyManifest(7),
            ),
        )

        val result = assertIs<AppUpdateCheckResult.Preparing>(environment.repository.checkForUpdate(force = true))

        assertEquals(9, result.versionCode)
        assertEquals("GitHub", result.sourceName)
    }

    private fun environment(
        currentVersionCode: Long = 8,
        preferredIndex: Int = 0,
        ignoredVersionCode: Int? = null,
        failingSources: Set<String> = emptySet(),
        htmlSources: Set<String> = emptySet(),
        manifests: Map<String, String> = mapOf(
            "GitHub" to readyManifest(9),
            "gh-proxy.com" to readyManifest(9),
            "ghfast.top" to readyManifest(9),
            "gh.llkk.cc" to readyManifest(9),
            "gh.ddlc.top" to readyManifest(9),
        ),
    ): TestAppUpdateEnvironment {
        val settingsStore = MemorySettingsStore()
        val settings = AppSettingsRepository(settingsStore)
        settings.appUpdatePreferredSourceIndex.setValue(preferredIndex)
        ignoredVersionCode?.let { settings.appUpdateIgnoredVersionCode.setValue(it) }

        val engine = MockEngine { request ->
            val url = request.url.toString()
            // gh-proxy.com URL 内包含 raw.githubusercontent.com，镜像域名必须先匹配
            val sourceName = when {
                "gh-proxy.com" in url -> "gh-proxy.com"
                "ghfast.top" in url -> "ghfast.top"
                "gh.llkk.cc" in url -> "gh.llkk.cc"
                "gh.ddlc.top" in url -> "gh.ddlc.top"
                "raw.githubusercontent.com" in url -> "GitHub"
                else -> null
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
        return TestAppUpdateEnvironment(settings, repository)
    }

    private data class TestAppUpdateEnvironment(
        val settings: AppSettingsRepository,
        val repository: DefaultAppUpdateRepository,
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

    private fun readyManifest(
        versionCode: Int,
        assetUrl: String = "https://github.com/lmc2007/yamibo-app/releases/download/$versionCode/yamibo.apk",
    ): String =
        """{"channel":"stable","versionName":"0.1.$versionCode","versionCode":$versionCode,"isReady":true,"assets":[{"type":"universal-apk","url":"$assetUrl","sha256":"abc","size":100}]}"""

    private fun notReadyManifest(versionCode: Int): String =
        """{"channel":"stable","versionName":"0.1.$versionCode","versionCode":$versionCode,"isReady":false,"assets":[]}"""
}
