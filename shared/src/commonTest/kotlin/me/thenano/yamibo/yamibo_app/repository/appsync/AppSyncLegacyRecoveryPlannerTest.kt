package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyOperationClassifier
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRemoteEvidence
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

class AppSyncLegacyRecoveryPlannerTest {
    private val classifier = AppSyncLegacyOperationClassifier()
    private val planner = AppSyncLegacyRecoveryPlanner()

    @Test
    fun classifierProducesPresentAbsentAndUnknownEvidencePerOperationId() {
        val cache = operation(1, "settings", "appsettings.signpagehtmlcache", fields = mapOf("value" to "secret"))
        val cover = operation(2, "reading.thread", "thread", fields = mapOf("threadCover" to "data:image/png;base64,secret"))

        val unknown = classifier.classify(listOf(cache, cover), emptySet(), false)
        assertTrue(unknown.all { it.evidence == AppSyncLegacyRemoteEvidence.Unknown })
        val authoritative = classifier.classify(
            listOf(cache, cover), setOf(cache.operationId), true,
        ).associateBy { it.operation.operationId }
        assertEquals(AppSyncLegacyRemoteEvidence.VerifiedPresent, authoritative[cache.operationId]?.evidence)
        assertEquals(AppSyncLegacyRemoteEvidence.VerifiedAbsent, authoritative[cover.operationId]?.evidence)
        assertTrue(authoritative.values.all { it.requiresRecovery })
    }

    @Test
    fun publishedLocalOnlyValuesProduceContentFreeCompensatingOperations() {
        val cache = operation(1, "settings", "appsettings.signpagehtmlcache", fields = mapOf("value" to "secret-html"))
        val cover = operation(
            2, "reading.thread", "thread",
            fields = mapOf("threadCover" to "data:image/png;base64,secret", "page" to "2"),
        )
        val classifications = classifier.classify(
            listOf(cache, cover), setOf(cache.operationId, cover.operationId), true,
        )

        val plan = planner.plan(classifications)

        val cacheCleanup = plan.replacements.single { it.source == cache }
        assertEquals(SyncOperationKind.Delete, cacheCleanup.kind)
        assertTrue(cacheCleanup.fields.isEmpty())
        val coverCleanup = plan.replacements.single { it.source == cover }
        assertEquals(SyncOperationKind.Patch, coverCleanup.kind)
        assertEquals(setOf("threadCover"), coverCleanup.fields.keys)
        assertNull(coverCleanup.fields["threadCover"])
        assertTrue(plan.replacements.none { replacement ->
            replacement.fields.values.any { it?.contains("secret") == true }
        })
    }

    @Test
    fun verifiedAbsentDestructiveAndRelationOperationsRetainExactSemantics() {
        val delete = operation(
            1, "detail-note", "note", kind = SyncOperationKind.Delete,
            fields = emptyMap(), entityGeneration = 7, bulkDeleteAuthorizationId = "bulk-proof",
        )
        val relationRemove = operation(
            2, "favorite.item-category", "relation", kind = SyncOperationKind.RelationRemove,
            fields = mapOf("targetId" to "1", "categorySyncId" to "category"),
            entityGeneration = 3,
        )
        val classifications = classifier.classify(
            listOf(delete, relationRemove), emptySet(), true,
        ).map { it.copy(requiresRecovery = true) }

        val replacements = planner.plan(classifications).replacements.associateBy { it.source }

        assertEquals(SyncOperationKind.Delete, replacements[delete]?.kind)
        assertEquals(delete.fields, replacements[delete]?.fields)
        assertEquals(SyncOperationKind.RelationRemove, replacements[relationRemove]?.kind)
        assertEquals(relationRemove.fields, replacements[relationRemove]?.fields)
    }

    @Test
    fun unknownEvidenceAndOversizedPortableEntityStopAutomaticStaging() {
        val unknown = operation(1, "settings", "appsettings.backupfolderuri", fields = mapOf("value" to "content://local"))
        val oversized = operation(
            2, "detail-note", "note", fields = mapOf("content" to "x".repeat(140 * 1024)),
        )
        val classifications = classifier.classify(listOf(unknown, oversized), emptySet(), false)
        val plan = planner.plan(classifications)
        assertEquals(setOf(unknown.operationId, oversized.operationId), plan.unknownOperationIds)

        val authoritative = planner.plan(
            classifier.classify(listOf(oversized), emptySet(), true),
        )
        assertEquals(1, authoritative.needsAttention.size)
        assertTrue(authoritative.replacements.isEmpty())
    }

    private fun operation(
        sequenceValue: Long,
        domain: String,
        entity: String,
        kind: SyncOperationKind = SyncOperationKind.Patch,
        fields: Map<String, String?>,
        entityGeneration: Long = 1,
        bulkDeleteAuthorizationId: String? = null,
    ): SyncOperation {
        val deviceId = SyncDeviceId("device")
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(sequenceValue)
        return SyncOperation(
            SyncOperation.idFor(deviceId, epoch, sequence), deviceId, epoch, sequence,
            SyncAccountBinding("account"), SyncDomainId(domain), SyncEntityId(entity),
            entityGeneration, kind, fields, SyncCausalContext(), 100 + sequenceValue,
            SyncOperationOrigin.UserAction, bulkDeleteAuthorizationId,
        )
    }
}
