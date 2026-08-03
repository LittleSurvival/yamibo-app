package me.thenano.yamibo.yamibo_app.repository.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository

class ExpandedBackupSchemaCompatibilityTest {
    @Test
    fun expandedSchemaOneFixtureRoundTripsEveryAddedSection() {
        val original = expandedBackup()

        val encoded = CloudBackupPayloadCodec().encode(original).getOrThrow()
        val decoded = CloudBackupPayloadCodec().decode(encoded).getOrThrow()

        assertEquals(original, decoded)
        assertEquals(1, decoded.readingState.tagCatalogHistory.size)
        assertEquals(1, decoded.readingState.rssSearchHistory.size)
        assertEquals(1, decoded.readingState.rssCatalogHistory.size)
        assertEquals(1, decoded.readingState.chapterState.size)
        assertEquals(1, decoded.favoriteUpdates.events.size)
        assertEquals(1, decoded.favoriteUpdates.fidFilters.size)
        assertEquals(1, decoded.favoriteUpdates.categoryFilters.size)
    }

    @Test
    fun unsupportedFutureSchemaIsRejectedByCodecAndRepository() = runBlocking {
        val future = expandedBackup().copy(schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION + 1)
        assertTrue(CloudBackupPayloadCodec().encode(future).isFailure)

        val fixture = BackupValidationHarness()
        fixture.put("future", future)

        val result = fixture.repository.restoreBackup("future", BackupRepository.RestoreMode.Overwrite)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("版本"))
    }
}
