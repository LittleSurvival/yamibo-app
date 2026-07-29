package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BulkDeleteGuard
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeletePolicy
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence

class BulkDeleteGuardTest {
    @Test
    fun unexpectedMassDeleteIsQuarantinedWithoutBlockingUpdate() {
        val deletes = (1L..101L).map(::delete)
        val update = update(102)
        val guard = BulkDeleteGuard(authorizationLookup = { null })

        val result = guard.evaluate(deletes + update) { 200 }

        assertEquals(listOf(update), result.accepted)
        assertEquals(101, result.quarantined.size)
    }

    @Test
    fun matchingUnconsumedAuthorizationAllowsConfirmedBatch() {
        val deletes = (1L..101L).map { delete(it, authorizationId = "auth") }
        val authorization = AppSyncBulkDeleteAuthorization(
            authorizationId = "auth",
            domainId = "reading.thread",
            scopeKey = "all-history",
            operationCount = 101,
            expiresAtEpochMillis = 1_000,
            consumedAtEpochMillis = null,
        )
        val guard = BulkDeleteGuard(
            authorizationLookup = { authorization },
        )

        val result = guard.evaluate(deletes) { 200 }

        assertEquals(101, result.accepted.size)
        assertTrue(result.quarantined.isEmpty())
    }

    @Test
    fun smallDeleteBatchDoesNotNeedCloudTimeConfirmation() {
        val deletes = (1L..5L).map(::delete)
        val guard = BulkDeleteGuard(authorizationLookup = { null })

        val result = guard.evaluate(deletes) { 200 }

        assertEquals(5, result.accepted.size)
        assertTrue(result.quarantined.isEmpty())
    }

    @Test
    fun embeddedAuthorizationProofWorksAcrossDevicesAfterOfflineDelay() {
        val deletes = (1L..101L).map {
            deleteWithProof(sequence = it, createdAt = 100, expiresAt = 200)
        }
        val guard = BulkDeleteGuard(authorizationLookup = { null })

        val result = guard.evaluate(deletes) { 200 }

        assertEquals(101, result.accepted.size)
        assertTrue(result.quarantined.isEmpty())
    }

    @Test
    fun proofExpiredBeforeOperationCreationIsQuarantined() {
        val deletes = (1L..101L).map {
            deleteWithProof(sequence = it, createdAt = 201, expiresAt = 200)
        }
        val guard = BulkDeleteGuard(authorizationLookup = { null })

        val result = guard.evaluate(deletes) { 200 }

        assertTrue(result.accepted.isEmpty())
        assertEquals(101, result.quarantined.size)
    }

    @Test
    fun everyConfiguredDomainUsesBothAbsoluteAndPercentageBoundary() {
        val guard = BulkDeleteGuard(authorizationLookup = { null })

        AppSyncBulkDeletePolicy.configuredThresholds().forEach { (domainId, threshold) ->
            val atBoundary = (1L..threshold.absoluteCount.toLong()).map {
                delete(it, domainId = domainId)
            }
            val overBoundary = (1L..(threshold.absoluteCount + 1L)).map {
                delete(it, domainId = domainId)
            }
            val population = (overBoundary.size * 4).coerceAtLeast(1)

            assertTrue(guard.evaluate(atBoundary) { population }.quarantined.isEmpty())
            assertEquals(
                overBoundary.size,
                guard.evaluate(overBoundary) { population }.quarantined.size,
                "Expected quarantine for ${domainId.value}",
            )
        }
    }

    private fun delete(
        sequence: Long,
        authorizationId: String? = null,
        domainId: SyncDomainId = SyncDomainId("reading.thread"),
    ): SyncOperation =
        operation(
            sequence = sequence,
            kind = SyncOperationKind.Delete,
            entity = "history-$sequence",
            authorizationId = authorizationId,
            domainId = domainId,
        )

    private fun update(sequence: Long): SyncOperation =
        operation(
            sequence = sequence,
            kind = SyncOperationKind.Patch,
            entity = "history-update",
            authorizationId = null,
        )

    private fun deleteWithProof(
        sequence: Long,
        createdAt: Long,
        expiresAt: Long,
    ): SyncOperation = operation(
        sequence = sequence,
        kind = SyncOperationKind.Delete,
        entity = "history-$sequence",
        authorizationId = "portable-proof",
        fields = mapOf(
            AppSyncBulkDeleteProofFields.SCOPE to "reading-history:all",
            AppSyncBulkDeleteProofFields.COUNT to "101",
            AppSyncBulkDeleteProofFields.EXPIRES_AT to expiresAt.toString(),
        ),
        createdAt = createdAt,
    )

    private fun operation(
        sequence: Long,
        kind: SyncOperationKind,
        entity: String,
        authorizationId: String?,
        fields: Map<String, String?> =
            if (kind == SyncOperationKind.Patch) mapOf("page" to "3") else emptyMap(),
        createdAt: Long = 100,
        domainId: SyncDomainId = SyncDomainId("reading.thread"),
    ): SyncOperation {
        val device = SyncDeviceId("device")
        val epoch = SyncDeviceEpoch("epoch")
        val syncSequence = SyncSequence(sequence)
        return SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, syncSequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = syncSequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = domainId,
            entityId = SyncEntityId(entity),
            kind = kind,
            fields = fields,
            createdAtEpochMillis = createdAt,
            origin = SyncOperationOrigin.UserAction,
            bulkDeleteAuthorizationId = authorizationId,
        )
    }
}
