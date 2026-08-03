package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence

class SyncOperationCodecTest {
    @Test
    fun operationRoundTripsWithoutPayloadLogging() {
        val device = SyncDeviceId("device")
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        val operation = SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, sequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "dark"),
            createdAtEpochMillis = 123,
            origin = SyncOperationOrigin.UserAction,
        )
        val codec = SyncOperationCodec()

        assertEquals(operation, codec.decode(codec.encode(operation)).getOrThrow())
    }

    @Test
    fun mismatchedOperationIdIsRejected() {
        val encoded = """
            {
              "operationId":"wrong",
              "deviceId":"device",
              "deviceEpoch":"epoch",
              "sequence":1,
              "accountBinding":"account",
              "domainId":"settings",
              "entityId":"theme",
              "kind":"Patch",
              "fields":{},
              "causalContext":{},
              "createdAtEpochMillis":123,
              "origin":"UserAction"
            }
        """.trimIndent()

        assertTrue(SyncOperationCodec().decode(encoded).isFailure)
    }
}
