package me.thenano.yamibo.yamibo_app.repository.appsync.cleanup

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession

internal data class AppSyncSegmentGenerationCandidate(
    val generationId: String,
    val rootBlogId: Long,
    val rootFingerprint: String,
    val segmentBlogIds: List<Long>,
    val payloadVerified: Boolean,
) {
    val deletionOrder: List<Long> get() = listOf(rootBlogId) + segmentBlogIds
}

internal data class AppSyncCleanupReachability(
    val indexedRootBlogIds: Set<Long> = emptySet(),
    val activeRecoveryRootBlogIds: Set<Long> = emptySet(),
    val pinnedCheckpointRootBlogIds: Set<Long> = emptySet(),
    val retirementRootBlogIds: Set<Long> = emptySet(),
) {
    val protectedRootBlogIds: Set<Long> get() =
        indexedRootBlogIds + activeRecoveryRootBlogIds +
            pinnedCheckpointRootBlogIds + retirementRootBlogIds
}

internal object AppSyncCleanupReachabilityAnalyzer {
    fun orphanCandidates(
        candidates: Collection<AppSyncSegmentGenerationCandidate>,
        reachability: AppSyncCleanupReachability,
    ): List<AppSyncSegmentGenerationCandidate> = candidates
        .asSequence()
        .filter { it.payloadVerified }
        .filterNot { it.rootBlogId in reachability.protectedRootBlogIds }
        .distinctBy { it.generationId }
        .sortedBy { it.generationId }
        .toList()

    fun activeRecoveryRootIds(sessions: Collection<AppSyncRecoverySession>): Set<Long> =
        sessions.mapNotNullTo(linkedSetOf()) { it.rootBlogId }
}

internal data class AppSyncCleanupObservation(
    val generationId: String,
    val accountBinding: String,
    val rootBlogId: Long,
    val rootFingerprint: String,
    val blogIds: List<Long>,
    val observationCount: Int,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
    val lastIndexFingerprint: String,
    val deletedBlogIds: Set<Long>,
) {
    fun isEligible(nowEpochMillis: Long): Boolean =
        observationCount >= REQUIRED_OBSERVATIONS &&
            lastObservedAtEpochMillis - firstObservedAtEpochMillis >= OBSERVATION_WINDOW_MILLIS &&
            nowEpochMillis - firstObservedAtEpochMillis >= OBSERVATION_WINDOW_MILLIS

    companion object {
        const val REQUIRED_OBSERVATIONS = 3
        const val MIN_OBSERVATION_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
        const val OBSERVATION_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}

internal interface AppSyncCleanupObservationStore {
    fun observe(
        accountBinding: String,
        candidate: AppSyncSegmentGenerationCandidate,
        authoritativeIndexFingerprint: String,
        observedAtEpochMillis: Long,
    ): AppSyncCleanupObservation

    fun observations(accountBinding: String): List<AppSyncCleanupObservation>
    fun markDeleted(generationId: String, blogId: Long)
    fun remove(generationId: String)
}

internal sealed interface AppSyncCleanupDeleteResult {
    data object Verified : AppSyncCleanupDeleteResult
    data class Retryable(val reason: String) : AppSyncCleanupDeleteResult
    data class Terminal(val reason: String) : AppSyncCleanupDeleteResult
}

internal data class AppSyncCleanupRunResult(
    val orphanGenerationCount: Int,
    val eligibleGenerationCount: Int,
    val deletedBlogCount: Int,
    val dryRunBlogCount: Int,
    val retryableFailure: String? = null,
    val terminalFailure: String? = null,
)

internal class AppSyncCleanupCoordinator(
    private val store: AppSyncCleanupObservationStore,
    private val deleteBlog: suspend (Long) -> AppSyncCleanupDeleteResult,
    private val nowMillis: () -> Long,
    private val maximumDeletesPerRun: Int = 20,
) {
    suspend fun observeAndClean(
        accountBinding: String,
        candidates: Collection<AppSyncSegmentGenerationCandidate>,
        reachability: AppSyncCleanupReachability,
        authoritativeIndexFingerprint: String,
        dryRun: Boolean,
        deletionEnabled: Boolean,
    ): AppSyncCleanupRunResult {
        require(authoritativeIndexFingerprint.isNotBlank())
        val now = nowMillis()
        val orphans = AppSyncCleanupReachabilityAnalyzer.orphanCandidates(candidates, reachability)
        val currentIds = orphans.mapTo(hashSetOf()) { it.generationId }
        store.observations(accountBinding)
            .filterNot { it.generationId in currentIds }
            .forEach { store.remove(it.generationId) }
        val observations = orphans.map { candidate ->
            store.observe(
                accountBinding,
                candidate,
                authoritativeIndexFingerprint,
                now,
            )
        }
        val eligible = observations.filter { it.isEligible(now) }
        val pendingDeletes = eligible.flatMap { observation ->
            observation.blogIds.filterNot { it in observation.deletedBlogIds }
                .map { observation to it }
        }.take(maximumDeletesPerRun)
        if (dryRun || !deletionEnabled) {
            return AppSyncCleanupRunResult(
                orphanGenerationCount = orphans.size,
                eligibleGenerationCount = eligible.size,
                deletedBlogCount = 0,
                dryRunBlogCount = pendingDeletes.size,
            )
        }
        var deleted = 0
        for ((observation, blogId) in pendingDeletes) {
            when (val result = deleteBlog(blogId)) {
                AppSyncCleanupDeleteResult.Verified -> {
                    store.markDeleted(observation.generationId, blogId)
                    deleted += 1
                }
                is AppSyncCleanupDeleteResult.Retryable -> return AppSyncCleanupRunResult(
                    orphans.size, eligible.size, deleted, 0, retryableFailure = result.reason,
                )
                is AppSyncCleanupDeleteResult.Terminal -> return AppSyncCleanupRunResult(
                    orphans.size, eligible.size, deleted, 0, terminalFailure = result.reason,
                )
            }
        }
        store.observations(accountBinding)
            .filter { observation -> observation.blogIds.all { it in observation.deletedBlogIds } }
            .forEach { store.remove(it.generationId) }
        return AppSyncCleanupRunResult(orphans.size, eligible.size, deleted, 0)
    }
}
