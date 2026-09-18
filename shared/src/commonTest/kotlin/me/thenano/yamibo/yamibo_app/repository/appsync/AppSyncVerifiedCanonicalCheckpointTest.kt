package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncVerifiedCanonicalCheckpointTest {
    @Test fun loadedDocumentEvidenceChecksOriginalIndexAndCannotReuseMetadataAfterMutation() {
        val read = assertIs<AppSyncV3DocumentRead.Checkpoint>(AppSyncV3DocumentCodec().readCheckpoint(body, "account", "checkpoint"))
        val html = "<pre>${index()}</pre>"
        val verified = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verifyDocument("account", 123, html, read))
        assertEquals(checkpoint, verified.document)
        assertNull(AppSyncVerifiedCanonicalCheckpoint.verifyDocument("account", 123, html,
            read.copy(document = checkpoint.copy(createdAtEpochMillis = 999))))
        assertNull(AppSyncVerifiedCanonicalCheckpoint.verifyDocument("account", 123, html,
            read.copy(metadata = read.metadata.copy(uncompressedLength = 0))))
        assertNull(AppSyncVerifiedCanonicalCheckpoint.verifyDocument("account", 123, html,
            read.copy(metadata = read.metadata.copy(codecVersion = 9))))
        assertNull(AppSyncVerifiedCanonicalCheckpoint.verifyDocument("account", 124, html, read))
    }

    private val checkpoint = AppSyncCanonicalCheckpoint("checkpoint", "account", 1, emptyMap(), emptyList())
    private val body = AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint)
    private val fingerprint = AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex()
    private val reference = AppSyncIndexCheckpointReference("checkpoint", 123, fingerprint)
    private fun index(account: String = "account", references: List<AppSyncIndexCheckpointReference> = listOf(reference)) =
        AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(SyncAccountBinding(account),
            checkpoints = references, updatedAtEpochMillis = 2))

    @Test fun exactIndexedArtifactCreatesEvidenceWithBothFingerprints() {
        val index = index()
        val verified = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", 123, index, body))
        assertEquals(checkpoint, verified.document)
        assertEquals(123L, verified.blogId)
        assertEquals(fingerprint, verified.fingerprint)
        assertEquals(assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(index)).envelope.fingerprint,
            verified.indexFingerprint)
    }

    @Test fun mismatchedOrAmbiguousReferencesCannotAuthorizeActivation() {
        listOf(index("other"), index(references = emptyList()),
            index(references = listOf(reference.copy(blogId = 124))),
            index(references = listOf(reference.copy(checkpointId = "other"))),
            index(references = listOf(reference.copy(fingerprint = "0".repeat(64)))),
            conflictingIndex())
            .forEach { assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", 123, it, body)) }
        listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1).forEach {
            assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", it, index(), body))
        }
    }

    // Hostile remote input must bypass the production writer, which now rejects conflicts.
    private fun conflictingIndex(): String {
        val payload = AppSyncIndexPayload(SyncAccountBinding("account"),
            checkpoints = listOf(reference, reference.copy(checkpointId = "duplicate-blog")), updatedAtEpochMillis = 2)
        val json = Json.encodeToString(AppSyncIndexPayload.serializer(), payload)
        val marker = AppSyncJournalDefaults.INDEX_MARKER
        return "[$marker:BEGIN]\nschema=${AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION}\nfingerprint=${stableAppSyncFingerprint(json)}\npayload=$json\n[$marker:END]"
    }

    @Test fun unsupportedCorruptWrongAccountAndLegacyBodiesNeverProduceCanonicalEvidence() {
        val corpus = AppSyncSyntheticCorpus.create()
        listOf(body.replace("codec=1", "codec=2"), body.replace("length=", "length=9"),
            AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint.copy(accountBinding = "other")),
            AppSyncCheckpointEnvelopeCodec().encode(corpus.checkpoint()))
            .forEach { assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", 123, index(), it)) }
        assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", 123, "invalid index", body))
    }
}
