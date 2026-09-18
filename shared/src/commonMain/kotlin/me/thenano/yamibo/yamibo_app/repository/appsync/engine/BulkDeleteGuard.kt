package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind

internal data class BulkDeleteThreshold(
    val absoluteCount: Int,
    val fraction: Double,
) {
    init {
        require(absoluteCount > 0)
        require(fraction > 0.0 && fraction <= 1.0)
    }
}

internal object AppSyncBulkDeletePolicy {
    private val thresholds = mapOf(
        SyncDomainId("settings") to BulkDeleteThreshold(20, 0.20),
        SyncDomainId("favorite.item") to BulkDeleteThreshold(50, 0.20),
        SyncDomainId("favorite.category") to BulkDeleteThreshold(20, 0.20),
        SyncDomainId("favorite.collection") to BulkDeleteThreshold(30, 0.20),
        SyncDomainId("detail-note") to BulkDeleteThreshold(50, 0.20),
        SyncDomainId("bookmark") to BulkDeleteThreshold(50, 0.20),
        SyncDomainId("reading.thread") to BulkDeleteThreshold(100, 0.20),
        SyncDomainId("reading.image") to BulkDeleteThreshold(100, 0.20),
        SyncDomainId("reading.tag-manga") to BulkDeleteThreshold(100, 0.20),
        SyncDomainId("reading.time") to BulkDeleteThreshold(100, 0.20),
    )

    fun threshold(domainId: SyncDomainId): BulkDeleteThreshold =
        thresholds[domainId] ?: BulkDeleteThreshold(20, 0.20)

    fun configuredThresholds(): Map<SyncDomainId, BulkDeleteThreshold> = thresholds.toMap()
}

internal object AppSyncBulkDeleteProofFields {
    const val SCOPE = "appsyncBulkDeleteScope"
    const val COUNT = "appsyncBulkDeleteCount"
    const val EXPIRES_AT = "appsyncBulkDeleteExpiresAt"
}

internal data class BulkDeleteGuardResult(
    val accepted: List<SyncOperation>,
    val quarantined: List<SyncQuarantinedOperation>,
)

internal class BulkDeleteGuard(
    private val authorizationLookup: (String) -> AppSyncBulkDeleteAuthorization?,
) {
    fun evaluate(
        operations: List<SyncOperation>,
        domainEntityCount: (SyncDomainId) -> Int,
    ): BulkDeleteGuardResult = evaluateInternal(operations, domainEntityCount, allowCoveredSubset = false)

    /** Canonical import already validates one consistent proof and its total referenced count.
     * A verified checkpoint may cover a prefix of the originally authorized batch.
     */
    fun evaluateCoveredSubset(
        operations: List<SyncOperation>,
        domainEntityCount: (SyncDomainId) -> Int,
    ): BulkDeleteGuardResult = evaluateInternal(operations, domainEntityCount, allowCoveredSubset = true)

    private fun evaluateInternal(
        operations: List<SyncOperation>,
        domainEntityCount: (SyncDomainId) -> Int,
        allowCoveredSubset: Boolean,
    ): BulkDeleteGuardResult {
        val accepted = operations.toMutableList()
        val quarantined = mutableListOf<SyncQuarantinedOperation>()
        operations
            .filter { it.kind == SyncOperationKind.Delete }
            .groupBy { it.domainId }
            .forEach { (domainId, deletes) ->
                val total = domainEntityCount(domainId).coerceAtLeast(0)
                val threshold = AppSyncBulkDeletePolicy.threshold(domainId)
                val exceedsAbsolute = deletes.size > threshold.absoluteCount
                val exceedsFraction = total > 0 && deletes.size.toDouble() / total > threshold.fraction
                if (!exceedsAbsolute || !exceedsFraction) return@forEach

                // A canonical recovery can combine several independently authorized commands.
                // Evaluate the domain threshold first, then require each represented batch's
                // own proof. Splitting into small groups must not bypass the threshold.
                val batches = if (allowCoveredSubset) deletes.groupBy { it.bulkDeleteAuthorizationId }.values
                    else listOf(deletes)
                batches.forEach { batch ->
                    val authorizationIds = batch.map { it.bulkDeleteAuthorizationId }.distinct()
                    val authorization = authorizationIds.singleOrNull()?.let(authorizationLookup)
                    val storedAuthorizationValid = authorization != null &&
                        authorization.domainId == domainId.value &&
                        (authorization.operationCount == batch.size.toLong() ||
                            (allowCoveredSubset && authorization.operationCount >= batch.size.toLong())) &&
                        batch.all { it.createdAtEpochMillis <= authorization.expiresAtEpochMillis }
                    val embeddedAuthorizationValid =
                        authorizationIds.singleOrNull() != null &&
                            batch.mapNotNull {
                                it.fields[AppSyncBulkDeleteProofFields.SCOPE]?.takeIf(String::isNotBlank)
                            }.distinct().size == 1 &&
                            batch.all {
                                !it.fields[AppSyncBulkDeleteProofFields.SCOPE].isNullOrBlank()
                            } &&
                            batch.all {
                                val count = it.fields[AppSyncBulkDeleteProofFields.COUNT]?.toLongOrNull()
                                count == batch.size.toLong() || (allowCoveredSubset && count != null && count >= batch.size.toLong())
                            } &&
                            batch.all {
                                val expiresAt = it.fields[AppSyncBulkDeleteProofFields.EXPIRES_AT]
                                    ?.toLongOrNull()
                                    ?: return@all false
                                it.createdAtEpochMillis <= expiresAt
                            }
                    val valid = storedAuthorizationValid || embeddedAuthorizationValid
                    if (!valid) {
                        batch.forEach { operation ->
                            accepted.remove(operation)
                            quarantined += SyncQuarantinedOperation(
                                operation = operation,
                                reason = "Suspicious bulk delete lacks matching authorization",
                            )
                        }
                    }
                }
            }
        return BulkDeleteGuardResult(accepted, quarantined)
    }
}
