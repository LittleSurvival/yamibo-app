package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.backup.BackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.download.resolveIosStorageFolder
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

class IOSBackupStorageProvider(
    private val appSettingsRepository: AppSettingsRepository,
) : BackupStorageProvider {
    private val fileSystem = FileSystem.SYSTEM

    private inline fun <T> backupResult(operation: String, block: () -> T): Result<T> =
        runCatching(block)
            .onFailure { Logger.e(TAG, "$operation failed", it) }

    override suspend fun getSelectedFolderLabel(): String? =
        runCatching { selectedFolder().name }.getOrNull()

    override suspend fun setSelectedFolder(uri: String): Result<Unit> = backupResult("setSelectedFolder") {
        appSettingsRepository.backupFolderUri.setValue(uri)
        ensureFolder(selectedFolder())
    }

    override suspend fun writeBackupFile(fileName: String, bytes: ByteArray): Result<BackupRepository.BackupFileInfo> = backupResult("writeBackupFile fileName=$fileName") {
        val folder = selectedFolder()
        ensureFolder(folder)
        val path = folder / fileName
        fileSystem.sink(path).buffer().use { sink -> sink.write(bytes) }
        BackupRepository.BackupFileInfo(
            name = fileName,
            bytes = bytes.size.toLong(),
            uri = path.toString(),
            automatic = fileName.endsWith(AUTO_BACKUP_SUFFIX),
            modifiedAt = null,
        )
    }

    override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> = backupResult("readBackupFile") {
        fileSystem.source(sourceUri.toPath()).buffer().use { it.readByteArray() }
    }

    override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> {
        val folder = selectedFolder()
        if (!fileSystem.exists(folder)) return emptyList()
        return fileSystem.list(folder)
            .filter { it.name.endsWith(BACKUP_EXTENSION) }
            .map { path ->
                val metadata = fileSystem.metadata(path)
                BackupRepository.BackupFileInfo(
                    name = path.name,
                    bytes = metadata.size ?: 0L,
                    uri = path.toString(),
                    automatic = path.name.endsWith(AUTO_BACKUP_SUFFIX),
                    modifiedAt = metadata.lastModifiedAtMillis,
                )
            }
    }

    override suspend fun getBackupStorageBytes(): Long =
        listBackupFiles().sumOf { it.bytes }

    override suspend fun deleteBackupFile(fileInfo: BackupRepository.BackupFileInfo): Result<Unit> = backupResult("deleteBackupFile name=${fileInfo.name}") {
        fileSystem.delete(fileInfo.uri.toPath())
    }

    private fun selectedFolder(): Path = resolveIosStorageFolder(appSettingsRepository.backupFolderUri.getValue())

    private fun ensureFolder(path: Path) {
        if (!fileSystem.exists(path)) fileSystem.createDirectories(path)
    }

    private companion object {
        const val TAG = "IOSBackupStorageProvider"
        const val BACKUP_EXTENSION = ".yamibobak"
        const val AUTO_BACKUP_SUFFIX = "-autobackup.yamibobak"
    }
}
