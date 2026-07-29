package me.thenano.yamibo.yamibo_app.repository.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository

class BackupMergeOverwriteSemanticsMatrixTest {
    @Test
    fun mergeKeepsOlderProgressThenAcceptsNewerProgressAcrossAllFamilies() = runBlocking {
        val fixture = BackupValidationHarness()
        restore(fixture, "local", expandedBackup(progressTime = 500))

        restore(fixture, "older", expandedBackup(progressTime = 100), BackupRepository.RestoreMode.Merge)
        assertProgressTimes(fixture, 500)

        restore(fixture, "newer", expandedBackup(progressTime = 900), BackupRepository.RestoreMode.Merge)
        assertProgressTimes(fixture, 900)
    }

    @Test
    fun sameSourceEventIgnoresDisplayChangesAndMergesLaterLifecycleMarkers() = runBlocking {
        val fixture = BackupValidationHarness()
        restore(
            fixture,
            "local",
            expandedBackup(
                eventDetectedAt = 100,
                eventSummary = "local summary",
                eventTitle = "local title",
                eventReadAt = 100,
            ),
        )
        restore(
            fixture,
            "remote",
            expandedBackup(
                eventDetectedAt = 900,
                eventSummary = "remote summary",
                eventTitle = "remote title",
                eventReadAt = 900,
                eventDismissedAt = 800,
            ),
            BackupRepository.RestoreMode.Merge,
        )

        val events = fixture.db.favoriteUpdateEventQueries.getAll().executeAsList()
        assertEquals(1, events.size)
        assertEquals("local summary", events.single().summary)
        assertEquals("local title", events.single().title)
        assertEquals(900, events.single().readAt)
        assertEquals(800, events.single().dismissedAt)
    }

    @Test
    fun distinctImmutableEvidenceCreatesDistinctEvents() = runBlocking {
        val fixture = BackupValidationHarness()
        restore(fixture, "first", expandedBackup(eventDetails = listOf(101, 102)))
        restore(
            fixture,
            "second",
            expandedBackup(eventDetails = listOf(103, 104)),
            BackupRepository.RestoreMode.Merge,
        )

        assertEquals(2, fixture.db.favoriteUpdateEventQueries.getAll().executeAsList().size)
    }

    @Test
    fun legacyAmbiguousFallbackDeduplicatesOnlyTheSameRetainedObservation() = runBlocking {
        val fixture = BackupValidationHarness()
        val legacy = expandedBackup(
            eventDetails = emptyList(),
            eventDetectedAt = 100,
            eventSummary = "legacy observation",
            eventTitle = "legacy title",
            eventReadAt = null,
            eventAmbiguous = true,
        )
        restore(fixture, "legacy-local", legacy)
        restore(
            fixture,
            "legacy-remote",
            legacy.copy(
                favoriteUpdates = legacy.favoriteUpdates.copy(
                    events = legacy.favoriteUpdates.events.map { it.copy(readAt = 900) },
                ),
            ),
            BackupRepository.RestoreMode.Merge,
        )

        val events = fixture.db.favoriteUpdateEventQueries.getAll().executeAsList()
        assertEquals(1, events.size)
        assertEquals(900, events.single().readAt)
    }

    @Test
    fun categoryFilterUsesSyncIdentityAndReportsOrphanInsteadOfSourceLocalId() = runBlocking {
        val fixture = BackupValidationHarness()
        restore(fixture, "local", expandedBackup(categoryLocalId = 10))
        val localCategory = fixture.db.localFavoriteCategoryQueries.getBySyncId("category-sync").executeAsOne()

        val imported = expandedBackup(
            categoryLocalId = 999,
            includeOrphanCategoryFilter = true,
        )
        fixture.put("remote", imported)
        val summary = fixture.repository.restoreBackup("remote", BackupRepository.RestoreMode.Merge).getOrThrow()

        val filter = fixture.db.favoriteUpdateCategoryFilterQueries.getAll().executeAsOne()
        assertEquals(localCategory.id, filter.categoryId)
        assertFalse(filter.enabled != 0L)
        assertEquals(1, summary.skippedRecords)
    }

    @Test
    fun overwriteRemovesStaleRowsFromEveryExpandedDomain() = runBlocking {
        val fixture = BackupValidationHarness()
        restore(fixture, "local", expandedBackup())
        restore(
            fixture,
            "empty",
            YamiboBackupFile(appVersionCode = 5, createdAt = 900),
            BackupRepository.RestoreMode.Overwrite,
        )

        assertTrue(fixture.db.tagCatalogReadingHistoryQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.rssSearchReadingHistoryQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.rssCatalogReadingHistoryQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.localChapterStateQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.favoriteUpdateEventQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.favoriteUpdateFidFilterQueries.getAll().executeAsList().isEmpty())
        assertTrue(fixture.db.favoriteUpdateCategoryFilterQueries.getAll().executeAsList().isEmpty())
    }

    private suspend fun restore(
        fixture: BackupValidationHarness,
        name: String,
        backup: YamiboBackupFile,
        mode: BackupRepository.RestoreMode = BackupRepository.RestoreMode.Overwrite,
    ) {
        fixture.put(name, backup)
        fixture.repository.restoreBackup(name, mode).getOrThrow()
    }

    private fun assertProgressTimes(fixture: BackupValidationHarness, expected: Long) {
        assertEquals(expected, fixture.db.readingHistoryQueries.getAllForBackup().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.imageReadingHistoryQueries.getAll().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.mangaTagReadingHistoryQueries.getAll().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.tagCatalogReadingHistoryQueries.getAll().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.rssSearchReadingHistoryQueries.getAll().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.rssCatalogReadingHistoryQueries.getAll().executeAsOne().lastVisitTime)
        assertEquals(expected, fixture.db.localChapterStateQueries.getAll().executeAsOne().updatedAt)
        assertEquals(expected, fixture.db.readingTimeStatQueries.getAll().executeAsOne().updatedAt)
        assertEquals(expected, fixture.db.detailNoteQueries.getAll().executeAsOne().updatedAt)
        assertEquals(expected, fixture.db.localBookMarkQueries.getAll().executeAsOne().updatedAt)
    }
}
