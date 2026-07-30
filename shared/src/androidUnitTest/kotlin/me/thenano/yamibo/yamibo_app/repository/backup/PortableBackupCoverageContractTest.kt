package me.thenano.yamibo.yamibo_app.repository.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository

class PortableBackupCoverageContractTest {
    @Test
    fun everySqlDomainIsDeclaredOrExplicitlyClassifiedAsNonPortable() {
        val sqlDirectory = repoRoot()
            .resolve("shared/src/commonMain/sqldelight/me/thenano/yamibo/yamibo_app")
        val sqlDomains = sqlDirectory.listFiles()
            .orEmpty()
            .filter { it.extension == "sq" }
            .mapTo(sortedSetOf()) { it.nameWithoutExtension }

        val undeclared = sqlDomains -
            PortableDomainManifest.storageNames -
            EXPLICIT_TEST_ONLY_NON_PORTABLE_SQL_DOMAINS

        assertTrue(
            undeclared.isEmpty(),
            "New SQL domains require a PortableDomainManifest declaration or an explicit " +
                "test-only infrastructure classification: $undeclared",
        )
        assertTrue(EXPLICIT_TEST_ONLY_NON_PORTABLE_SQL_DOMAINS.all(sqlDomains::contains))
    }

    @Test
    fun everyLocalBackupManifestDomainHasAnExportAndRestoreAdapter() = runBlocking {
        val fixture = BackupValidationHarness()
        fixture.put("expanded", expandedBackup())

        fixture.repository.restoreBackup("expanded", BackupRepository.RestoreMode.Overwrite).getOrThrow()
        val exported = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)

        assertEquals(
            PortableDomainManifest.included(PortableSnapshotScope.LocalBackup),
            domainsRepresentedBy(exported),
        )
    }

    @Test
    fun everySettingsRegistryIsDiscoveredAndItsItemsRoundTrip() = runBlocking {
        val registrySources = repoRoot()
            .resolve("shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/settings")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                SETTINGS_REGISTRY_DECLARATION.findAll(file.readText(Charsets.UTF_8))
                    .map { it.groupValues[1] }
            }
            .toSortedSet()
        assertEquals(EXPECTED_SETTINGS_REGISTRIES, registrySources)

        val settings = MutableTestSettingsStore()
        val registries = listOf(
            AppSettingsRepository(settings),
            MangaReaderSettingsRepository(settings),
            NovelReaderSettingsRepository(settings),
        )
        val fixture = BackupValidationHarness(settings = settings, registries = registries)
        val before = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)
        val expectedKeys = registries.flatMap { it.exportableSettingItems }
            .mapTo(sortedSetOf()) { it.storageKey }
            .filterNotTo(sortedSetOf(), ::isRuntimeOnlySetting)
        assertEquals(expectedKeys, before.settings.mapTo(sortedSetOf()) { it.key })

        before.settings.forEach { setting ->
            when (setting.type) {
                BackupSettingType.Int -> settings.putInt(setting.key, Int.MIN_VALUE)
                BackupSettingType.Float -> settings.putFloat(setting.key, Float.MIN_VALUE)
                BackupSettingType.Bool -> settings.putBoolean(setting.key, setting.value != "true")
                BackupSettingType.String,
                BackupSettingType.Enum -> settings.putString(setting.key, "__changed__")
            }
        }
        fixture.put("settings", before)
        fixture.repository.restoreBackup("settings", BackupRepository.RestoreMode.Overwrite).getOrThrow()

        val after = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)
        assertEquals(before.settings, after.settings)
    }

    private fun domainsRepresentedBy(backup: YamiboBackupFile): Set<String> = buildSet {
        val favorites = backup.favorites
        val reading = backup.readingState
        val updates = backup.favoriteUpdates
        if (favorites.categories.isNotEmpty()) add("LocalFavoriteCategory")
        if (favorites.collections.isNotEmpty()) add("LocalFavoriteCollection")
        if (favorites.items.isNotEmpty()) add("LocalFavoriteItem")
        if (favorites.rssSubscriptions.isNotEmpty()) add("RssSearchSubscription")
        if (favorites.itemCategories.isNotEmpty()) add("LocalFavoriteItemCategoryCrossRef")
        if (favorites.itemCollections.isNotEmpty()) add("LocalFavoriteItemCollectionCrossRef")
        if (backup.notes.isNotEmpty()) add("DetailNote")
        if (backup.bookmarks.isNotEmpty()) add("LocalBookMark")
        if (reading.threadHistory.isNotEmpty()) add("ReadingHistory")
        if (reading.imageHistory.isNotEmpty()) add("ImageReadingHistory")
        if (reading.tagMangaHistory.isNotEmpty()) add("MangaTagReadingHistory")
        if (reading.tagCatalogHistory.isNotEmpty()) add("TagCatalogReadingHistory")
        if (reading.rssSearchHistory.isNotEmpty()) add("RssSearchReadingHistory")
        if (reading.rssCatalogHistory.isNotEmpty()) add("RssCatalogReadingHistory")
        if (reading.chapterState.isNotEmpty()) add("LocalChapterState")
        if (reading.readingTimeStats.isNotEmpty()) add("ReadingTimeStat")
        if (updates.events.isNotEmpty()) add("FavoriteUpdateEvent")
        if (updates.fidFilters.isNotEmpty()) add("FavoriteUpdateFidChoice")
        if (updates.categoryFilters.isNotEmpty()) add("FavoriteUpdateCategoryChoice")
    }

    private fun repoRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private fun isRuntimeOnlySetting(key: String): Boolean {
        val normalized = key.replace(".", "").lowercase()
        return RUNTIME_ONLY_SETTING_SUFFIXES.any(normalized::endsWith)
    }

    private companion object {
        val EXPLICIT_TEST_ONLY_NON_PORTABLE_SQL_DOMAINS = setOf(
            "ContentCover",
            "DiskCacheEntry",
            "FavoriteSyncRemoteThread",
            "FavoriteSyncTask",
            "RssSearchPageCache",
            "RssSearchSubscriptionResult",
            "SignDailyRecord",
        )
        val EXPECTED_SETTINGS_REGISTRIES = sortedSetOf(
            "AppSettingsRepository",
            "MangaReaderSettingsRepository",
            "NovelReaderSettingsRepository",
        )
        val SETTINGS_REGISTRY_DECLARATION =
            Regex("""class\s+(\w+)\s*\([^)]*\)\s*:\s*SettingsRegistry\s*\(""")
        val RUNTIME_ONLY_SETTING_SUFFIXES = setOf(
            "signpagehtmlcache",
            "signpagehtmlcacheupdatedat",
            "favoriteupdatehiddenrunid",
            "appupdatelastcheckat",
            "appupdateignoredversioncode",
            "backupfolderuri",
            "backuplastautobackupat",
        )
    }
}
