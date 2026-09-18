package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity

class AppSyncCanonicalOperationImporterTest {
    private val importer = AppSyncCanonicalOperationImporter()
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private val account get() = corpus.journal.accountBinding.value
    private fun source(domain: String) = corpus.journal.operations.first { it.domainId.value == domain }

    @Test
    fun productionCorpusImportsAllDomainsAndPreservesConflictEvidenceWithoutMutatingSources() {
        val originals = corpus.journal.operations.toList()
        val imported = originals.map { source -> assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, source)) }
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, imported.map { it.operation.domainId }.toSet())
        originals.zip(imported).forEach { (before, after) ->
            val op = after.operation
            assertEquals(before.entityId.value, op.entityId)
            assertEquals(before.sequence.value, op.sequence)
            assertEquals(before.deviceId.value, op.deviceId)
            assertEquals(before.deviceEpoch.value, op.deviceEpoch)
            assertEquals(before.entityGeneration, op.generation)
            assertEquals(before.causalContext.asStableMap(), op.causalContext)
            assertEquals(before.createdAtEpochMillis, op.createdAtEpochMillis)
            assertEquals(before.origin, op.origin)
            if (op.kind in setOf(SyncOperationKind.Delete, SyncOperationKind.RelationRemove)) assertTrue(op.fields.isEmpty())
            val domain = AppSyncCanonicalSchema.domainsById.getValue(op.domainId)
            op.fields.keys.forEach { assertTrue(domain.fieldsById.getValue(it).portable) }
        }
        assertEquals(originals, corpus.journal.operations)
        val block = AppSyncCanonicalOperationBlock(account, imported.map { it.operation })
        val codec = AppSyncCanonicalOperationBlockCodec()
        assertEquals(block, codec.decode(account, codec.encode(block)))
    }

    @Test
    fun unknownAndNoOpResultsStayExplicitInsteadOfLosingSequenceCoverage() {
        val operation = source("reading.thread")
        assertIs<AppSyncCanonicalOperationImport.Excluded>(importer.import(account, operation.copy(domainId = SyncDomainId("future.domain"))))
        assertIs<AppSyncCanonicalOperationImport.Excluded>(importer.import(account, source("settings").copy(entityId = SyncEntityId("unknown.setting"))))
        val noOp = assertIs<AppSyncCanonicalOperationImport.NoOp>(importer.import(account,
            operation.copy(kind = SyncOperationKind.Patch, fields = mapOf("threadCover" to "https://example.test/private"))))
        assertEquals(AppSyncCanonicalIssueReason.Excluded, noOp.exclusions.single().reason)
        assertFalse(noOp.toString().contains("private"))
        assertEquals(AppSyncCanonicalImportFailure.AccountMismatch,
            assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import("other", operation)).failure)
    }

    @Test
    fun derivedFieldMismatchCannotSilentlyRedirectAnEntity() {
        val original = source("favorite.item")
        assertEquals(AppSyncCanonicalImportFailure.IdentityFieldMismatch,
            assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account,
                original.copy(fields = original.fields + ("targetId" to "999")))).failure)
        val equivalent = original.copy(fields = original.fields + ("targetId" to "0001"))
        assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, equivalent))
        assertEquals(AppSyncCanonicalImportFailure.InvalidIdentity,
            assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account,
                original.copy(entityId = SyncEntityId("malformed")))).failure)
        val setting = source("settings")
        assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, setting.copy(fields = setting.fields + ("type" to "string"))))
    }

    @Test
    fun budgetsOmitRefetchableTitlesButNeverTruncateEssentialNotes() {
        val favorite = source("favorite.item")
        val result = assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account,
            favorite.copy(fields = favorite.fields + ("title" to "中".repeat(200)))))
        assertFalse(6 in result.operation.fields)
        assertTrue(result.exclusions.any { it.fieldId == 6 && it.reason == AppSyncCanonicalIssueReason.FieldBudget })
        val note = source("detail-note")
        val failure = assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account,
            note.copy(fields = note.fields + ("content" to "x".repeat(128 * 1024 + 1)))))
        assertEquals(AppSyncCanonicalIssueReason.FieldBudget, failure.fieldIssue?.reason)
        assertNull(failure.failure)
    }

    @Test
    fun authorizedDeletesSeparateProofAndRejectMissingOrExpiredEvidence() {
        val deletion = source("reading.thread").copy(kind = SyncOperationKind.Delete, origin = SyncOperationOrigin.UserAction,
            bulkDeleteAuthorizationId = "proof", fields = mapOf(AppSyncBulkDeleteProofFields.SCOPE to "scope",
                AppSyncBulkDeleteProofFields.COUNT to "120", AppSyncBulkDeleteProofFields.EXPIRES_AT to Long.MAX_VALUE.toString(),
                "threadCover" to "private-obsolete-cover"))
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, deletion))
        assertTrue(imported.operation.fields.isEmpty())
        assertEquals(120L, imported.proof?.operationCount)
        for (fields in listOf(emptyMap(), deletion.fields + (AppSyncBulkDeleteProofFields.EXPIRES_AT to "0"))) {
            assertEquals(AppSyncCanonicalImportFailure.InvalidProof,
                assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account, deletion.copy(fields = fields))).failure)
        }
        assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account, deletion.copy(bulkDeleteAuthorizationId = null)))
    }

    @Test
    fun customEventDiscriminatorCannotBeSilentlyDiscardedAsDerivedData() {
        val event = source("favorite.update-event")
        assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, event))
        val fields = event.fields
        val identity = favoriteUpdateEventIdentity(requireNotNull(fields["targetType"]), requireNotNull(fields["targetId"]).toLong(),
            0, requireNotNull(fields["mode"]), listOf(101, 102), false, event.createdAtEpochMillis,
            requireNotNull(fields["summary"]), requireNotNull(fields["title"]), "custom-source-evidence")
        val custom = event.copy(entityId = SyncEntityId(identity.syncId), fields = fields + mapOf(
            "sourceFingerprint" to identity.sourceFingerprint, "sourceDiscriminator" to identity.sourceDiscriminator))
        val result = assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, custom))
        assertEquals(AppSyncCanonicalValue.Text("custom-source-evidence"), result.operation.fields[64])
        assertEquals("custom-source-evidence", custom.fields["sourceDiscriminator"])
        val codec = AppSyncCanonicalOperationBlockCodec()
        val block = AppSyncCanonicalOperationBlock(account, listOf(result.operation))
        assertEquals(block, codec.decode(account, codec.encode(block)))
        assertFalse(64 in assertIs<AppSyncCanonicalOperationImport.Accepted>(importer.import(account, event)).operation.fields)
    }

    @Test
    fun ambiguousDefaultIdentityRemainsProtectedUntilDigestMigrationExists() {
        val event = source("favorite.update-event")
        val fields = event.fields
        val identity = favoriteUpdateEventIdentity(requireNotNull(fields["targetType"]), requireNotNull(fields["targetId"]).toLong(),
            0, requireNotNull(fields["mode"]), emptyList(), true, requireNotNull(fields["detectedAt"]).toLong(),
            requireNotNull(fields["summary"]), requireNotNull(fields["title"]))
        val ambiguous = event.copy(entityId = SyncEntityId(identity.syncId), fields = fields + mapOf(
            "detailIds" to "", "ambiguous" to "true", "sourceFingerprint" to identity.sourceFingerprint,
            "sourceDiscriminator" to identity.sourceDiscriminator))
        assertEquals(AppSyncCanonicalImportFailure.NonReconstructibleEventIdentity,
            assertIs<AppSyncCanonicalOperationImport.NeedsAttention>(importer.import(account, ambiguous)).failure)
        assertEquals(identity.sourceDiscriminator, ambiguous.fields["sourceDiscriminator"])
    }
}
