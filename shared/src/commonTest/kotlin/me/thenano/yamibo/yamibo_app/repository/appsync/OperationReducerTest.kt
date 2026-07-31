package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence

class OperationReducerTest {
    private val reducer = OperationReducer()

    @Test
    fun causalSuccessorWinsRegardlessOfWallClock() {
        val first = operation(
            device = "a",
            sequence = 1,
            timestamp = 9_999_999,
            fields = mapOf("value" to "old"),
        )
        val second = operation(
            device = "b",
            sequence = 1,
            timestamp = 1,
            fields = mapOf("value" to "new"),
            context = SyncCausalContext().advance(first.replicaKey, first.sequence),
        )

        val result = reducer.reduce(operations = listOf(second, first))

        assertEquals("new", result.entities.values.single().fields.getValue("value").value)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun concurrentScalarUsesStableWinnerAndRetainsLoser() {
        val left = operation(device = "a", sequence = 1, fields = mapOf("value" to "left"))
        val right = operation(device = "b", sequence = 1, fields = mapOf("value" to "right"))

        val forward = reducer.reduce(operations = listOf(left, right))
        val reverse = reducer.reduce(operations = listOf(right, left))

        assertEquals(
            forward.entities.values.single().fields.getValue("value").value,
            reverse.entities.values.single().fields.getValue("value").value,
        )
        assertEquals(1, forward.conflicts.size)
        assertEquals(1, reverse.conflicts.size)
    }

    @Test
    fun concurrentDifferentFieldsAreBothRetained() {
        val title = operation(device = "a", sequence = 1, fields = mapOf("title" to "A"))
        val note = operation(device = "b", sequence = 1, fields = mapOf("note" to "B"))

        val entity = reducer.reduce(operations = listOf(title, note)).entities.values.single()

        assertEquals("A", entity.fields.getValue("title").value)
        assertEquals("B", entity.fields.getValue("note").value)
    }

    @Test
    fun concurrentRelationRemoveWins() {
        val add = operation(
            device = "a",
            sequence = 1,
            domain = "favorite.item-category",
            kind = SyncOperationKind.RelationAdd,
            fields = relationFields(),
        )
        val remove = operation(
            device = "b",
            sequence = 1,
            domain = "favorite.item-category",
            kind = SyncOperationKind.RelationRemove,
            fields = relationFields(),
        )

        val result = reducer.reduce(operations = listOf(add, remove))

        assertFalse(result.entities.values.single().relationPresent ?: true)
        assertEquals(1, result.conflicts.size)
    }

    @Test
    fun oldPutCannotResurrectTombstone() {
        val put = operation(device = "a", sequence = 1, fields = mapOf("value" to "live"))
        val delete = operation(
            device = "a",
            sequence = 2,
            kind = SyncOperationKind.Delete,
            fields = emptyMap(),
            context = SyncCausalContext().advance(put.replicaKey, put.sequence),
        )

        val entity = reducer.reduce(operations = listOf(delete, put)).entities.values.single()

        assertTrue(entity.fields.isEmpty())
        assertEquals(delete.operationId, entity.tombstone?.operationId)
    }

    @Test
    fun concurrentHistoryClearWinsOverProgressUpdate() {
        val original = operation(
            device = "origin",
            sequence = 1,
            domain = "reading.tag-catalog",
            entity = "42",
            kind = SyncOperationKind.Put,
            fields = tagCatalogHistoryFields(lastVisitTime = 10),
        )
        val observedOriginal = SyncCausalContext().advance(original.replicaKey, original.sequence)
        val progress = operation(
            device = "progress",
            sequence = 1,
            domain = "reading.tag-catalog",
            entity = "42",
            kind = SyncOperationKind.Patch,
            fields = mapOf("tagId" to "42", "lastVisitTime" to "20"),
            context = observedOriginal,
        )
        val clear = operation(
            device = "clear",
            sequence = 1,
            domain = "reading.tag-catalog",
            entity = "42",
            kind = SyncOperationKind.Delete,
            fields = emptyMap(),
            context = observedOriginal,
        )

        val entity = reducer.reduce(operations = listOf(original, progress, clear)).entities.values.single()

        assertEquals(clear.operationId, entity.tombstone?.operationId)
        assertTrue(entity.fields.isEmpty())
    }

    @Test
    fun duplicateOperationIsAppliedOnce() {
        val operation = operation(device = "a", sequence = 1, fields = mapOf("value" to "x"))

        val result = reducer.reduce(operations = listOf(operation, operation, operation))

        assertEquals(setOf(operation.operationId), result.appliedOperationIds)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun unknownDomainIsQuarantinedWithoutBlockingValidOperation() {
        val valid = operation(device = "a", sequence = 1, fields = mapOf("value" to "x"))
        val unknown = operation(
            device = "b",
            sequence = 1,
            domain = "future.domain",
            fields = mapOf("value" to "future"),
        )

        val result = reducer.reduce(operations = listOf(unknown, valid))

        assertEquals(1, result.quarantined.size)
        assertTrue(valid.operationId in result.appliedOperationIds)
    }

    @Test
    fun concurrentReadingTimeUsesMonotonicDuration() {
        val longer = operation(
            device = "a",
            sequence = 1,
            domain = "reading.time",
            entity = "2026-07-30",
            fields = mapOf("durationMillis" to "120"),
        )
        val laterOperationIdButShorter = operation(
            device = "z",
            sequence = 1,
            domain = "reading.time",
            entity = "2026-07-30",
            fields = mapOf("durationMillis" to "10"),
        )

        val result = reducer.reduce(operations = listOf(longer, laterOperationIdButShorter))

        assertEquals(
            "120",
            result.entities.values.single().fields.getValue("durationMillis").value,
        )
    }

    @Test
    fun newerGenerationRequiresPutAndOldGenerationCannotReturn() {
        val original = operation(
            device = "a",
            sequence = 1,
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to "old"),
        )
        val recreated = operation(
            device = "b",
            sequence = 1,
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to "new"),
            generation = 2,
        )

        val result = reducer.reduce(operations = listOf(recreated, original))

        assertEquals(2L, result.entities.keys.single().generation)
        assertEquals("new", result.entities.values.single().fields.getValue("value").value)
    }

