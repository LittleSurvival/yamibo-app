package me.thenano.yamibo.yamibo_app.repository

interface BackupRepository {
    enum class RestoreMode {
        Merge,
        Overwrite,
    }

    data class BackupFileInfo(
        val name: String,
        val bytes: Long,
        val uri: String,
        val automatic: Boolean,
        val modifiedAt: Long?,
    )

    data class RestoreSummary(
        val favorites: Int,
        val settings: Int,
        val notes: Int,
        val bookmarks: Int,
        val readingHistory: Int,
        val updateRecords: Int = 0,
        val skippedRecords: Int = 0,
    )

    suspend fun createBackup(automatic: Boolean = false, customName: String? = null): Result<BackupFileInfo>
    suspend fun restoreBackup(sourceUri: String, mode: RestoreMode): Result<RestoreSummary>
    suspend fun listBackupFiles(): List<BackupFileInfo>
    suspend fun getBackupStorageBytes(): Long
    suspend fun cleanupAutoBackups(maxFiles: Int): Result<Int>
    suspend fun getSelectedFolderLabel(): String?
    suspend fun setSelectedFolder(uri: String): Result<Unit>
}
