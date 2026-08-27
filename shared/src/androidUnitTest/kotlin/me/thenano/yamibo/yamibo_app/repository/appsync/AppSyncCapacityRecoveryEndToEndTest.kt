package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyOperationClassifier
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryOperationStager
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

class AppSyncCapacityRecoveryEndToEndTest {
    @Test
    fun tracedMixedLegacyDatabaseRecoversAndConvergesAcrossTwoReducers() {
        val fixture = AppSyncCapacityFailureFixture.create()
        val source = fixture.store.pendingOperations()
        val verifiedPresent = setOf(
            source.first { it.entityId.value == "appsettings.signpagehtmlcache" }.operationId,
            source.first { it.kind == SyncOperationKind.Delete }.operationId,
        )
        val classifications = AppSyncLegacyOperationClassifier().classify(
            pending = source,
            verifiedRemoteOperationIds = verifiedPresent,
            authoritativeAbsence = true,
        )
        val plan = AppSyncLegacyRecoveryPlanner().plan(classifications)
        assertTrue(plan.unknownOperationIds.isEmpty())
        assertTrue(plan.needsAttention.isEmpty())

        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            accountBinding = fixture.accountBinding,
            sourceOperationIds = source.mapTo(linkedSetOf()) { it.operationId.value },
            replacementFingerprint = "traced-capacity-recovery",
            nowEpochMillis = 10,
            acknowledgedSourceOperationIds = plan.verifiedPresentSourceIds
                .mapTo(linkedSetOf()) { it.value },
        )
        val shadow = AppSyncRecoveryOperationStager(recovery).stage(session.sessionId, plan)

        assertTrue(shadow.any { it.kind == SyncOperationKind.Delete })
        assertTrue(shadow.any { it.kind == SyncOperationKind.RelationRemove })
        assertTrue(shadow.none { operation ->
            operation.fields.values.any { value ->
                value?.contains("redacted-cache-block") == true || value?.startsWith("data:") == true
            }
        })

        recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment-fingerprint", null)
        recovery.markSegmentVerified(session.sessionId, 0, "segment-fingerprint", 301, 20)
        recovery.transition(
            session.sessionId,
            AppSyncRecoveryPhase.PublishingSegments,
            AppSyncRecoveryPhase.PublishingRoot,
            21,
        )
        recovery.markRootVerified(session.sessionId, 401, "root-fingerprint", 22)
        recovery.markIndexCommitted(session.sessionId, 23)
        recovery.activateCommittedSession(session.sessionId, 24)

        val rows = fixture.store.allOutboxOperations().associateBy { it.first.operationId }
        verifiedPresent.forEach { operationId ->
            assertEquals(AppSyncOperationLifecycle.Acknowledged, rows.getValue(operationId).second)
        }
        (source.map { it.operationId }.toSet() - verifiedPresent).forEach { operationId ->
            assertEquals(AppSyncOperationLifecycle.SupersededByRecovery, rows.getValue(operationId).second)
        }
        shadow.forEach { operation ->
            assertEquals(AppSyncOperationLifecycle.Acknowledged, rows.getValue(operation.operationId).second)
        }
        assertTrue(
            rows.values
                .filter { it.first.entityId.value == "appsettings.signpagehtmlcache" }
                .any { it.first.fields["value"]?.length == AppSyncCapacityFailureFixture.SIGN_CACHE_CHARS },
            "Recovery must retain the original local source row for rollback evidence",
        )

        val committedCloudOperations = source.filter { it.operationId in verifiedPresent } + shadow
        val firstDevice = OperationReducer().reduce(operations = committedCloudOperations)
        val secondDevice = OperationReducer().reduce(operations = committedCloudOperations.reversed())
        assertEquals(firstDevice.entities, secondDevice.entities)
        assertEquals(firstDevice.appliedOperationIds, secondDevice.appliedOperationIds)
        assertFalse(firstDevice.quarantined.isNotEmpty())
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(session.sessionId)?.phase)
    }
}
