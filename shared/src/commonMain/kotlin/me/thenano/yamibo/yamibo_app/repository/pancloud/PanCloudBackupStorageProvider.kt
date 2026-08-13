package me.thenano.yamibo.yamibo_app.repository.pancloud

import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.backup.BackupStorageProvider
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * 网盘云备份存储后端：把 `.yamibobak` 备份文件上传到网盘 `yamibo` 文件夹，
 * 恢复时从网盘下载。
 *
 * 传输编码为 gzip 二进制（决策 D2）：`BackupRepositoryImpl` 传入的 JSON bytes
 * 在此 gzip 后上传、下载后 gunzip，对上层完全透明。`uri` 字段复用网盘 file_id。
 */
class PanCloudBackupStorageProvider(
    private val apiClient: PanCloudApiClient,
    private val accountRepository: PanCloudAccountRepository,
) : BackupStorageProvider {

    override suspend fun getSelectedFolderLabel(): String? =
        accountRepository.status.username?.let { "網盤：$it" }

    override suspend fun setSelectedFolder(uri: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun writeBackupFile(
        fileName: String,
        bytes: ByteArray,
    ): Result<BackupRepository.BackupFileInfo> = runCatching {
        val parentId = accountRepository.ensureFolderBound().getOrThrow()
        val compressed = gzip(bytes)
        val fileId = if (compressed.size <= SINGLE_UPLOAD_LIMIT) {
            apiClient.uploadFile(compressed, fileName, MIME_TYPE, parentId).fileId
        } else {
            uploadInChunks(compressed, fileName, parentId)
        }
        BackupRepository.BackupFileInfo(
            name = fileName,
            bytes = bytes.size.toLong(),
            uri = fileId,
            automatic = fileName.endsWith(AUTO_BACKUP_SUFFIX),
            modifiedAt = null,
        )
    }

    override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> = runCatching {
        gunzip(apiClient.downloadFile(sourceUri))
    }

    override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> {
        val parentId = accountRepository.ensureFolderBound().getOrNull() ?: return emptyList()
        return runCatching {
            apiClient.listFiles(parentId = parentId)
                .filter { it.name.endsWith(BACKUP_EXTENSION) }
                .map {
                    BackupRepository.BackupFileInfo(
                        name = it.name,
                        bytes = it.size ?: 0L,
                        uri = it.id,
                        automatic = it.name.endsWith(AUTO_BACKUP_SUFFIX),
                        modifiedAt = it.updatedAt,
                    )
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun getBackupStorageBytes(): Long =
        listBackupFiles().sumOf { it.bytes }

    override suspend fun deleteBackupFile(fileInfo: BackupRepository.BackupFileInfo): Result<Unit> =
        runCatching { apiClient.deleteFile(fileInfo.uri) }

    private suspend fun uploadInChunks(
        bytes: ByteArray,
        fileName: String,
        parentId: String,
    ): String {
        val chunkRefs = mutableListOf<PanCloudChunkRef>()
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val result = apiClient.uploadChunk(chunk, index, fileName, parentId)
            chunkRefs += PanCloudChunkRef(result.index, result.fileId, chunk.size.toLong())
            offset = end
            index++
        }
        return apiClient.completeUpload(
            filename = fileName,
            totalSize = bytes.size.toLong(),
            parentId = parentId,
            chunks = chunkRefs,
        ).fileId
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = Buffer()
        GzipSink(output).buffer().use { sink -> sink.write(bytes) }
        return output.readByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val BACKUP_EXTENSION = ".yamibobak"
        const val AUTO_BACKUP_SUFFIX = "-autobackup.yamibobak"
        const val SINGLE_UPLOAD_LIMIT = 10 * 1024 * 1024
        const val CHUNK_SIZE = 10 * 1024 * 1024
    }
}
