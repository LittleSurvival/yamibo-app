package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.backup.BackupFavorites
import me.thenano.yamibo.yamibo_app.repository.backup.BackupReadingState
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRssSearchReadingHistory
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRssSearchSubscription
import me.thenano.yamibo.yamibo_app.repository.backup.BackupTagCatalogReadingHistory
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

class BackupSnapshotHistoryMigrationTest {
    @Test
    fun emptyAndRepeatedSeedPlanningAreStable() {
        val empty = YamiboBackupFile(appVersionCode = 1, createdAt = 10)
        assertTrue(BackupSnapshotMigrationPlanner().plan(empty).isEmpty())

        val populated = empty.copy(
            readingState = BackupReadingState(tagCatalogHistory = listOf(tagCatalog())),
        )
        val planner = BackupSnapshotMigrationPlanner()
        assertEquals(planner.plan(populated), planner.plan(populated))
    }

    @Test
    fun seedUsesStableHistoryIdentitiesAndSkipsUnresolvedRssParents() {
        val snapshot = YamiboBackupFile(
            appVersionCode = 1,
            createdAt = 10,
            favorites = BackupFavorites(
                rssSubscriptions = listOf(
                    BackupRssSearchSubscription(
                        localId = 7,
                        syncId = "rss:stable-parent",
                        title = "RSS",
                        query = "query",
                        forumId = null,
                        forumName = null,
                        enabled = true,
                        createdAt = 1,
                        updatedAt = 2,
                    ),
                ),
            ),
            readingState = BackupReadingState(
                tagCatalogHistory = listOf(tagCatalog()),
                rssSearchHistory = listOf(rssSearch(7), rssSearch(999)),
            ),
        )

        val plan = BackupSnapshotMigrationPlanner().planWithDiagnostics(snapshot)
        val drafts = plan.drafts
        val historyDrafts = drafts.filter { it.domainId.value.startsWith("reading.") }

        assertEquals(
            listOf("reading.rss-search|rss:stable-parent", "reading.tag-catalog|42"),
            historyDrafts.map { "${it.domainId.value}|${it.entityId.value}" }.sorted(),
        )
        assertTrue(historyDrafts.none { it.entityId.value == "999" })
        assertEquals(1, plan.skippedOrphanRssHistoryCount)
    }

    private fun tagCatalog() = BackupTagCatalogReadingHistory(
        tagId = 42,
        tagName = "tag",
        tagPage = 1,
        threadId = 2,
        threadTitle = "thread",
        threadPage = 1,
        postId = 3,
        postTitle = "post",
        authorId = null,
        anchorPostId = 3,
        anchorPostRatio = null,
        anchorBlockId = null,
        anchorBlockType = null,
        anchorBlockRatio = null,
        viewportHeight = null,
        firstVisibleItemIndex = null,
        firstVisibleItemOffset = null,
        lastVisitTime = 10,
        coverUrl = null,
    )

    private fun rssSearch(subscriptionId: Long) = BackupRssSearchReadingHistory(
        subscriptionId = subscriptionId,
        subscriptionTitle = "RSS",
        subscriptionQuery = "query",
        subscriptionPage = 1,
        threadId = 2,
        threadTitle = "thread",
        threadImagePageIndex = 1,
        threadImageTotalPages = 3,
        firstVisibleItemIndex = null,
        firstVisibleItemOffset = null,
        lastVisitTime = 10,
        coverUrl = null,
    )
}
