package me.thenano.yamibo.yamibo_app.repository.backup

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import okio.ByteString.Companion.encodeUtf8

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
    val fingerprint = if (discriminator.startsWith(PORTABLE_LEGACY_EVENT_PREFIX)) {
        require(ambiguous && canonicalDetails.isEmpty()) { "Invalid portable legacy event evidence" }
        val parts = discriminator.removePrefix(PORTABLE_LEGACY_EVENT_PREFIX).split('|')
        require(parts.size == 2 && parts[0] == legacyEventScope(targetType, targetId, authorId, mode).encodeUtf8().sha256().hex() &&
            parts[1].matches(Regex("[0-9a-f]{16}"))) { "Portable legacy event scope mismatch" }
        parts[1]
    } else stableAppSyncFingerprint(sourceMaterial)
    return FavoriteUpdateEventIdentity(
        syncId = "event:$fingerprint",
        sourceFingerprint = fingerprint,
        sourceDiscriminator = discriminator,
    )
}

internal const val PORTABLE_LEGACY_EVENT_PREFIX = "legacy-identity-v1|"

private fun legacyEventScope(targetType: String, targetId: Long, authorId: Long?, mode: String): String =
    listOf(targetType, targetId.toString(), (authorId ?: 0L).toString(), mode).joinToString("|")

/** Preserve an existing event identity, not an authentication proof. The importer verifies
 * the original source identity before dropping its duplicate presentation text.
 */
internal fun portableLegacyFavoriteUpdateDiscriminator(targetType: String, targetId: Long, authorId: Long?,
    mode: String, original: String): String {
    require(original.startsWith("legacy-ambiguous|"))
    val scope = legacyEventScope(targetType, targetId, authorId, mode)
    return PORTABLE_LEGACY_EVENT_PREFIX + scope.encodeUtf8().sha256().hex() + "|" + stableAppSyncFingerprint("$scope|$original")
}
