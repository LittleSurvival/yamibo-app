package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncVerifiedCanonicalCheckpointTest {
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
            index(references = listOf(reference, reference.copy(checkpointId = "duplicate-blog"))))
            .forEach { assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", 123, it, body)) }
        listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1).forEach {
            assertNull(AppSyncVerifiedCanonicalCheckpoint.verify("account", it, index(), body))
        }
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
