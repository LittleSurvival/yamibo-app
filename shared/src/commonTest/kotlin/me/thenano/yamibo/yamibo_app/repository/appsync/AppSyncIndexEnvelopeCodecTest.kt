package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
}
