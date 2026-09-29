package me.thenano.yamibo.yamibo_app.profile.settings.backup

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.thenano.yamibo.yamibo_app.repository.BackupRepository

class BackupStorageSnapshotTest {
    @Test fun usesOneListingForCountAndBytes() = runTest {
        val repository = FakeBackupRepository()
        val snapshot = repository.readStorageSnapshot().getOrThrow()
        assertEquals("backup-folder", snapshot.folderLabel)
        assertEquals(2, snapshot.files.size)
        assertEquals(30L, snapshot.bytes)
        assertEquals(1, repository.listings)
    }

    @Test fun unavailableFolderIsFailureNotAnEmptySuccessfulListing() = runTest {
        val repository = FakeBackupRepository()
        val failure = IllegalStateException("folder unavailable")
        repository.failure = failure
        assertSame(failure, repository.readStorageSnapshot().exceptionOrNull())
        repository.failure = null
        assertEquals(2, repository.readStorageSnapshot().getOrThrow().files.size)
    }

    @Test fun cancellationPropagatesWithoutFailureFeedback() = runTest {
        val repository = FakeBackupRepository()
        repository.failure = CancellationException("leaving screen")
        assertFailsWith<CancellationException> { repository.readStorageSnapshot() }
    }

    private class FakeBackupRepository : BackupRepository {
        var listings = 0
        var failure: Exception? = null
        override suspend fun getSelectedFolderLabel() = "backup-folder"
        override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> {
            listings++
            failure?.let { throw it }
            return listOf(10L, 20L).map {
                BackupRepository.BackupFileInfo("$it.yamibobak", it, "file:/$it", false, null)
            }
        }
        override suspend fun getBackupStorageBytes(): Long = error("Do not enumerate twice")
        override suspend fun createBackup(automatic: Boolean, customName: String?) = error("Unused")
        override suspend fun restoreBackup(sourceUri: String, mode: BackupRepository.RestoreMode) = error("Unused")
        override suspend fun cleanupAutoBackups(maxFiles: Int) = error("Unused")
        override suspend fun setSelectedFolder(uri: String) = error("Unused")
    }
}
