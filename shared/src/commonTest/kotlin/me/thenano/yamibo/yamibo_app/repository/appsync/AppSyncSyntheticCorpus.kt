package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.backup.*
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId

/** Entirely generated data. No export, account, clock, randomness, device or network dependency. */
internal object AppSyncSyntheticCorpus {
    const val TIMESTAMP = 1_800_000_000_000L
    val account = SyncAccountBinding("synthetic-account")
    val device = SyncDeviceId("synthetic-device")
    val epoch = SyncDeviceEpoch("synthetic-epoch")

    enum class Size(val favorites: Int, val history: Int, val noteBytes: Int) {
        Small(8, 12, 256), Typical(300, 600, 8 * 1024), Oversized(5_000, 10_000, 128 * 1024),
    }

    data class Corpus(
        val snapshot: YamiboBackupFile,
        val journal: AppSyncJournalPayload,
        val resolved: List<ResolvedSyncEntity>,
    ) {
        fun checkpoint() = AppSyncCheckpointEnvelopeCodec().createPayload(
            "synthetic-checkpoint", account, journal.observed, snapshot, resolved, emptyList(), TIMESTAMP,
        )
    }

    fun create(size: Size = Size.Small): Corpus {
        val snapshot = snapshot(size)
        val drafts = BackupSnapshotMigrationPlanner().plan(snapshot)
        val operations = drafts.mapIndexed { index, draft ->
            val sequence = SyncSequence(index + 1L)
            SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence, account,
                draft.domainId, draft.entityId, kind = draft.kind, fields = draft.fields,
                causalContext = if (index == 0) SyncCausalContext() else
                    SyncCausalContext().advance(SyncReplicaKey(device, epoch), SyncSequence(index.toLong())),
                createdAtEpochMillis = TIMESTAMP + index, origin = SyncOperationOrigin.Migration)
        }.toMutableList()
        // Include actual edit history, not only a fresh snapshot import. The final live state
        // still matches the snapshot: the older position is patched, a relation is removed and
        // re-added, and a temporary note leaves a tombstone without entering the live snapshot.
        val readingIndex = operations.indexOfFirst { it.domainId.value == "reading.thread" }
        val reading = operations[readingIndex]
        operations[readingIndex] = reading.copy(fields = reading.fields + ("page" to "1"))
        fun append(source: SyncOperation, kind: SyncOperationKind, fields: Map<String, String?>) {
            val sequence = SyncSequence(operations.size + 1L)
            operations += source.copy(
                operationId = SyncOperation.idFor(device, epoch, sequence), sequence = sequence,
                kind = kind, fields = fields,
                causalContext = SyncCausalContext().advance(SyncReplicaKey(device, epoch),
                    SyncSequence(sequence.value - 1)),
                createdAtEpochMillis = TIMESTAMP + sequence.value - 1,
                origin = SyncOperationOrigin.UserAction,
            )
        }
        append(reading, SyncOperationKind.Patch, mapOf("page" to reading.fields.getValue("page")))
        val relation = operations.first { it.kind == SyncOperationKind.RelationAdd }
        // V2 relation removal still requires identity fields; v3 will move these into its key.
        append(relation, SyncOperationKind.RelationRemove, relation.fields)
        append(relation, SyncOperationKind.RelationAdd, relation.fields)
        val deletedNote = operations.first { it.domainId.value == "detail-note" }.copy(
            entityId = SyncEntityId("ThreadNormal|999999|0"),
            fields = mapOf("targetType" to "ThreadNormal", "targetId" to "999999", "authorId" to "0",
                "content" to "Synthetic deleted note", "createdAt" to TIMESTAMP.toString(),
                "updatedAt" to TIMESTAMP.toString()),
        )
        append(deletedNote, SyncOperationKind.Put, deletedNote.fields)
        append(deletedNote, SyncOperationKind.Delete, emptyMap())
        // This corpus has independent entities; group before reduction to avoid quadratic fixture
        // construction masking the actual codec cost in the 25k-operation benchmark case.
        val resolved = operations.groupBy { it.domainId to it.entityId }.values.flatMap { group ->
            val reduced = OperationReducer().reduce(operations = group)
            check(reduced.quarantined.isEmpty()) { "Synthetic corpus failed its production domain contracts" }
            reduced.entities.values
        }
        val coverage = SyncCausalContext().advance(SyncReplicaKey(device, epoch), operations.last().sequence)
        return Corpus(snapshot, AppSyncJournalPayload(account, device, epoch, SyncWriterNonce("synthetic-writer"),
            1, operations.size.toLong(), operations, coverage, heartbeatAtEpochMillis = TIMESTAMP),
            resolved)
    }

    private fun snapshot(size: Size): YamiboBackupFile {
        val query = "synthetic query 百合"
        val subscription = rssSearchSubscriptionSyncId(query, null)
        val event = favoriteUpdateEventIdentity("ThreadNormal", 1, 0, "NormalThread", listOf(101, 102),
            false, TIMESTAMP, "Two synthetic updates", "Synthetic event")
        return YamiboBackupFile(appVersionCode = 1, createdAt = TIMESTAMP,
            favorites = BackupFavorites(
                categories = listOf(BackupFavoriteCategory(1, "synthetic-category", "分類", 0, TIMESTAMP, TIMESTAMP)),
                collections = listOf(BackupFavoriteCollection(1, "synthetic-collection", 1, "Collection", "brown", 0, TIMESTAMP, TIMESTAMP)),
                items = (1..size.favorites).map { id ->
                    BackupFavoriteItem(id.toLong(), "ThreadNormal", id.toLong(), title(id),
                        "https://example.test/cover/$id", TIMESTAMP, 1, "Synthetic forum", 0, TIMESTAMP, TIMESTAMP)
                },
                rssSubscriptions = listOf(BackupRssSearchSubscription(1, subscription, "Synthetic RSS", query, null,
                    "Synthetic forum", true, TIMESTAMP, TIMESTAMP)),
                itemCategories = (1..size.favorites).map { BackupFavoriteItemCategory(it.toLong(), 1, TIMESTAMP) },
                itemCollections = (1..size.favorites).map { BackupFavoriteItemCollection(it.toLong(), 1, TIMESTAMP) },
            ),
            settings = listOf(
                BackupSetting("appsettings.thememode", BackupSettingType.Enum, "DARK"),
                BackupSetting("appsettings.ismangamode", BackupSettingType.Bool, "true"),
                BackupSetting("novelreadersettings.fontsize", BackupSettingType.Int, "18"),
                BackupSetting("novelreadersettings.linespacing", BackupSettingType.Float, "1.5"),
                BackupSetting("appsettings.appfontid", BackupSettingType.String, "synthetic-font"),
            ),
            notes = listOf(BackupDetailNote("ThreadNormal", 1, 0, note(size.noteBytes), TIMESTAMP, TIMESTAMP)),
            bookmarks = listOf(BackupBookMark("ThreadNormal", 1, 101, "Synthetic bookmark", true, false, TIMESTAMP, TIMESTAMP)),
            readingState = BackupReadingState(
                threadHistory = (1..size.history).map { id -> BackupThreadReadingHistory(
                    threadId = id.toLong(), threadType = "Normal", threadName = title(id),
                    threadCover = "https://example.test/history/$id", forumName = "Synthetic forum", forumId = 1,
                    authorId = 0, page = id % 20 + 1L, postId = id + 100L, postTitle = "Refetchable post",
                    anchorPostId = id + 100L, anchorPostRatio = if (id % 2 == 0) 0.5 else null,
                    anchorBlockId = "block-$id", anchorBlockType = "text", anchorBlockRatio = 0.25,
                    globalScrollY = id * 10L, viewportHeight = 800, firstVisibleItemIndex = id % 10L,
                    firstVisibleItemOffset = id % 32L, lastVisitTime = TIMESTAMP + id, lastUpdatedTime = null,
                ) },
                imageHistory = listOf(BackupImageReadingHistory(101, 1, 2, 12, null, null, TIMESTAMP)),
                tagMangaHistory = listOf(BackupTagMangaReadingHistory(10, "Synthetic tag", 1, 1, title(1),
                    2, 12, 1, 10, TIMESTAMP, "https://example.test/tag")),
                tagCatalogHistory = listOf(BackupTagCatalogReadingHistory(11, "Synthetic catalog", 1, 1,
                    title(1), 2, 101, "Post", null, 101, 0.5, "block", "text", 0.25, 800, 1, 10, TIMESTAMP, null)),
                rssSearchHistory = listOf(BackupRssSearchReadingHistory(1, "Synthetic RSS", query, 1, 1,
                    title(1), 2, 12, 1, 10, TIMESTAMP, null)),
                rssCatalogHistory = listOf(BackupRssCatalogReadingHistory(1, "Synthetic RSS", query, 1, 1,
                    title(1), 2, 101, "Post", null, 101, 0.5, "block", "text", 0.25, 800, 1, 10, TIMESTAMP, null)),
                readingTimeStats = listOf(BackupReadingTimeStat("2026-09-17", 60_000, TIMESTAMP)),
            ),
            favoriteUpdates = BackupFavoriteUpdates(
                events = listOf(BackupFavoriteUpdateEvent(event.syncId, event.sourceFingerprint, event.sourceDiscriminator,
                    "ThreadNormal", 1, 0, 1, "Synthetic forum", "Synthetic event", "Latest post", "NormalThread",
                    "Two synthetic updates", listOf(101, 102), "https://example.test/event", TIMESTAMP, null, null, false)),
                fidFilters = listOf(BackupFavoriteUpdateFidFilter(1, true)),
                categoryFilters = listOf(BackupFavoriteUpdateCategoryFilter("synthetic-category", true)),
            ),
        )
    }

    private fun title(index: Int) = "Synthetic 百合 $index / ${(index.toLong() * 2_654_435_761L).toString(16)}"
    private fun note(bytes: Int): String = buildString(bytes) {
        var index = 0
        while (length < bytes) {
            val line = "Synthetic note ${index++}: ${(index.toLong() * 2_654_435_761L).toString(16)}\n"
            append(line.take(bytes - length))
        }
    }
}
