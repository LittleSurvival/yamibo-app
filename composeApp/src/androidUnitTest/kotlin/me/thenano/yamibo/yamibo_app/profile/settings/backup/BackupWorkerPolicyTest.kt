package me.thenano.yamibo.yamibo_app.profile.settings.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository

class BackupWorkerPolicyTest {

    @Test
    fun cloudEnabledAndLoggedInTargetsCloudOnly() {
        assertEquals(AutomaticBackupTarget.CLOUD, resolveAutomaticBackupTarget(true, true))
    }

    @Test
    fun cloudEnabledWithoutLoginNeverFallsBackToLocal() {
        assertEquals(AutomaticBackupTarget.CLOUD_UNAVAILABLE, resolveAutomaticBackupTarget(true, false))
    }

    @Test
    fun cloudDisabledTargetsLocalRegardlessOfCloudLoginState() {
        assertEquals(AutomaticBackupTarget.LOCAL, resolveAutomaticBackupTarget(false, false))
        assertEquals(AutomaticBackupTarget.LOCAL, resolveAutomaticBackupTarget(false, true))
    }

    @Test
    fun summaryContainsFileNameAndByteSize() {
        val file = BackupRepository.BackupFileInfo(
            name = "YamiboApp-20260101-000000-autobackup.yamibobak",
            bytes = 42,
            uri = "cloud-file-id",
            automatic = true,
            modifiedAt = 1,
        )

        assertEquals("YamiboApp-20260101-000000-autobackup.yamibobak，42 bytes", automaticBackupSummary(file))
    }

    @Test
    fun helperCreatesAutomaticBackupThenCleansWithConfiguredMax() = runBlocking {
        val repository = RecordingBackupRepository()

        val file = createAutomaticBackupAndCleanup(repository, maxAutoFiles = 3)

        assertEquals("created", file.name)
        assertEquals(listOf("create:true", "cleanup:3"), repository.calls)
    }

    private class RecordingBackupRepository : BackupRepository {
        val calls = mutableListOf<String>()

        override suspend fun createBackup(
            automatic: Boolean,
            customName: String?,
        ): Result<BackupRepository.BackupFileInfo> {
            calls += "create:$automatic"
            return Result.success(
                BackupRepository.BackupFileInfo("created", 1, "uri", automatic, 1),
            )
        }

        override suspend fun restoreBackup(
            sourceUri: String,
            mode: BackupRepository.RestoreMode,
        ): Result<BackupRepository.RestoreSummary> =
            error("unexpected call")

        override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> = emptyList()

        override suspend fun getBackupStorageBytes(): Long = 0

        override suspend fun cleanupAutoBackups(maxFiles: Int): Result<Int> {
            calls += "cleanup:$maxFiles"
            return Result.success(0)
        }

        override suspend fun getSelectedFolderLabel(): String? = null

        override suspend fun setSelectedFolder(uri: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun summaryOfCloudOnlyBackupDoesNotReferenceLocalFile() {
        val file = BackupRepository.BackupFileInfo(
            name = "YamiboApp-20260101-010101-autobackup.yamibobak",
            bytes = 128,
            uri = "cloud-file-id",
            automatic = true,
            modifiedAt = 2,
        )

        assertTrue(automaticBackupSummary(file).startsWith("YamiboApp-20260101-010101"))
        assertTrue("local" !in automaticBackupSummary(file))
    }
}
