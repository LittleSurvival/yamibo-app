package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteUpdateCategoryFilter
import me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteUpdateEvent
import me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteUpdateFidFilter
import me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteUpdates
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation

class FavoriteUpdateSyncDomainTest {
    @Test
    fun concurrentReadAndDismissMarkersBothPersist() {
        val put = eventPut("a", 1)
        val base = SyncCausalContext().advance(put.replicaKey, put.sequence)
        val read = operation(
            "b", 1, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("readAt" to "200"), base,
        )
        val dismiss = operation(
            "c", 1, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("dismissedAt" to "300"), base,
        )

        val result = OperationReducer().reduce(operations = listOf(dismiss, put, read))
        val entity = result.entities.values.single()

        assertEquals("200", entity.fields.getValue("readAt").value)
        assertEquals("300", entity.fields.getValue("dismissedAt").value)
        assertTrue(result.quarantined.isEmpty())
    }

    @Test
    fun deleteRemainsRemoveWinsWhenOldPutIsReplayed() {
        val put = eventPut("a", 1)
        val delete = operation(
            "a", 2, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Delete, emptyMap(),
            SyncCausalContext().advance(put.replicaKey, put.sequence),
        )

        val result = OperationReducer().reduce(operations = listOf(delete, put, put))

        assertNotNull(result.entities.values.single().tombstone)
    }

    @Test
    fun malformedLifecycleAndLocalCategoryIdAreQuarantined() {
        val put = eventPut("a", 1)
        val badPatch = operation(
            "a", 2, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("readAt" to null),
        )
        val badCategory = operation(
            "b", 1, "favorite.update-category-filter", "category:stable",
            SyncOperationKind.Put,
            mapOf("categorySyncId" to "stable", "categoryId" to "7", "enabled" to "true"),
        )

        val result = OperationReducer().reduce(operations = listOf(put, badPatch, badCategory))

        assertEquals(2, result.quarantined.size)
    }

    @Test
    fun filterRaceConvergesRegardlessOfDeliveryOrder() {
        val enabled = operation(
            "a", 1, "favorite.update-fid-filter", "fid:7",
            SyncOperationKind.Put, mapOf("fid" to "7", "enabled" to "true"),
        )
        val disabled = operation(
            "b", 1, "favorite.update-fid-filter", "fid:7",
            SyncOperationKind.Put, mapOf("fid" to "7", "enabled" to "false"),
        )
        val reducer = OperationReducer()

        val first = reducer.reduce(operations = listOf(enabled, disabled))
        val second = reducer.reduce(operations = listOf(disabled, enabled))

        assertEquals(
            first.entities.values.single().fields.getValue("enabled").value,
            second.entities.values.single().fields.getValue("enabled").value,
        )
    }

    @Test
    fun clockSkewCannotOverrideCausalOrOperationIdOrdering() {
        val oldClock = operation(
            "a", 1, "favorite.update-fid-filter", "fid:7",
            SyncOperationKind.Put, mapOf("fid" to "7", "enabled" to "true"),
            createdAtEpochMillis = 9_000,
        )
        val causalSuccessor = operation(
            "b", 1, "favorite.update-fid-filter", "fid:7",
            SyncOperationKind.Patch, mapOf("fid" to "7", "enabled" to "false"),
            context = SyncCausalContext().advance(oldClock.replicaKey, oldClock.sequence),
            createdAtEpochMillis = 1,
        )
        val concurrentLowClock = operation(
            "c", 1, "favorite.update-fid-filter", "fid:8",
            SyncOperationKind.Put, mapOf("fid" to "8", "enabled" to "true"),
            createdAtEpochMillis = 9_000,
        )
        val concurrentHighId = operation(
            "z", 1, "favorite.update-fid-filter", "fid:8",
            SyncOperationKind.Put, mapOf("fid" to "8", "enabled" to "false"),
            createdAtEpochMillis = 1,
        )

        val state = OperationReducer().reduce(
            operations = listOf(
                concurrentHighId,
                causalSuccessor,
                concurrentLowClock,
                oldClock,
            ),
        ).entities.values.associateBy { it.key.entityId.value }
        val concurrentWinner = maxOf(
            concurrentLowClock,
            concurrentHighId,
            compareBy { it.operationId.value },
        )

        assertEquals("false", state.getValue("fid:7").fields.getValue("enabled").value)
        assertEquals(
            concurrentWinner.fields.getValue("enabled"),
            state.getValue("fid:8").fields.getValue("enabled").value,
        )
    }

    @Test
    fun backupProjectionCreatesExactlyOneDraftPerDurableFavoriteUpdateEntity() {
        val put = eventPut("a", 1)
        val fields = put.fields
        val snapshot = YamiboBackupFile(
            appVersionCode = 5,
            createdAt = 100,
            favoriteUpdates = BackupFavoriteUpdates(
                events = listOf(
                    BackupFavoriteUpdateEvent(
                        syncId = put.entityId.value,
                        sourceFingerprint = fields.getValue("sourceFingerprint")!!,
                        sourceDiscriminator = fields.getValue("sourceDiscriminator")!!,
                        targetType = fields.getValue("targetType")!!,
                        targetId = 42,
                        authorId = null,
                        fid = 1,
                        forumName = "forum",
                        title = "title",
                        latestPostTitle = "post",
                        mode = "NormalThread",
                        summary = "new",
                        detailIds = listOf(100),
                        coverUrl = null,
                        detectedAt = 100,
                        readAt = 200,
                        dismissedAt = null,
                        ambiguous = false,
                    ),
                ),
                fidFilters = listOf(BackupFavoriteUpdateFidFilter(1, false)),
                categoryFilters = listOf(
                    BackupFavoriteUpdateCategoryFilter("category-sync", true),
                ),
            ),
        )

        val drafts = BackupSnapshotMigrationPlanner().plan(snapshot)
            .filter { it.domainId.value.startsWith("favorite.update-") }

        assertEquals(3, drafts.size)
        assertEquals(3, drafts.map { it.domainId to it.entityId }.distinct().size)
        assertTrue(drafts.none { it.fields.keys.any { key -> key.contains("run", true) } })
    }

