package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.backup.BackupReadingState
import me.thenano.yamibo.yamibo_app.repository.backup.BackupSetting
import me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType
import me.thenano.yamibo.yamibo_app.repository.backup.BackupThreadReadingHistory
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

class AppSyncPayloadFilteringTest {
    @Test
    fun threadCoverSanitizerOnlyAcceptsHttpAndHttpsLinks() {
        assertEquals("http://example.com/a.jpg", appSyncThreadCoverOrNull("http://example.com/a.jpg"))
        assertEquals("https://example.com/a.jpg", appSyncThreadCoverOrNull("https://example.com/a.jpg"))
        assertEquals("HTTPS://example.com/a.jpg", appSyncThreadCoverOrNull(" HTTPS://example.com/a.jpg "))
        assertNull(appSyncThreadCoverOrNull("data:image/png;base64,AAAA"))
        assertNull(appSyncThreadCoverOrNull("http://data:image/png;base64,AAAA"))
        assertNull(appSyncThreadCoverOrNull("content://cover/1"))
        assertNull(appSyncThreadCoverOrNull("file:///cover.jpg"))
        assertNull(appSyncThreadCoverOrNull("not-a-link"))
        assertNull(appSyncThreadCoverOrNull("https://example.com/has space.jpg"))
        assertNull(appSyncThreadCoverOrNull(null))
    }

    @Test
    fun portableSnapshotDropsLegacyDataCoverFromRollbackPayload() {
        val sanitized = YamiboBackupFile(
            appVersionCode = 1,
            createdAt = 1,
            readingState = BackupReadingState(
                threadHistory = listOf(threadHistory("http://data:image/png;base64,AAAA")),
            ),
        ).withPortableAppSyncPayloads()

        assertNull(sanitized.readingState.threadHistory.single().threadCover)
    }

    @Test
    fun bootstrapSnapshotExcludesSignCacheAndSanitizesThreadCover() {
        val snapshot = YamiboBackupFile(
            appVersionCode = 1,
            createdAt = 1,
            settings = listOf(
                BackupSetting("appsettings.signpagehtmlcache", BackupSettingType.String, "<html>cache</html>"),
                BackupSetting("appsettings.signpagehtmlcacheupdatedat", BackupSettingType.String, "123"),
                BackupSetting("theme", BackupSettingType.String, "dark"),
            ),
            readingState = BackupReadingState(
                threadHistory = listOf(threadHistory("http://data:image/png;base64,AAAA")),
            ),
        )

        val drafts = BackupSnapshotMigrationPlanner().plan(snapshot)

        assertEquals(setOf("theme"), drafts.filter { it.domainId.value == "settings" }.mapTo(mutableSetOf()) { it.entityId.value })
        assertNull(drafts.single { it.domainId.value == "reading.thread" }.fields["threadCover"])
    }

    @Test
    fun bootstrapSnapshotPreservesValidHttpThreadCover() {
        val cover = "https://example.com/cover.jpg"
        val snapshot = YamiboBackupFile(
            appVersionCode = 1,
            createdAt = 1,
            readingState = BackupReadingState(threadHistory = listOf(threadHistory(cover))),
        )

        val draft = BackupSnapshotMigrationPlanner().plan(snapshot).single()

        assertEquals(cover, draft.fields["threadCover"])
    }

    private fun threadHistory(threadCover: String?) = BackupThreadReadingHistory(
        threadId = 1,
        threadType = "Normal",
        threadName = "thread",
        threadCover = threadCover,
        forumName = null,
        forumId = null,
        authorId = 0,
        page = 1,
        postId = 1,
        postTitle = "post",
        anchorPostId = 1,
        anchorPostRatio = null,
        anchorBlockId = null,
        anchorBlockType = null,
        anchorBlockRatio = null,
        globalScrollY = null,
        viewportHeight = null,
        firstVisibleItemIndex = null,
        firstVisibleItemOffset = null,
        lastVisitTime = 1,
        lastUpdatedTime = null,
    )
}
