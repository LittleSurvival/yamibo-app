package me.thenano.yamibo.yamibo_app.repository.download

interface DownloadStorageProvider {
    suspend fun getSelectedFolderLabel(): String?
    suspend fun isReady(): Boolean
    suspend fun writeThreadPage(
        key: ThreadPageDownloadKey,
        manifestBytes: ByteArray,
        threadPageBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    )
    suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray?
    suspend fun resolveImageUri(key: ThreadPageDownloadKey, fileName: String): String?
    suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest?
    suspend fun listManifests(): List<ThreadPageDownloadManifest>
    suspend fun writeTagMangaChapter(
        key: TagMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    )
    suspend fun resolveTagMangaImageUri(key: TagMangaChapterDownloadKey, fileName: String): String?
    suspend fun resolveTagMangaImageUris(key: TagMangaChapterDownloadKey, fileNames: List<String>): List<String> =
        fileNames.mapNotNull { resolveTagMangaImageUri(key, it) }
    suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest?
    suspend fun listTagMangaManifests(): List<TagMangaChapterManifest>
    suspend fun writeRssMangaChapter(
        key: RssMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    )
    suspend fun resolveRssMangaImageUri(key: RssMangaChapterDownloadKey, fileName: String): String?
    suspend fun resolveRssMangaImageUris(key: RssMangaChapterDownloadKey, fileNames: List<String>): List<String> =
        fileNames.mapNotNull { resolveRssMangaImageUri(key, it) }
    suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest?
    suspend fun listRssMangaManifests(): List<RssMangaChapterManifest>
    suspend fun readQueue(): List<DownloadQueueEntry>
    suspend fun writeQueue(entries: List<DownloadQueueEntry>)
    suspend fun deleteThreadPage(key: ThreadPageDownloadKey)
    suspend fun deleteThread(key: ThreadPageDownloadKey)
    suspend fun deleteTagMangaChapter(key: TagMangaChapterDownloadKey)
    suspend fun deleteTagManga(tagId: Int)
    suspend fun deleteRssMangaChapter(key: RssMangaChapterDownloadKey)
    suspend fun deleteRssManga(subscriptionId: Long)
}

data class PendingDownloadedImage(
    val fileName: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PendingDownloadedImage

        if (fileName != other.fileName) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