    @Test
    fun checkpointRejectsFavoriteUpdateProjectionMismatch() {
        val put = eventPut("a", 1)
        val read = operation(
            "a", 2, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("readAt" to "200"),
            SyncCausalContext().advance(put.replicaKey, put.sequence),
        )
        val entities = OperationReducer().reduce(operations = listOf(put, read)).entities.values
        val fields = put.fields
        val staleSnapshot = YamiboBackupFile(
            appVersionCode = 5,
            createdAt = 100,
            favoriteUpdates = BackupFavoriteUpdates(
                events = listOf(
                    BackupFavoriteUpdateEvent(
                        syncId = put.entityId.value,
                        sourceFingerprint = fields.getValue("sourceFingerprint")!!,
                        sourceDiscriminator = fields.getValue("sourceDiscriminator")!!,
                        targetType = "ThreadNormal",
                        targetId = 42,
                        authorId = null,
                        fid = 1,
                        forumName = "forum",
                        title = "title",
                        latestPostTitle = "post",
                        mode = "NormalThread",
                        summary = "new",
                        detailIds = listOf(100),
                        coverUrl = null,
                        detectedAt = 100,
                        readAt = null,
                        dismissedAt = null,
                        ambiguous = false,
                    ),
                ),
            ),
        )
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload(
            checkpointId = "checkpoint",
            accountBinding = SyncAccountBinding("account"),
            coverage = SyncCausalContext(),
            snapshot = staleSnapshot,
            resolvedEntities = entities,
            tombstones = emptyList(),
            createdAtEpochMillis = 100,
        )

        val result = codec.validate(codec.encode(payload))

        assertTrue(result is AppSyncCheckpointValidation.Invalid)
    }

    @Test
    fun fiveDeviceDuplicateAndReorderedDeliveryConverges() {
        val detectors = listOf("a", "b", "c").map { eventPut(it, 1) }
        val entityId = detectors.first().entityId.value
        val read = operation(
            "d", 1, "favorite.update-event", entityId,
            SyncOperationKind.Patch, mapOf("readAt" to "200"),
        )
        val dismiss = operation(
            "e", 1, "favorite.update-event", entityId,
            SyncOperationKind.Patch, mapOf("dismissedAt" to "300"),
        )
        val operations = detectors + read + dismiss + detectors.first()
        val reducer = OperationReducer()

        val forward = reducer.reduce(operations = operations).entities.values.single()
        val reverse = reducer.reduce(operations = operations.reversed()).entities.values.single()
        val interleaved = reducer.reduce(
            operations = listOf(read, detectors[2], dismiss, detectors[0], detectors[1]),
        ).entities.values.single()

        fun values(entity: me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity) =
            entity.fields.mapValues { it.value.value }
        assertEquals(values(forward), values(reverse))
        assertEquals(values(forward), values(interleaved))
        assertEquals("200", values(forward)["readAt"])
        assertEquals("300", values(forward)["dismissedAt"])
    }

    private fun eventPut(device: String, sequence: Long): SyncOperation {
        val identity = favoriteUpdateEventIdentity(
            targetType = "ThreadNormal",
            targetId = 42,
            authorId = null,
            mode = "NormalThread",
            detailIds = listOf(100),
            ambiguous = false,
            detectedAt = 100,
            summary = "new",
            title = "title",
            sourceDiscriminator = "post:100:revision:500",
        )
        return operation(
            device = device,
            sequence = sequence,
            domain = "favorite.update-event",
            entity = identity.syncId,
            kind = SyncOperationKind.Put,
            fields = mapOf(
                "targetType" to "ThreadNormal",
                "targetId" to "42",
                "authorId" to "0",
                "fid" to "1",
                "forumName" to "forum",
                "title" to "title",
                "latestPostTitle" to "post",
                "mode" to "NormalThread",
                "summary" to "new",
                "detailIds" to "100",
                "coverUrl" to null,
                "detectedAt" to "100",
                "ambiguous" to "false",
                "sourceFingerprint" to identity.sourceFingerprint,
                "sourceDiscriminator" to identity.sourceDiscriminator,
            ),
        )
    }

    private fun operation(
        device: String,
        sequence: Long,
        domain: String,
        entity: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        context: SyncCausalContext = SyncCausalContext(),
        createdAtEpochMillis: Long = 100,
    ): SyncOperation {
        val deviceId = SyncDeviceId(device)
        val epoch = SyncDeviceEpoch("epoch")
        val syncSequence = SyncSequence(sequence)
        return SyncOperation(
            operationId = SyncOperation.idFor(deviceId, epoch, syncSequence),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = syncSequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId(domain),
            entityId = SyncEntityId(entity),
            kind = kind,
            fields = fields,
            causalContext = context,
            createdAtEpochMillis = createdAtEpochMillis,
            origin = SyncOperationOrigin.UserAction,
        )
    }
}
