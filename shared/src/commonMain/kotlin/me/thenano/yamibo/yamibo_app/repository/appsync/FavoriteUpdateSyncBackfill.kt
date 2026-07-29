package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity

internal fun backfillFavoriteUpdateSyncState(db: Database) {
    val eventQueries = db.favoriteUpdateEventQueries
    val existingBySyncId = linkedMapOf<String, Long>()
    eventQueries.getAll().executeAsList()
        .sortedBy { it.id }
        .forEach { event ->
            val detailIds = event.detailIds.orEmpty().split(",")
                .mapNotNull { it.trim().toLongOrNull() }
            val identity = favoriteUpdateEventIdentity(
                targetType = event.targetType,
                targetId = event.targetId,
                authorId = event.authorId,
                mode = event.mode,
                detailIds = detailIds,
                ambiguous = event.ambiguous != 0L,
                detectedAt = event.detectedAt,
                summary = event.summary,
                title = event.title,
                sourceDiscriminator = event.sourceDiscriminator,
            )
            val syncId = event.syncId.ifBlank { identity.syncId }
            val fingerprint = event.sourceFingerprint.ifBlank { identity.sourceFingerprint }
            val discriminator = event.sourceDiscriminator.ifBlank { identity.sourceDiscriminator }
            require(syncId == identity.syncId && fingerprint == identity.sourceFingerprint) {
                "FavoriteUpdate identity does not match its canonical source"
            }
            val existingId = existingBySyncId[syncId]
            if (existingId == null) {
                eventQueries.setIdentity(syncId, fingerprint, discriminator, event.id)
                existingBySyncId[syncId] = event.id
            } else {
                val existing = eventQueries.getById(existingId).executeAsOne()
                listOfNotNull(existing.readAt, event.readAt).maxOrNull()
                    ?.let { eventQueries.markRead(it, existingId) }
                listOfNotNull(existing.dismissedAt, event.dismissedAt).maxOrNull()
                    ?.let { eventQueries.dismiss(it, existingId) }
                eventQueries.deleteById(event.id)
            }
        }

    val fidChoices = db.favoriteUpdateFidChoiceQueries
    db.favoriteUpdateFidFilterQueries.getAll().executeAsList().forEach { filter ->
        if (fidChoices.getByFid(filter.fid).executeAsOneOrNull() == null) {
            fidChoices.upsertChoice(filter.fid, filter.enabled, null, filter.updatedAt)
        }
    }

    val categoryChoices = db.favoriteUpdateCategoryChoiceQueries
    val categorySyncIds = db.localFavoriteCategoryQueries.getAll().executeAsList()
        .mapNotNull { category -> category.syncId?.let { category.id to it } }
        .toMap()
    db.favoriteUpdateCategoryFilterQueries.getAll().executeAsList().forEach { filter ->
        val syncId = categorySyncIds[filter.categoryId] ?: return@forEach
        if (categoryChoices.getBySyncId(syncId).executeAsOneOrNull() == null) {
            categoryChoices.upsertChoice(syncId, filter.enabled, null, filter.updatedAt)
        }
    }
}
