package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence

class MultiDeviceConvergenceTest {
    private val reducer = OperationReducer()

    @Test
    fun twoToFiveDevicesConvergeAfterReorderedDuplicatedDroppedAndDelayedDelivery() {
        for (deviceCount in 2..5) {
            val operations = (0 until deviceCount).map { index -> operation(index) }
            val expected = reducer.reduce(operations = operations).entities.canonical()

            repeat(25) { run ->
                val random = Random(deviceCount * 10_000 + run)
                val delivered = (operations + operations.shuffled(random).take(deviceCount / 2))
                    .shuffled(random)
                val split = random.nextInt(delivered.size + 1)
                val firstBatch = delivered.take(split).filterIndexed { index, _ -> index % 3 != 0 }
                val delayed = delivered.drop(split) + operations
                val first = reducer.reduce(operations = firstBatch)
                val final = reducer.reduce(first.entities, delayed)

                assertEquals(expected, final.entities.canonical())
            }
        }
    }

    private fun operation(index: Int): SyncOperation {
        val device = SyncDeviceId("device-$index")
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, sequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "value-$index", "field-$index" to "$index"),
            createdAtEpochMillis = index.toLong(),
            origin = SyncOperationOrigin.UserAction,
        )
    }

    private fun Map<SyncEntityKey, ResolvedSyncEntity>.canonical() =
        mapValues { (_, entity) ->
            Triple(
                entity.fields.mapValues { it.value.value },
                entity.relationPresent,
                entity.tombstone?.operationId,
            )
        }
}
