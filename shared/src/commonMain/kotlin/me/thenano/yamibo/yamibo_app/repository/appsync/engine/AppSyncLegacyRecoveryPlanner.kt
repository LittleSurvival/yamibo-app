package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPortableEntityResult
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPortabilityPolicy
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

internal enum class AppSyncLegacyRemoteEvidence {
    VerifiedPresent,
    VerifiedAbsent,
    Unknown,
}

internal data class AppSyncLegacyOperationClassification(
    val operation: SyncOperation,
    val evidence: AppSyncLegacyRemoteEvidence,
    val portability: AppSyncPortableEntityResult,
    val requiresRecovery: Boolean,
)

internal class AppSyncLegacyOperationClassifier {
    fun classify(
        pending: Collection<SyncOperation>,
        verifiedRemoteOperationIds: Set<SyncOperationId>,
        authoritativeAbsence: Boolean,
    ): List<AppSyncLegacyOperationClassification> = pending.map { operation ->
        val portability = AppSyncPortabilityPolicy.sanitizeFields(
            operation.domainId.value,
            operation.entityId.value,
            operation.fields,
        )
        val requiresRecovery = when (portability) {
            is AppSyncPortableEntityResult.NeedsAttention -> true
            is AppSyncPortableEntityResult.Portable ->
                portability.fields != operation.fields ||
                    (operation.domainId.value == "settings" &&
                        !AppSyncPortabilityPolicy.isSettingPortable(operation.entityId.value))
        }
        AppSyncLegacyOperationClassification(
            operation = operation,
            evidence = when {
                operation.operationId in verifiedRemoteOperationIds ->
                    AppSyncLegacyRemoteEvidence.VerifiedPresent
                authoritativeAbsence -> AppSyncLegacyRemoteEvidence.VerifiedAbsent
                else -> AppSyncLegacyRemoteEvidence.Unknown
            },
            portability = portability,
            requiresRecovery = requiresRecovery,
        )
    }
}

internal data class AppSyncRecoveryReplacement(
    val source: SyncOperation,
    val kind: SyncOperationKind,
    val fields: Map<String, String?>,
    val compensatingCleanup: Boolean,
)

internal data class AppSyncLegacyRecoveryPlan(
    val replacements: List<AppSyncRecoveryReplacement>,
    val supersededWithoutReplacement: Set<SyncOperationId>,
    val verifiedPresentSourceIds: Set<SyncOperationId>,
    val unknownOperationIds: Set<SyncOperationId>,
    val needsAttention: List<AppSyncPortableEntityResult.NeedsAttention>,
)

