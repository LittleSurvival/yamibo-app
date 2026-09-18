package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields

class AppSyncSanitizedV2OperationExporterTest {
    @Test fun omittedOversizedDisplayTitleDoesNotBecomeAnEmptyPortableWinner() {
        val source = corpus.journal.operations.first { it.domainId.value == "favorite.item" }
        val canonical = imported(source.copy(fields = source.fields + ("title" to "標題".repeat(300)))).operation
        assertFalse(6 in canonical.fields)
        val exported = assertIs<AppSyncV2OperationExport.Ready>(exporter.export(
            AppSyncCanonicalOperationBlock(account, listOf(canonical)))).operations.single()
        assertFalse("title" in exported.fields)
        assertEquals(canonical, imported(exported).operation)
        val invalid = source.copy(fields = source.fields - "targetId")
        assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(AppSyncCanonicalOperationImporter().import(account, invalid))
    }

    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private val account get() = corpus.journal.accountBinding.value
    private val exporter = AppSyncSanitizedV2OperationExporter()
    private fun imported(source: SyncOperation) = assertIs<AppSyncCanonicalOperationImport.Accepted>(
        AppSyncCanonicalOperationImporter().import(account, source))

    @Test fun corpusExportsWithoutRestoringExcludedValuesOrChangingOperationEvidence() {
        val operations = corpus.journal.operations
        val blocked = mutableSetOf<String>()
        for (original in operations) {
            val canonical = imported(original)
            val block = AppSyncCanonicalOperationBlock(account, listOf(canonical.operation), listOfNotNull(canonical.proof))
            when (val result = exporter.export(block)) {
                is AppSyncV2OperationExport.NeedsAttention -> {
                    assertEquals(AppSyncV2ExportFailure.LegacyContract, result.reason)
                    blocked += original.domainId.value
                }
                is AppSyncV2OperationExport.Ready -> {
                    val restored = result.operations.single()
                    assertEquals(original.operationId, restored.operationId)
                    assertEquals(canonical.operation, imported(restored).operation)
                    assertEquals(canonical.proof, imported(restored).proof)
                    val domain = AppSyncCanonicalSchema.domainsById.getValue(canonical.operation.domainId)
                    domain.fields.values.filter { it.classification in setOf(AppSyncFieldClass.Cache,
                        AppSyncFieldClass.ParentJoinable, AppSyncFieldClass.DeviceLocal) }.forEach {
                        assertFalse(it.name in restored.fields, "${domain.name}.${it.name}")
                    }
                }
            }
        }
        assertEquals(emptySet(), blocked)
        assertEquals(operations, corpus.journal.operations)
    }

    @Test fun patchesAndDeletesPreserveExactAuthorityWithoutAddingStaleBodies() {
        val original = corpus.journal.operations.first { it.domainId.value == "favorite.item" }
        val patch = imported(original.copy(kind = SyncOperationKind.Patch, fields = mapOf("title" to "Updated")))
        val patched = assertIs<AppSyncV2OperationExport.Ready>(exporter.export(
            AppSyncCanonicalOperationBlock(account, listOf(patch.operation)))).operations.single()
        assertEquals(mapOf("title" to "Updated"), patched.fields)
        val sourceDelete = original.copy(kind = SyncOperationKind.Delete, origin = SyncOperationOrigin.UserAction,
            bulkDeleteAuthorizationId = "confirmed-batch", fields = original.fields + mapOf(
                AppSyncBulkDeleteProofFields.SCOPE to "all",
                AppSyncBulkDeleteProofFields.COUNT to "1",
                AppSyncBulkDeleteProofFields.EXPIRES_AT to (original.createdAtEpochMillis + 1000).toString()))
        val delete = imported(sourceDelete)
        val deleted = assertIs<AppSyncV2OperationExport.Ready>(exporter.export(AppSyncCanonicalOperationBlock(
            account, listOf(delete.operation), listOfNotNull(delete.proof)))).operations.single()
        assertEquals(setOf(AppSyncBulkDeleteProofFields.SCOPE, AppSyncBulkDeleteProofFields.COUNT,
            AppSyncBulkDeleteProofFields.EXPIRES_AT), deleted.fields.keys)
        assertEquals(delete.proof, imported(deleted).proof)
        assertEquals(sourceDelete.operationId, deleted.operationId)
        assertIs<AppSyncV2OperationExport.NeedsAttention>(exporter.export(
            AppSyncCanonicalOperationBlock(account, listOf(delete.operation))))
    }

    @Test fun malformedCanonicalBatchNeverReturnsPartialOperationsOrPayloadDiagnostics() {
        val original = imported(corpus.journal.operations.first { it.domainId.value == "favorite.item" }).operation
        val invalid = original.copy(sequence = original.sequence + 1, domainId = 999,
            entityId = "private-title-must-not-escape")
        val result = assertIs<AppSyncV2OperationExport.NeedsAttention>(exporter.export(
            AppSyncCanonicalOperationBlock(account, listOf(original, invalid))))
        assertEquals(AppSyncV2ExportFailure.InvalidCanonical, result.reason)
        assertFalse(result.toString().contains("private-title"))
    }

    @Test fun portableAmbiguousEventEvidenceRequiresNewReaderInsteadOfReintroducingLegacyText() {
        val event = corpus.journal.operations.first { it.domainId.value == "favorite.update-event" }
        val fields = event.fields
        val identity = me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity(
            fields.getValue("targetType")!!, fields.getValue("targetId")!!.toLong(), fields.getValue("authorId")!!.toLong(),
            fields.getValue("mode")!!, emptyList(), true, fields.getValue("detectedAt")!!.toLong(),
            fields.getValue("summary")!!, fields.getValue("title")!!)
        val canonical = imported(event.copy(entityId = SyncEntityId(identity.syncId), fields = fields + mapOf(
            "sourceDiscriminator" to identity.sourceDiscriminator, "sourceFingerprint" to identity.sourceFingerprint,
            "detailIds" to "", "ambiguous" to "true"))).operation
        val result = assertIs<AppSyncV2OperationExport.NeedsAttention>(exporter.export(
            AppSyncCanonicalOperationBlock(account, listOf(canonical))))
        assertEquals(AppSyncV2ExportFailure.ReaderCompatibility, result.reason)
    }
}
