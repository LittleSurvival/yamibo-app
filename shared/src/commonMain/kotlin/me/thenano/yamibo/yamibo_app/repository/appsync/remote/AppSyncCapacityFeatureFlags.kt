package me.thenano.yamibo.yamibo_app.repository.appsync.remote

/** Local rollout switches. Defaults keep reads safe while staging and deletion stay reversible. */
internal data class AppSyncCapacityFeatureFlags(
    val v2ReadsEnabled: Boolean = true,
    val v2WritesEnabled: Boolean = true,
    val automaticLegacyRecoveryEnabled: Boolean = true,
    val cleanupDryRun: Boolean = true,
    val cleanupDeletionEnabled: Boolean = false,
) {
    /** A committed v2 Index reference remains readable even if opportunistic v2 discovery is paused. */
    fun mayReadV2(committedIndexReference: Boolean): Boolean =
        committedIndexReference || v2ReadsEnabled

    fun mayDeleteCleanupCandidates(): Boolean =
        cleanupDeletionEnabled && !cleanupDryRun
}

internal object AppSyncCapacityFeatureFlagKeys {
    const val V2_READS = "appSyncCapacityV2ReadsEnabled"
    const val V2_WRITES = "appSyncCapacityV2WritesEnabled"
    const val AUTOMATIC_LEGACY_RECOVERY = "appSyncCapacityAutomaticLegacyRecoveryEnabled"
    const val CLEANUP_DRY_RUN = "appSyncCapacityCleanupDryRun"
    const val CLEANUP_DELETION = "appSyncCapacityCleanupDeletionEnabled"
}

internal fun AppSyncJournalPayload.segmentedSessionFingerprint(
    codec: AppSyncJournalEnvelopeCodec,
): String = me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint(
    codec.encode(copy(heartbeatAtEpochMillis = 0L)),
)

internal fun AppSyncJournalPayload.forSegmentedSession(createdAtEpochMillis: Long): AppSyncJournalPayload =
    copy(heartbeatAtEpochMillis = createdAtEpochMillis)

internal fun AppSyncCheckpointPayload.forSegmentedSession(
    createdAtEpochMillis: Long,
): AppSyncCheckpointPayload = copy(createdAtEpochMillis = createdAtEpochMillis)
