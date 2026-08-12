package me.thenano.yamibo.yamibo_app.repository.appsync.rollout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class RolloutDemandOutcome {
    Converged,
    Excluded,
    ManualIntervention,
    Failed,
}

@Serializable
internal enum class RolloutExclusionReason {
    AuthenticationUnavailable,
    ExecutionOpportunityUnavailable,
    UnsupportedSchema,
    ProviderUnavailable,
}

@Serializable
internal data class RolloutFixedPointObservation(
    val pendingNonQuarantinedCount: Int,
    val fetchedValidUnappliedCount: Int,
    val projectionMismatchCount: Int,
    val acknowledgedOperationLossCount: Int,
) {
    init {
        require(pendingNonQuarantinedCount >= 0)
        require(fetchedValidUnappliedCount >= 0)
        require(projectionMismatchCount >= 0)
        require(acknowledgedOperationLossCount >= 0)
    }

    val reached: Boolean
        get() = pendingNonQuarantinedCount == 0 &&
            fetchedValidUnappliedCount == 0 &&
            projectionMismatchCount == 0
}

@Serializable
internal data class RolloutDemandEvidence(
    val demandId: String,
    val eligible: Boolean,
    val outcome: RolloutDemandOutcome,
    val attempts: Int,
    val coveredDomains: Set<String>,
    val fixedPoint: RolloutFixedPointObservation?,
    val exclusionReason: RolloutExclusionReason? = null,
) {
    init {
        require(demandId.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) {
            "Demand id must be an opaque, bounded identifier"
        }
        require(attempts > 0)
        require(coveredDomains.size <= 32)
        require(coveredDomains.all { it.matches(Regex("[a-z0-9.-]{1,80}")) })
        require(eligible == (exclusionReason == null)) {
            "Only ineligible demands may carry an exclusion reason"
        }
        require(eligible == (outcome != RolloutDemandOutcome.Excluded))
    }

    val convergedWithoutLoss: Boolean
        get() = eligible &&
            outcome == RolloutDemandOutcome.Converged &&
            fixedPoint?.reached == true &&
            fixedPoint.acknowledgedOperationLossCount == 0
}

@Serializable
internal data class AppSyncRolloutEvidenceReport(
    val generatedAtEpochMillis: Long,
    val demands: List<RolloutDemandEvidence>,
    val eligibleDemandCount: Int,
    val convergedDemandCount: Int,
    val retryCount: Int,
    val exclusionCount: Int,
    val manualInterventionCount: Int,
    val acknowledgedOperationLossCount: Int,
    val convergenceBasisPoints: Int,
    val passesRetirementGate: Boolean,
) {
    companion object {
        const val MINIMUM_ELIGIBLE_DEMANDS = 100
        const val REQUIRED_CONVERGENCE_BASIS_POINTS = 9_900
        private const val MAX_RECORDED_DEMANDS = 1_000

        fun create(
            generatedAtEpochMillis: Long,
            demands: List<RolloutDemandEvidence>,
        ): AppSyncRolloutEvidenceReport {
            require(generatedAtEpochMillis >= 0)
            require(demands.size <= MAX_RECORDED_DEMANDS) {
                "Rollout evidence is bounded to $MAX_RECORDED_DEMANDS demands"
            }
            require(demands.map { it.demandId }.distinct().size == demands.size) {
                "Retries must update one demand instead of adding denominator rows"
            }
            val eligible = demands.filter(RolloutDemandEvidence::eligible)
            val converged = eligible.count(RolloutDemandEvidence::convergedWithoutLoss)
            val losses = demands.sumOf {
                it.fixedPoint?.acknowledgedOperationLossCount ?: 0
            }
            val basisPoints = if (eligible.isEmpty()) {
                0
            } else {
                converged * 10_000 / eligible.size
            }
            val manualInterventions = demands.count {
                it.outcome == RolloutDemandOutcome.ManualIntervention
            }
            return AppSyncRolloutEvidenceReport(
                generatedAtEpochMillis = generatedAtEpochMillis,
                demands = demands.toList(),
                eligibleDemandCount = eligible.size,
                convergedDemandCount = converged,
                retryCount = demands.sumOf { (it.attempts - 1).coerceAtLeast(0) },
                exclusionCount = demands.count { !it.eligible },
                manualInterventionCount = manualInterventions,
                acknowledgedOperationLossCount = losses,
                convergenceBasisPoints = basisPoints,
                passesRetirementGate = eligible.size >= MINIMUM_ELIGIBLE_DEMANDS &&
                    basisPoints >= REQUIRED_CONVERGENCE_BASIS_POINTS &&
                    losses == 0 &&
                    manualInterventions == 0,
            )
        }
    }
}

internal object AppSyncRolloutEvidenceCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(report: AppSyncRolloutEvidenceReport): String = json.encodeToString(report)
}
