package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeSummary
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.summarizeWinningOperations
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

class OperationChangeSummaryTest {
    @Test
    fun onlyFinalWinnerIsReportedAndBooleanPatchUsesEnabledState() {
        val received = operation(
            device = "remote",
            kind = SyncOperationKind.Put,
            value = "true",
        )
        val uploaded = operation(
            device = "local",
            kind = SyncOperationKind.Patch,
            value = "false",
            causalContext = SyncCausalContext().advance(received.replicaKey, received.sequence),
        )
        val state = OperationReducer().reduce(operations = listOf(received, uploaded)).entities

        assertEquals(
            listOf(
                OperationChangeSummary(
                    direction = OperationChangeDirection.Uploaded,
                    domainId = "settings",
                    action = OperationChangeAction.Disabled,
                    count = 1,
                ),
            ),
            summarizeWinningOperations(listOf(received), listOf(uploaded), state),
        )
    }

    private fun operation(
        device: String,
        kind: SyncOperationKind,
        value: String,
        causalContext: SyncCausalContext = SyncCausalContext(),
    ): SyncOperation {
        val deviceId = SyncDeviceId(device)
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(deviceId, epoch, sequence),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("feature"),
            kind = kind,
            fields = mapOf("type" to "bool", "value" to value),
            causalContext = causalContext,
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.UserAction,
        )
    }
}
