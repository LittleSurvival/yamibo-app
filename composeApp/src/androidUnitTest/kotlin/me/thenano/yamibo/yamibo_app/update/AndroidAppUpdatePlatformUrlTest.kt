package me.thenano.yamibo.yamibo_app.update

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAppUpdatePlatformUrlTest {

    @Test
    fun githubReleaseAssetPrefersGhProxyThenDirect() {
        val url = "https://github.com/lmc2007/yamibo-app/releases/download/8/yamibo-stable-v0.1.6.apk"

        assertEquals(
            listOf("https://gh-proxy.com/$url", url),
            resolveApkDownloadUrls(url),
        )
    }

    @Test
    fun githubusercontentObjectAssetPrefersGhProxyThenDirect() {
        val url = "https://objects.githubusercontent.com/github-production-release/asset/123.apk"

        assertEquals(
            listOf("https://gh-proxy.com/$url", url),
            resolveApkDownloadUrls(url),
        )
    }

    @Test
    fun giteeAssetStaysDirectOnly() {
        val url = "https://gitee.com/LittleSurvival/ymb-apk-release/releases/download/8/yamibo.apk"

        assertEquals(listOf(url), resolveApkDownloadUrls(url))
    }

    @Test
    fun giteaAssetStaysDirectOnly() {
        val url = "https://gitea.com/LittleSurvival/ymb-apk-release/releases/download/8/yamibo.apk"

        assertEquals(listOf(url), resolveApkDownloadUrls(url))
    }

    @Test
    fun blankUrlHasNoCandidates() {
        assertEquals(emptyList(), resolveApkDownloadUrls("  "))
    }

    @Test
    fun nonHttpUrlIsUsedAsIs() {
        val url = "content://updates/yamibo.apk"

        assertEquals(listOf(url), resolveApkDownloadUrls(url))
    }
}
