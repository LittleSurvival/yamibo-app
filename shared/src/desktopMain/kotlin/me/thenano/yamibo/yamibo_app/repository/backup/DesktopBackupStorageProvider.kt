package me.thenano.yamibo.yamibo_app.repository.backup

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.repository.BackupRepository.BackupFileInfo
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository

/** Native paths stay on this machine; backup archives retain their portable format. */
class DesktopBackupStorageProvider(
    private val settings: AppSettingsRepository,
) : BackupStorageProvider {
    override suspend fun getSelectedFolderLabel(): String? = selectedFolder()?.toString()

    override suspend fun setSelectedFolder(uri: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = path(uri).toRealPath()
            require(Files.isDirectory(folder) && Files.isWritable(folder)) { "備份資料夾無法寫入" }
            settings.backupFolderUri.setValue(folder.toUri().toString())
            Unit
        }
    }

    override suspend fun writeBackupFile(fileName: String, bytes: ByteArray): Result<BackupFileInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(fileName.endsWith(EXTENSION) && fileName != EXTENSION) { "備份檔名無效" }
                val folder = requireNotNull(selectedFolder()) { "尚未選擇備份資料夾" }.toRealPath()
                val target = folder.resolve(fileName).normalize()
                require(target.parent == folder && !fileName.contains('/') && !fileName.contains('\\')) {
                    "備份檔名無效"
                }
                // Never replace an existing backup, including symlinks. A partial write stays hidden.
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "備份檔案已存在" }
                val temporary = Files.createTempFile(folder, ".yamibo-backup-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    // Plain move guarantees no replacement; same-directory moves stay on one filesystem.
                    Files.move(temporary, target)
                    info(target)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }

    override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching { Files.readAllBytes(path(sourceUri)) }
    }

    override suspend fun listBackupFiles(): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        val folder = selectedFolder() ?: return@withContext emptyList()
        Files.list(folder).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(EXTENSION) }
                .map(::info).collect(Collectors.toList()).sortedByDescending { it.modifiedAt }
        }
    }

    override suspend fun getBackupStorageBytes(): Long = listBackupFiles().sumOf { it.bytes }

    override suspend fun deleteBackupFile(fileInfo: BackupFileInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = requireNotNull(selectedFolder()) { "尚未選擇備份資料夾" }.toRealPath()
            val target = path(fileInfo.uri).toAbsolutePath().normalize()
            require(target.parent == folder && target.fileName.toString().endsWith(EXTENSION)) {
                "只能刪除所選資料夾內的備份"
            }
            require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "備份檔案不存在或不是一般檔案" }
            Files.delete(target)
        }
    }

    private fun selectedFolder(): Path? = settings.backupFolderUri.getValue()
        .takeIf(String::isNotBlank)?.let(::path)

    private fun path(value: String): Path =
        if (value.startsWith("file:", ignoreCase = true)) Path.of(URI(value)) else Path.of(value)

    private fun info(file: Path) = BackupFileInfo(
        name = file.fileName.toString(),
        bytes = Files.size(file),
        uri = file.toUri().toString(),
        automatic = file.fileName.toString().endsWith("-autobackup$EXTENSION"),
        modifiedAt = Files.getLastModifiedTime(file).toMillis(),
    )

    private companion object { const val EXTENSION = ".yamibobak" }
}
