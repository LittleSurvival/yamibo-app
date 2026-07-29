package me.thenano.yamibo.yamibo_app.repository.backup

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint

internal data class FavoriteUpdateEventIdentity(
    val syncId: String,
    val sourceFingerprint: String,
)

internal fun favoriteUpdateEventIdentity(
    targetType: String,
    targetId: Long,
    authorId: Long?,
    mode: String,
    detailIds: List<Long>,
    ambiguous: Boolean,
    detectedAt: Long,
    summary: String,
    title: String,
): FavoriteUpdateEventIdentity {
    val canonicalDetails = detailIds.distinct().sorted()
    val sourceMaterial = if (canonicalDetails.isNotEmpty()) {
        listOf(
            targetType,
            targetId.toString(),
            (authorId ?: 0L).toString(),
            mode,
            canonicalDetails.joinToString(","),
        ).joinToString("|")
    } else {
        require(ambiguous) {
            "FavoriteUpdate event without immutable detail evidence must be marked ambiguous"
        }
        listOf(
            targetType,
            targetId.toString(),
            (authorId ?: 0L).toString(),
            mode,
            "legacy-ambiguous",
            detectedAt.toString(),
            summary,
            title,
        ).joinToString("|")
    }
    val fingerprint = stableAppSyncFingerprint(sourceMaterial)
    return FavoriteUpdateEventIdentity(
        syncId = "event:$fingerprint",
        sourceFingerprint = fingerprint,
    )
}
