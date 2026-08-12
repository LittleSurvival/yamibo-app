package me.thenano.yamibo.yamibo_app.repository.contentcover

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.ContentCover

class ContentCoverRepositoryImpl(
    private val db: Database,
) : ContentCoverRepository {
    private val queries = db.contentCoverQueries

    override fun observeCover(key: ContentCoverRepository.Key): Flow<ContentCoverRepository.Cover?> =
        queries.getByTarget(key.targetType.toStorageValue(), key.targetId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it?.toModel() }

    override suspend fun getCover(key: ContentCoverRepository.Key): ContentCoverRepository.Cover? =
        queries.getByTarget(key.targetType.toStorageValue(), key.targetId)
            .executeAsOneOrNull()
            ?.toModel()

    override suspend fun setAutomaticCover(key: ContentCoverRepository.Key, url: String): Boolean {
        val normalized = normalizeCoverUrl(url) ?: return false
        db.transaction {
            val existing = queries.getByTarget(key.targetType.toStorageValue(), key.targetId)
                .executeAsOneOrNull()
            queries.upsert(
                targetType = key.targetType.toStorageValue(),
                targetId = key.targetId,
                automaticCoverUrl = normalized,
                manualCoverUrl = existing?.manualCoverUrl,
                dynamicEnabled = existing?.dynamicEnabled ?: 1L,
                updatedAt = currentTimeMillis(),
            )
        }
        return true
    }

    override suspend fun setManualCover(key: ContentCoverRepository.Key, url: String): Boolean {
        val normalized = normalizeCoverUrl(url) ?: return false
        db.transaction {
            val existing = queries.getByTarget(key.targetType.toStorageValue(), key.targetId)
                .executeAsOneOrNull()
            queries.upsert(
                targetType = key.targetType.toStorageValue(),
                targetId = key.targetId,
                automaticCoverUrl = existing?.automaticCoverUrl,
                manualCoverUrl = normalized,
                dynamicEnabled = 0L,
                updatedAt = currentTimeMillis(),
            )
        }
        return true
    }

    override suspend fun setDynamicEnabled(key: ContentCoverRepository.Key, enabled: Boolean) {
        db.transaction {
            val existing = queries.getByTarget(key.targetType.toStorageValue(), key.targetId)
                .executeAsOneOrNull()
            queries.upsert(
                targetType = key.targetType.toStorageValue(),
                targetId = key.targetId,
                automaticCoverUrl = existing?.automaticCoverUrl,
                manualCoverUrl = existing?.manualCoverUrl,
                dynamicEnabled = if (enabled) 1L else 0L,
                updatedAt = currentTimeMillis(),
            )
        }
    }

    private fun ContentCover.toModel(): ContentCoverRepository.Cover {
        val type = ContentCoverRepository.TargetType.entries
            .firstOrNull { it.name == targetType }
            ?: ContentCoverRepository.TargetType.ThreadNormal
        return ContentCoverRepository.Cover(
            key = ContentCoverRepository.Key(type, targetId),
            automaticCoverUrl = automaticCoverUrl?.let(::normalizeCoverUrl),
            manualCoverUrl = manualCoverUrl?.let(::normalizeCoverUrl),
            dynamicEnabled = dynamicEnabled != 0L,
            updatedAt = updatedAt,
        )
    }
}

internal fun normalizeCoverUrl(rawUrl: String): String? {
    val url = repairDomainPrefixedLocalCoverUri(rawUrl.trim())
    if (url.isEmpty()) return null
    val lower = url.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("blob:")) return null
    if (lower.contains("none.gif") || lower.contains("static/image/") ||
        lower.contains("/smiley/") || lower.contains("/face/")) return null
    return when {
        lower.startsWith("https://") || lower.startsWith("http://") -> url
        lower.contains("://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://bbs.yamibo.com$url"
        else -> "https://bbs.yamibo.com/$url"
    }
}

private fun repairDomainPrefixedLocalCoverUri(url: String): String =
    when {
        url.startsWith("https://bbs.yamibo.com/content://") -> url.removePrefix("https://bbs.yamibo.com/")
        url.startsWith("https://bbs.yamibo.com/file://") -> url.removePrefix("https://bbs.yamibo.com/")
        else -> url
    }

private fun ContentCoverRepository.TargetType.toStorageValue(): String = name

fun ReadHistoryRepository.ThreadEntryType.toCoverTargetType(): ContentCoverRepository.TargetType =
    when (this) {
        ReadHistoryRepository.ThreadEntryType.Normal -> ContentCoverRepository.TargetType.ThreadNormal
        ReadHistoryRepository.ThreadEntryType.Novel -> ContentCoverRepository.TargetType.ThreadNovel
    }
