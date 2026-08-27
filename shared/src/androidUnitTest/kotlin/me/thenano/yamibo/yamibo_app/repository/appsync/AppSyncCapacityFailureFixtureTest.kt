package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPayloadBudget

class AppSyncCapacityFailureFixtureTest {
    @Test
    fun fixtureMatchesTracedScaleAndPreservesDestructiveOperationKinds() {
        val fixture = AppSyncCapacityFailureFixture.create()
        val operations = fixture.store.pendingOperations()
        val cacheOperations = operations.filter {
            it.entityId.value == "appsettings.signpagehtmlcache"
        }
        val historyCount = fixture.database.readingHistoryQueries.countAll().executeAsOne() +
            fixture.database.mangaTagReadingHistoryQueries.countAll().executeAsOne() +
            fixture.database.tagCatalogReadingHistoryQueries.countAll().executeAsOne() +
            fixture.database.rssSearchReadingHistoryQueries.countAll().executeAsOne() +
            fixture.database.rssCatalogReadingHistoryQueries.countAll().executeAsOne()

        assertEquals(5_000, fixture.database.localFavoriteItemQueries.getAll().executeAsList().size)
        assertTrue(historyCount >= 8_000)
        assertEquals(3, cacheOperations.size)
        assertTrue(cacheOperations.sumOf { it.fields["value"].orEmpty().length } > 1_000_000)
        assertTrue(operations.any { it.kind.name == "Delete" })
        assertTrue(operations.any { it.kind.name == "RelationRemove" })
        assertTrue(operations.any { it.entityId.value.endsWith("backupfolderuri") })
        assertTrue(operations.any { it.fields["threadCover"]?.startsWith("data:") == true })
    }

    @Test
    fun historicalThreadCoverDetectorMissesOversizedLocalOnlySettings() {
        val operations = AppSyncCapacityFailureFixture.create().store.pendingOperations()
        val signCache = operations.filter {
            it.entityId.value == "appsettings.signpagehtmlcache"
        }
        val historicallyDetected = signCache.filter { operation ->
            operation.fields["threadCover"]?.let(::appSyncThreadCoverOrNull) == null &&
                operation.fields.containsKey("threadCover")
        }

        assertEquals(3, signCache.size)
        assertTrue(signCache.sumOf { it.fields["value"].orEmpty().length } > 1_000_000)
        assertTrue(historicallyDetected.isEmpty())
        assertTrue(signCache.all {
            !AppSyncPortabilityPolicy.isSettingPortable(it.entityId.value)
        })
    }

    @Test
    fun portableFullSnapshotRebaseStillExceedsSingleProviderBody() {
        val fixture = AppSyncCapacityFailureFixture.create()
        val installation = requireNotNull(fixture.store.installation())
        val count = 5_000 + 10_000
        val operations = (1..count).map { index ->
            val sequence = SyncSequence(index.toLong())
            val favorite = index <= 5_000
            SyncOperation(
                operationId = SyncOperation.idFor(
                    installation.deviceId, installation.deviceEpoch, sequence,
                ),
                deviceId = installation.deviceId,
                deviceEpoch = installation.deviceEpoch,
                sequence = sequence,
                accountBinding = fixture.accountBinding,
                domainId = SyncDomainId(if (favorite) "favorite.item" else "reading.thread"),
                entityId = SyncEntityId("snapshot-$index"),
                entityGeneration = 1,
                kind = SyncOperationKind.Put,
                fields = if (favorite) {
                    mapOf(
                        "targetType" to "ThreadNormal",
                        "targetId" to (100_000 + index).toString(),
                        "authorId" to "0",
                        "title" to "Synthetic favorite ${index.toString().padStart(5, '0')}",
                    )
                } else {
                    mapOf(
                        "threadId" to (200_000 + index).toString(),
                        "threadType" to "Normal",
                        "threadName" to "Synthetic thread ${index.toString().padStart(5, '0')}",
                        "page" to (index % 20 + 1).toString(),
                        "lastVisitTime" to (1_800_000_000_000L - index).toString(),
                    )
                },
                causalContext = SyncCausalContext(),
                createdAtEpochMillis = 1_800_000_000_000L + index,
                origin = SyncOperationOrigin.Migration,
            )
        }
        val encoded = AppSyncJournalEnvelopeCodec().encode(
            AppSyncJournalPayload(
                accountBinding = fixture.accountBinding,
                deviceId = installation.deviceId,
                deviceEpoch = installation.deviceEpoch,
                writerNonce = installation.writerNonce,
                firstSequence = 1,
                lastSequence = count.toLong(),
                operations = operations,
                observed = SyncCausalContext(),
                heartbeatAtEpochMillis = 1_800_000_000_000L,
            ),
        )

        assertTrue(encoded.length > AppSyncPayloadBudget.HARD_LIMIT_CHARS)
    }
}
