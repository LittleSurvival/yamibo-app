package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexJournalReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexCheckpointReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexRetirementReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexValidation

class AppSyncIndexEnvelopeCodecTest {
    private val codec = AppSyncIndexEnvelopeCodec()

    @Test
    fun retirementReferencesRoundTripInStableOrder() {
        val payload = AppSyncIndexPayload(
            accountBinding = SyncAccountBinding("account"),
            retirements = listOf(reference("replica-b", 2), reference("replica-a", 1)),
            updatedAtEpochMillis = 100,
        )

        val valid = assertIs<AppSyncIndexValidation.Valid>(codec.validate(codec.encode(payload)))

        assertEquals(
            listOf("replica-a", "replica-b"),
            valid.envelope.payload.retirements.map { it.replicaKey },
        )
    }

    @Test
    fun invalidRetirementIdentityFailsClosed() {
        val payload = AppSyncIndexPayload(
            accountBinding = SyncAccountBinding("account"),
            retirements = listOf(reference("", 1)),
            updatedAtEpochMillis = 100,
        )

        val error = runCatching { codec.encode(payload) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("retirement"))
    }

    @Test
    fun retirementReferenceCountIsBounded() {
        val payload = AppSyncIndexPayload(
            accountBinding = SyncAccountBinding("account"),
            retirements = (1..129).map { reference("replica-$it", it) },
            updatedAtEpochMillis = 100,
        )

        val error = runCatching { codec.encode(payload) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("exceed"))
    }

    private fun reference(replica: String, blogId: Int) =
        AppSyncIndexRetirementReference(
            replicaKey = replica,
            blogId = blogId,
            fingerprint = "fingerprint-$blogId",
            publishedThroughSequence = blogId.toLong(),
            checkpointId = "checkpoint",
        )

    @Test fun conflictingLogicalAndPhysicalReferencesFailForBothWriterAndReader() {
        val journal = AppSyncIndexJournalReference("replica", 1, "sha")
        val checkpoint = AppSyncIndexCheckpointReference("checkpoint", 2, "sha")
        val base = AppSyncIndexPayload(SyncAccountBinding("account"), listOf(journal), listOf(checkpoint), updatedAtEpochMillis = 1)
        val conflicts = listOf(
            base.copy(journals = listOf(journal, journal.copy(blogId = 3))),
            base.copy(journals = listOf(journal, journal.copy(fingerprint = "other"))),
            base.copy(journals = listOf(journal, journal.copy(replicaKey = "other"))),
            base.copy(checkpoints = listOf(checkpoint, checkpoint.copy(blogId = 3))),
            base.copy(checkpoints = listOf(checkpoint, checkpoint.copy(checkpointId = "other"))),
            base.copy(checkpoints = listOf(checkpoint.copy(blogId = 1))),
            base.copy(retirements = listOf(reference("replica", 4), reference("replica", 5))),
            base.copy(retirements = listOf(reference("replica", 4), reference("other", 4))),
        )
        for (payload in conflicts) {
            assertFailsWith<IllegalArgumentException> { codec.encode(payload) }
            // Recompute a valid envelope hash: reject semantic conflicts, not just corruption.
            assertIs<AppSyncIndexValidation.Invalid>(codec.validate(uncheckedEnvelope(payload)))
        }
    }

    @Test fun exactDuplicatesRemainCompatibleAndNullJournalFingerprintIsPreserved() {
        val journal = AppSyncIndexJournalReference("replica", 1, null)
        val checkpoint = AppSyncIndexCheckpointReference("checkpoint", 2, "sha")
        val retirement = reference("old", 3)
        val payload = AppSyncIndexPayload(SyncAccountBinding("account"), listOf(journal, journal),
            listOf(checkpoint, checkpoint), listOf(retirement, retirement), 1)
        assertIs<AppSyncIndexValidation.Valid>(codec.validate(uncheckedEnvelope(payload)))
        val result = assertIs<AppSyncIndexValidation.Valid>(codec.validate(codec.encode(payload))).envelope.payload
        assertEquals(listOf(journal), result.journals)
        assertEquals(listOf(checkpoint), result.checkpoints)
        assertEquals(listOf(retirement), result.retirements)
    }

    @Test fun invalidJournalAndCheckpointIdentitiesCannotBecomeIndexEvidence() {
        val base = AppSyncIndexPayload(SyncAccountBinding("account"), updatedAtEpochMillis = 1)
        val invalid = listOf(
            base.copy(journals = listOf(AppSyncIndexJournalReference("", 1, null))),
            base.copy(journals = listOf(AppSyncIndexJournalReference("replica", 0, null))),
            base.copy(journals = listOf(AppSyncIndexJournalReference("replica", 1, ""))),
            base.copy(checkpoints = listOf(AppSyncIndexCheckpointReference("", 2, "sha"))),
            base.copy(checkpoints = listOf(AppSyncIndexCheckpointReference("checkpoint", -1, "sha"))),
            base.copy(checkpoints = listOf(AppSyncIndexCheckpointReference("checkpoint", 2, ""))),
        )
        for (payload in invalid) {
            assertFailsWith<IllegalArgumentException> { codec.encode(payload) }
            assertIs<AppSyncIndexValidation.Invalid>(codec.validate(uncheckedEnvelope(payload)))
        }
    }

    private fun uncheckedEnvelope(payload: AppSyncIndexPayload): String {
        val body = Json.encodeToString(AppSyncIndexPayload.serializer(), payload)
        val marker = AppSyncJournalDefaults.INDEX_MARKER
        return "[$marker:BEGIN]\nschema=${AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION}\nfingerprint=${stableAppSyncFingerprint(body)}\npayload=$body\n[$marker:END]"
    }
}
