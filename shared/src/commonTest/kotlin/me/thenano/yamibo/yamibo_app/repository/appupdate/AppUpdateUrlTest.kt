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
}
