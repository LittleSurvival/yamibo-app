package me.thenano.yamibo.yamibo_app.repository.download

import no.synth.kmpzip.io.SeekableSource
import okio.Path

interface DownloadStorageProvider {
    suspend fun getSelectedFolderLabel(): String?
    suspend fun getStorageKey(): String? = getSelectedFolderLabel()
    suspend fun isReady(): Boolean
    suspend fun publishItem(storageKey: String, key: DownloadTaskKey, manifest: ByteArray, page: ByteArray?, archive: Path?,
                            verifyArchive: ((SeekableSource) -> Unit)? = null)
    suspend fun openArchive(key: DownloadTaskKey, generation: String): SeekableSource?
    suspend fun looseImages(key: DownloadTaskKey, names: List<String>?): Map<String, String>
    fun openLoose(uri: String): okio.Source
    /** Append to the existing page in this specific storage folder, publishing its manifest last. */
    suspend fun writeThreadImage(
        storageKey: String, key: ThreadPageDownloadKey, image: PendingDownloadedImage, manifestBytes: ByteArray,
    ): String
    suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray?
    suspend fun readPageRecord(key: ThreadPageDownloadKey): Pair<ByteArray, ThreadPageDownloadManifest?>? =
        readThreadPage(key)?.let { it to readManifest(key) }
    suspend fun updateThreadPage(key: ThreadPageDownloadKey, manifestBytes: ByteArray, threadPageBytes: ByteArray)
    suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest?
    suspend fun listManifests(): List<ThreadPageDownloadManifest>
    suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest?
    suspend fun listTagMangaManifests(): List<TagMangaChapterManifest>
    suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest?
    suspend fun listRssMangaManifests(): List<RssMangaChapterManifest>
    suspend fun readQueue(): List<DownloadQueueEntry>
    suspend fun writeQueue(entries: List<DownloadQueueEntry>)
    /** Return a failure per key. A successful deletion includes checking that the directory is gone. */
    suspend fun groupKeys(storageKey: String, group: DownloadTaskKey): Set<DownloadTaskKey>
    suspend fun deleteItems(storageKey: String, keys: Set<DownloadTaskKey>, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable>

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

internal fun itemDirectoryNames(key: DownloadTaskKey) = listOf(key.itemDirectory, "${key.itemDirectory}.previous", "${key.itemDirectory}.tmp")

private val threadDirectory = Regex("""thread_(-?[0-9]+)_page_([0-9]+)_author_(all|-?[0-9]+)""")
private val chapterDirectory = Regex("""chapter_(-?[0-9]+)_author_(all|-?[0-9]+)""")

internal fun groupMember(group: DownloadTaskKey, raw: String): DownloadTaskKey? {
    val name = raw.removeSuffix(".previous").removeSuffix(".tmp")
    if (group is ThreadPageDownloadKey) {
        val match = threadDirectory.matchEntire(name) ?: return null
        if (match.groupValues[1] != group.tid.toString() || match.groupValues[3] != (group.authorId?.toString() ?: "all")) return null
        return group.copy(page = match.groupValues[2].toIntOrNull() ?: return null)
    }
    val match = chapterDirectory.matchEntire(name) ?: return null
    val tid = match.groupValues[1].toIntOrNull() ?: return null
    val author = match.groupValues[2].let { if (it == "all") null else it.toIntOrNull() ?: return null }
    return when (group) {
        is TagMangaChapterDownloadKey -> group.copy(tid = tid, authorId = author)
        is RssMangaChapterDownloadKey -> group.copy(tid = tid, authorId = author)
        is ThreadPageDownloadKey -> null
    }
}
