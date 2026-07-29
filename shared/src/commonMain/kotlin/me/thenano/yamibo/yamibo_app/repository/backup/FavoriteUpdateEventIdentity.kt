package me.thenano.yamibo.yamibo_app.repository.backup

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint

internal data class FavoriteUpdateEventIdentity(
    val syncId: String,
    val sourceFingerprint: String,
    val sourceDiscriminator: String,
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
    sourceDiscriminator: String? = null,
): FavoriteUpdateEventIdentity {
    val canonicalDetails = detailIds.distinct().sorted()
    val discriminator = sourceDiscriminator?.takeIf { it.isNotBlank() } ?: if (canonicalDetails.isNotEmpty()) {
        "details:${canonicalDetails.joinToString(",")}"
    } else {
        require(ambiguous) {
            "FavoriteUpdate event without immutable detail evidence must be marked ambiguous"
        }
        listOf("legacy-ambiguous", detectedAt, summary, title).joinToString("|")
    }
    val sourceMaterial = listOf(
        targetType,
        targetId.toString(),
        (authorId ?: 0L).toString(),
        mode,
        discriminator,
    ).joinToString("|")
    val fingerprint = stableAppSyncFingerprint(sourceMaterial)
    return FavoriteUpdateEventIdentity(
        syncId = "event:$fingerprint",
        sourceFingerprint = fingerprint,
        sourceDiscriminator = discriminator,
    )
}
