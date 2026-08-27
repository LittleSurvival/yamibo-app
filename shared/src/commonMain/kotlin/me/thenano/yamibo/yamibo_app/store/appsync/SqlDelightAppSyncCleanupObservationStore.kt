package me.thenano.yamibo.yamibo_app.store.appsync

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupObservation
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupObservationStore
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncSegmentGenerationCandidate

internal class SqlDelightAppSyncCleanupObservationStore(
    db: Database,
    private val json: Json = Json,
) : AppSyncCleanupObservationStore {
    private val queries = db.appSyncOperationQueries

    override fun observe(
        accountBinding: String,
        candidate: AppSyncSegmentGenerationCandidate,
        authoritativeIndexFingerprint: String,
        observedAtEpochMillis: Long,
    ): AppSyncCleanupObservation {
        val blogIds = candidate.deletionOrder.distinct()
        val existing = queries.getCleanupObservation(candidate.generationId)
            .executeAsOneOrNull()?.toModel()
        if (existing == null ||
            existing.rootBlogId != candidate.rootBlogId ||
            existing.rootFingerprint != candidate.rootFingerprint ||
            existing.blogIds != blogIds ||
            existing.accountBinding != accountBinding
        ) {
            existing?.let { queries.deleteCleanupObservation(candidate.generationId) }
            queries.insertCleanupObservation(
                generationId = candidate.generationId,
                accountBinding = accountBinding,
                rootBlogId = candidate.rootBlogId,
                rootFingerprint = candidate.rootFingerprint,
                blogIdsJson = json.encodeToString(blogIds),
                firstObservedAtEpochMillis = observedAtEpochMillis,
                lastObservedAtEpochMillis = observedAtEpochMillis,
                lastIndexFingerprint = authoritativeIndexFingerprint,
            )
        } else if (
            observedAtEpochMillis - existing.lastObservedAtEpochMillis >=
            AppSyncCleanupObservation.MIN_OBSERVATION_INTERVAL_MILLIS
        ) {
            queries.updateCleanupObservation(
                rootBlogId = candidate.rootBlogId,
                rootFingerprint = candidate.rootFingerprint,
                blogIdsJson = json.encodeToString(blogIds),
                observationCount = existing.observationCount + 1L,
                lastObservedAtEpochMillis = observedAtEpochMillis,
                lastIndexFingerprint = authoritativeIndexFingerprint,
                generationId = candidate.generationId,
            )
        }
        return requireNotNull(
            queries.getCleanupObservation(candidate.generationId).executeAsOneOrNull(),
        ).toModel()
    }

    override fun observations(accountBinding: String): List<AppSyncCleanupObservation> =
        queries.getCleanupObservationsByAccount(accountBinding).executeAsList().map { it.toModel() }

    override fun markDeleted(generationId: String, blogId: Long) {
        val existing = queries.getCleanupObservation(generationId).executeAsOneOrNull()?.toModel()
            ?: return
        queries.markCleanupBlogDeleted(
            json.encodeToString((existing.deletedBlogIds + blogId).sorted()),
            generationId,
        )
    }

    override fun remove(generationId: String) {
        queries.deleteCleanupObservation(generationId)
    }

    private fun me.thenano.yamibo.yamibo_app.AppSyncCleanupObservation.toModel() =
        AppSyncCleanupObservation(
            generationId = generationId,
            accountBinding = accountBinding,
            rootBlogId = rootBlogId,
            rootFingerprint = rootFingerprint,
            blogIds = json.decodeFromString(blogIdsJson),
            observationCount = observationCount.toInt(),
            firstObservedAtEpochMillis = firstObservedAtEpochMillis,
            lastObservedAtEpochMillis = lastObservedAtEpochMillis,
            lastIndexFingerprint = lastIndexFingerprint,
            deletedBlogIds = json.decodeFromString<List<Long>>(deletedBlogIdsJson).toSet(),
        )
}