internal class AppSyncLegacyRecoveryPlanner {
    fun plan(
        classifications: Collection<AppSyncLegacyOperationClassification>,
    ): AppSyncLegacyRecoveryPlan {
        val replacements = mutableListOf<AppSyncRecoveryReplacement>()
        val supersededWithoutReplacement = linkedSetOf<SyncOperationId>()
        val verifiedPresent = linkedSetOf<SyncOperationId>()
        val unknown = linkedSetOf<SyncOperationId>()
        val blockers = mutableListOf<AppSyncPortableEntityResult.NeedsAttention>()
        classifications.forEach { classified ->
            val source = classified.operation
            when (classified.evidence) {
                AppSyncLegacyRemoteEvidence.Unknown -> {
                    unknown += source.operationId
                    return@forEach
                }
                else -> Unit
            }
            if (classified.evidence == AppSyncLegacyRemoteEvidence.VerifiedPresent) {
                verifiedPresent += source.operationId
            }
            if (!classified.requiresRecovery) {
                if (classified.evidence == AppSyncLegacyRemoteEvidence.VerifiedAbsent) {
                    replacements += AppSyncRecoveryReplacement(
                        source, source.kind, source.fields, compensatingCleanup = false,
                    )
                }
                return@forEach
            }
            val portable = when (val result = classified.portability) {
                is AppSyncPortableEntityResult.NeedsAttention -> {
                    blockers += result
                    return@forEach
                }
                is AppSyncPortableEntityResult.Portable -> result.fields
            }
            val localOnlySetting = source.domainId.value == "settings" &&
                !AppSyncPortabilityPolicy.isSettingPortable(source.entityId.value)
            when (classified.evidence) {
                AppSyncLegacyRemoteEvidence.VerifiedPresent -> {
                    if (localOnlySetting) {
                        replacements += AppSyncRecoveryReplacement(
                            source, SyncOperationKind.Delete, emptyMap(), compensatingCleanup = true,
                        )
                    } else {
                        val removed = source.fields.keys.filterTo(linkedSetOf()) {
                            portable[it] != source.fields[it]
                        }
                        if (removed.isEmpty()) {
                            supersededWithoutReplacement += source.operationId
                        } else {
                            replacements += AppSyncRecoveryReplacement(
                                source,
                                SyncOperationKind.Patch,
                                removed.associateWith { null },
                                compensatingCleanup = true,
                            )
                        }
                    }
                }
                AppSyncLegacyRemoteEvidence.VerifiedAbsent -> {
                    if (localOnlySetting ||
                        (portable.isEmpty() && source.kind in setOf(
                            SyncOperationKind.Put,
                            SyncOperationKind.Patch,
                        ))
                    ) {
                        supersededWithoutReplacement += source.operationId
                    } else {
                        replacements += AppSyncRecoveryReplacement(
                            source, source.kind, portable, compensatingCleanup = false,
                        )
                    }
                }
                AppSyncLegacyRemoteEvidence.Unknown -> Unit
            }
        }
        return AppSyncLegacyRecoveryPlan(
            replacements,
            supersededWithoutReplacement,
            verifiedPresent,
            unknown,
            blockers,
        )
    }
}

internal class AppSyncRecoveryOperationStager(
    private val recoveryStore: SqlDelightAppSyncRecoveryStore,
) {
    fun stage(sessionId: String, plan: AppSyncLegacyRecoveryPlan): List<SyncOperation> {
        require(plan.unknownOperationIds.isEmpty()) { "Unknown remote evidence cannot be staged" }
        require(plan.needsAttention.isEmpty()) { "Policy violations cannot be staged" }
        val session = requireNotNull(recoveryStore.session(sessionId))
        require(
            plan.replacements.mapTo(hashSetOf()) { it.source.operationId.value } +
                plan.supersededWithoutReplacement.map { it.value } +
                plan.verifiedPresentSourceIds.map { it.value } == session.sourceOperationIds,
        ) { "Recovery plan must classify every source operation" }
        val staged = plan.replacements.sortedBy { it.source.sequence.value }.mapIndexed { index, replacement ->
            val source = replacement.source
            val sequence = SyncSequence(session.targetFirstSequence + index)
            val origin = if (
                replacement.kind in setOf(SyncOperationKind.Delete, SyncOperationKind.RelationRemove) &&
                source.origin == SyncOperationOrigin.Migration
            ) SyncOperationOrigin.RemoteReplay else source.origin
            SyncOperation(
                operationId = SyncOperation.idFor(
                    session.targetDeviceId, session.targetDeviceEpoch, sequence,
                ),
                deviceId = session.targetDeviceId,
                deviceEpoch = session.targetDeviceEpoch,
                sequence = sequence,
                accountBinding = session.accountBinding,
                domainId = source.domainId,
                entityId = source.entityId,
                entityGeneration = source.entityGeneration,
                kind = replacement.kind,
                fields = replacement.fields,
                causalContext = source.causalContext,
                createdAtEpochMillis = source.createdAtEpochMillis,
                origin = origin,
                bulkDeleteAuthorizationId = source.bulkDeleteAuthorizationId,
                schemaVersion = source.schemaVersion,
            )
        }
        recoveryStore.stageOperations(sessionId, staged)
        return staged
    }
}
