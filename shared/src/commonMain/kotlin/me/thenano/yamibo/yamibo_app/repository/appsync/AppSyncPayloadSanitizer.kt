package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation

/** Also sanitize provenance: each winning operation can contain fields other than its winner. */
internal fun ResolvedSyncEntity.withoutExcludedAppSyncPayloads(): ResolvedSyncEntity = copy(
    fields = fields.filterKeys {
        AppSyncPortabilityPolicy.field(key.domainId.value, it).portability == AppSyncPortability.Portable
    }.mapValues { (_, field) -> field.copy(operation = field.operation.withoutExcludedAppSyncPayloads()) },
    relationOperation = relationOperation?.withoutExcludedAppSyncPayloads(),
    tombstone = tombstone?.withoutExcludedAppSyncPayloads(),
)

internal fun SyncOperation.withoutExcludedAppSyncPayloads(): SyncOperation = copy(
    fields = fields.filterKeys {
        AppSyncPortabilityPolicy.field(domainId.value, it).portability == AppSyncPortability.Portable
    },
)

internal fun portableAppSyncFields(
    domain: String,
    entityId: String,
    fields: Map<String, String?>,
): Map<String, String?> = if (domain == "settings" &&
    !AppSyncPortabilityPolicy.isSettingPortable(entityId)
) emptyMap() else fields.filterKeys {
    AppSyncPortabilityPolicy.field(domain, it).portability == AppSyncPortability.Portable
}

internal fun Collection<ResolvedSyncEntity>.withoutExcludedAppSyncPayloads(): List<ResolvedSyncEntity> =
    filterNot { it.key.domainId.value == "settings" &&
        !AppSyncPortabilityPolicy.isSettingPortable(it.key.entityId.value)
    }.map { it.withoutExcludedAppSyncPayloads() }

/**
 * Covers belong to the local content cache, including remotely fetchable URLs.
 * Keep this compatibility adapter for legacy import callers; never alter local backup models.
 */
internal fun appSyncThreadCoverOrNull(@Suppress("UNUSED_PARAMETER") value: String?): String? {
    return null
}

internal fun YamiboBackupFile.withPortableAppSyncPayloads(): YamiboBackupFile = copy(
    settings = settings.filter { AppSyncPortabilityPolicy.isSettingPortable(it.key) },
    favorites = favorites.copy(items = favorites.items.map { it.copy(coverUrl = null) }),
    readingState = readingState.copy(
        threadHistory = readingState.threadHistory.map { history ->
            history.copy(threadCover = appSyncThreadCoverOrNull(history.threadCover))
        },
        tagMangaHistory = readingState.tagMangaHistory.map { it.copy(coverUrl = null) },
        tagCatalogHistory = readingState.tagCatalogHistory.map { it.copy(coverUrl = null) },
        rssSearchHistory = readingState.rssSearchHistory.map { it.copy(coverUrl = null) },
        rssCatalogHistory = readingState.rssCatalogHistory.map { it.copy(coverUrl = null) },
    ),
    favoriteUpdates = favoriteUpdates.copy(
        events = favoriteUpdates.events.map { it.copy(coverUrl = null) },
    ),
)
