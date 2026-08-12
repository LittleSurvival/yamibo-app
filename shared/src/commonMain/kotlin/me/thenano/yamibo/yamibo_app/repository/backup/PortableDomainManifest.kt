package me.thenano.yamibo.yamibo_app.repository.backup

internal enum class PortableSnapshotScope {
    LocalBackup,
    AppSync,
}

internal enum class PortableDomainDisposition {
    Included,
    Excluded,
}

internal data class PortableDomainDeclaration(
    val storageName: String,
    val localBackup: PortableDomainDisposition,
    val appSync: PortableDomainDisposition,
    val exclusionReason: String? = null,
)

internal object PortableDomainManifest {
    val declarations: List<PortableDomainDeclaration> = listOf(
        included("LocalFavoriteCategory", appSync = true),
        included("LocalFavoriteCollection", appSync = true),
        included("LocalFavoriteItem", appSync = true),
        included("RssSearchSubscription", appSync = true),
        included("LocalFavoriteItemCategoryCrossRef", appSync = true),
        included("LocalFavoriteItemCollectionCrossRef", appSync = true),
        included("DetailNote", appSync = true),
        included("LocalBookMark", appSync = true),
        included("ReadingHistory", appSync = true),
        included("ImageReadingHistory", appSync = true),
        included("MangaTagReadingHistory", appSync = true),
        included("TagCatalogReadingHistory", appSync = true),
        included("RssSearchReadingHistory", appSync = true),
        included("RssCatalogReadingHistory", appSync = true),
        included("LocalChapterState", appSync = false),
        included("ReadingTimeStat", appSync = true),
        included("FavoriteUpdateEvent", appSync = true),
        included("FavoriteUpdateFidChoice", appSync = true),
        included("FavoriteUpdateCategoryChoice", appSync = true),
        excluded("FavoriteUpdateFidFilter", "Derived projection of durable FID choices"),
        excluded("FavoriteUpdateCategoryFilter", "Derived projection of durable category choices"),
        excluded("FavoriteUpdateTrackedTarget", "Rebuildable scanner baseline and provider cache"),
        excluded("FavoriteUpdateRun", "Transient scan progress, log, warning, and error state"),
        excluded("DownloadQueueEntry", "Device-local download execution state"),
        excluded("AppSyncOperation", "Remote synchronization metadata"),
        excluded("AppSyncInstallation", "Device and account binding metadata"),
        excluded("AppSyncRemoteBlog", "Remote transport cache"),
        excluded("authentication", "Credentials, cookies, and FormHash are device-local secrets"),
        excluded("platformPaths", "Folder URIs and platform paths are not portable"),
    )

    val storageNames: Set<String> = declarations.mapTo(linkedSetOf()) { it.storageName }

    fun included(scope: PortableSnapshotScope): Set<String> =
        declarations.filter {
            when (scope) {
                PortableSnapshotScope.LocalBackup ->
                    it.localBackup == PortableDomainDisposition.Included
                PortableSnapshotScope.AppSync ->
                    it.appSync == PortableDomainDisposition.Included
            }
        }.mapTo(linkedSetOf()) { it.storageName }

    private fun included(storageName: String, appSync: Boolean) =
        PortableDomainDeclaration(
            storageName = storageName,
            localBackup = PortableDomainDisposition.Included,
            appSync = if (appSync) PortableDomainDisposition.Included else PortableDomainDisposition.Excluded,
            exclusionReason = if (appSync) null else "Not registered as an AppSync domain",
        )

    private fun excluded(storageName: String, reason: String) =
        PortableDomainDeclaration(
            storageName = storageName,
            localBackup = PortableDomainDisposition.Excluded,
            appSync = PortableDomainDisposition.Excluded,
            exclusionReason = reason,
        )
}
