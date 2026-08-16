package me.thenano.yamibo.yamibo_app.repository.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateUrlTest {

    @Test
    fun testResolveChangelogUrl() {
        // GitHub URL format
        val githubUrl = "https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json"
        assertEquals(
            "https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/changelogs/3.changelog",
            resolveChangelogUrl(githubUrl, 3)
        )
    }

    @Test
    fun testResolveChangelogUrlForGiteeMirror() {
        val giteeUrl = "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/stable.json"
        assertEquals(
            "https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/changelogs/3.changelog",
            resolveChangelogUrl(giteeUrl, 3)
        )
    }

    @Test
    fun testResolveChangelogUrlKeepsGiteaQuery() {
        val giteaUrl = "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/stable.json?ref=main"
        assertEquals(
            "https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/changelogs/3.changelog?ref=main",
            resolveChangelogUrl(giteaUrl, 3)
        )
    }
}
