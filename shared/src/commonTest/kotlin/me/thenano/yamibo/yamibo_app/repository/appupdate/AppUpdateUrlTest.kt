package me.thenano.yamibo.yamibo_app.repository.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppUpdateUrlTest {
    @Test fun desktopFailureNeverContactsMirrorsAndHasManualFallback() = runBlocking {
        val requests = mutableListOf<String>()
        val platform = UpdateTestPlatform("windows")
        val settings = AppSettingsRepository(UpdateTestSettings())
        settings.appUpdatePreferredSourceIndex.setValue(2)
        HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond("unavailable", HttpStatusCode.ServiceUnavailable)
        }).use { client ->
            val repository = DefaultAppUpdateRepository(settings, platform, httpClient = client)
            assertIs<AppUpdateCheckResult.Failed>(repository.checkForUpdate(true))
            assertEquals(1, requests.size)
            assertTrue(requests.single().contains("/desktop-update-release/"))
            assertEquals(2, settings.appUpdatePreferredSourceIndex.getValue())
            repository.openManualDownloadPage()
            assertEquals("https://github.com/LittleSurvival/yamibo-app/releases", platform.opened)
        }
    }

    @Test fun androidRetainsMirrorFallback() = runBlocking {
        val requests = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            requests += request.url.host
            if (request.url.host == "gitee.com") respond("""{"versionName":"1","versionCode":1,"isReady":false}""")
            else respond("unavailable", HttpStatusCode.ServiceUnavailable)
        }).use { client ->
            val repository = DefaultAppUpdateRepository(AppSettingsRepository(UpdateTestSettings()), UpdateTestPlatform("android"), httpClient = client)
            assertIs<AppUpdateCheckResult.UpToDate>(repository.checkForUpdate(true))
            assertEquals(listOf("raw.githubusercontent.com", "gitee.com", "gitea.com"), requests)
            assertEquals(null, repository.manualDownloadUrl)
        }
    }

    @Test fun callerCancellationIsNotConvertedIntoCheckFailure() = runBlocking {
        HttpClient(MockEngine { awaitCancellation() }).use { client ->
            val repository = DefaultAppUpdateRepository(AppSettingsRepository(UpdateTestSettings()), UpdateTestPlatform("windows"), httpClient = client)
            assertFailsWith<TimeoutCancellationException> { withTimeout(100) { repository.checkForUpdate(true) } }
            Unit
        }
    }

    @Test fun desktopSelectsNativeAssetAndRejectsForeignReleaseLink() = runBlocking {
        val platform = UpdateTestPlatform("windows")
        HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith(".changelog")) respond("notes") else respond("""
                {"versionName":"2","versionCode":2,"isReady":true,"releaseUrl":"https://mirror.invalid/download",
                "assets":[{"platform":"android","type":"apk","url":"wrong"},
                {"platform":"windows","type":"msi","url":"native"}]}
            """.trimIndent())
        }).use { client ->
            val repository = DefaultAppUpdateRepository(AppSettingsRepository(UpdateTestSettings()), platform, httpClient = client)
            val release = assertIs<AppUpdateCheckResult.UpdateAvailable>(repository.checkForUpdate(true)).release
            assertEquals("native", release.asset?.url)
            assertEquals("notes", release.changelogText)
            repository.openReleasePage(release)
            assertEquals(repository.manualDownloadUrl, platform.opened)
        }
    }

    @Test
    fun testResolveChangelogUrl() {
        // GitHub URL format
        val githubUrl = "https://raw.githubusercontent.com/LittleSurvival/yamibo-app/update-release/update/stable.json"
        assertEquals(
            "https://raw.githubusercontent.com/LittleSurvival/yamibo-app/update-release/update/changelogs/3.changelog",
            resolveChangelogUrl(githubUrl, 3)
        )

        // Gitee URL format
        val giteeUrl = "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/stable.json"
        assertEquals(
            "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/changelogs/3.changelog",
            resolveChangelogUrl(giteeUrl, 3)
        )

        // Gitea URL format (with query params)
        val giteaUrl = "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/stable.json?ref=main"
        assertEquals(
            "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/changelogs/3.changelog?ref=main",
            resolveChangelogUrl(giteaUrl, 3)
        )
    }
}

private class UpdateTestPlatform(override val platformKey: String) : AppUpdatePlatform {
    override val currentVersionCode = 1L
    override val currentVersionName = "1"
    override val supportedAssetTypes = setOf("msi")
    var opened: String? = null
    override fun openReleasePage(url: String) { opened = url }
    override fun cancelDownload() = Unit
    override suspend fun downloadAndInstall(release: AppUpdateRelease, onProgress: (Long, Long?) -> Unit) = AppUpdateDownloadState.Idle
}

private class UpdateTestSettings : SettingsStore {
    private val values = mutableMapOf<String, Any>()
    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun hasKey(key: String) = key in values
}