    @Test
    fun categoryRenameKeepsStableIdentity() {
        val created = operation(
            device = "a",
            sequence = 1,
            domain = "favorite.category",
            entity = "stable-category-id",
            kind = SyncOperationKind.Put,
            fields = categoryFields("Original"),
        )
        val renamed = operation(
            device = "a",
            sequence = 2,
            domain = "favorite.category",
            entity = "stable-category-id",
            fields = mapOf("name" to "Renamed"),
            context = SyncCausalContext().advance(created.replicaKey, created.sequence),
        )

        val result = reducer.reduce(operations = listOf(renamed, created))

        assertEquals(1, result.entities.size)
        assertEquals("Renamed", result.entities.values.single().fields.getValue("name").value)
    }

    @Test
    fun sameNameCategoriesRemainDistinctEntities() {
        val first = operation(
            device = "a",
            sequence = 1,
            domain = "favorite.category",
            entity = "category-a",
            kind = SyncOperationKind.Put,
            fields = categoryFields("Same"),
        )
        val second = operation(
            device = "b",
            sequence = 1,
            domain = "favorite.category",
            entity = "category-b",
            kind = SyncOperationKind.Put,
            fields = categoryFields("Same"),
        )

        val result = reducer.reduce(operations = listOf(first, second))

        assertEquals(setOf("category-a", "category-b"), result.entities.keys.map { it.entityId.value }.toSet())
    }

    private fun operation(
        device: String,
        sequence: Long,
        domain: String = "settings",
        entity: String = "theme",
        kind: SyncOperationKind = SyncOperationKind.Patch,
        fields: Map<String, String?>,
        context: SyncCausalContext = SyncCausalContext(),
        timestamp: Long = 100,
        generation: Long = 1,
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
            entityGeneration = generation,
            kind = kind,
            fields = fields,
            causalContext = context,
            createdAtEpochMillis = timestamp,
            origin = if (kind == SyncOperationKind.Delete || kind == SyncOperationKind.RelationRemove) {
                SyncOperationOrigin.UserAction
            } else {
                SyncOperationOrigin.UserAction
            },
        )
    }

    private fun tagCatalogHistoryFields(lastVisitTime: Long) = mapOf(
        "tagId" to "42",
        "tagName" to "tag",
        "tagPage" to "1",
        "threadId" to "2",
        "threadTitle" to "thread",
        "threadPage" to "1",
        "postId" to "3",
        "postTitle" to "post",
        "authorId" to null,
        "anchorPostId" to "3",
        "anchorPostRatio" to null,
        "anchorBlockId" to null,
        "anchorBlockType" to null,
        "anchorBlockRatio" to null,
        "viewportHeight" to null,
        "firstVisibleItemIndex" to null,
        "firstVisibleItemOffset" to null,
        "lastVisitTime" to lastVisitTime.toString(),
        "coverUrl" to null,
    )

    private fun relationFields() = mapOf(
        "targetType" to "thread",
        "targetId" to "1",
        "authorId" to "2",
        "categorySyncId" to "category-1",
    )

    private fun categoryFields(name: String) = mapOf(
        "name" to name,
        "sortOrder" to "0",
        "createdAt" to "1",
        "updatedAt" to "1",
    )
}
