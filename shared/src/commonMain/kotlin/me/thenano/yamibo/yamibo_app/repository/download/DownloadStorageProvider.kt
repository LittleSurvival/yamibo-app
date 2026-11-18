package me.thenano.yamibo.yamibo_app.repository.download

import no.synth.kmpzip.io.SeekableSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.littlesurvival.dto.page.ThreadPage
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.Logger
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.SYSTEM
import okio.buffer
import okio.use
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
    /** Append to an existing download in this storage folder, publishing its manifest last. */
    suspend fun writeImage(
        storageKey: String, key: DownloadTaskKey, image: PendingDownloadedImage, manifestBytes: ByteArray,
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
internal fun ThreadPage.downloadManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest {
    val total = pageNav?.totalPages ?: 1
    return ThreadPageDownloadManifest(key, thread.title, currentTimeMillis(), total,
        if (key.page >= total) DownloadPageKind.LastAtDownloadTime else DownloadPageKind.Normal,
        thread.forum.fid.value, thread.forum.name)
}

/** Keep a readable page discoverable by a rescan even when its manifest is lost. */
internal suspend fun DownloadStorageProvider.recoverListing(directory: String, json: Json): ThreadPageDownloadManifest? {
    val parts = threadDirectory.matchEntire(directory.removeSuffix(".previous"))?.groupValues ?: return null
    val author = parts[3].takeUnless { it == "all" }?.let { it.toIntOrNull() ?: return null }
    val key = ThreadPageDownloadKey(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null, author)
    val bytes = readThreadPage(key) ?: return null
    val page = runCatching { json.decodeFromString<ThreadPage>(bytes.decodeToString()) }.getOrNull() ?: return null
    return page.takeIf { it.thread.tid.value == key.tid }?.downloadManifest(key)
}
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

@Serializable
internal data class CatalogEdit(val removed: Set<DownloadTaskKey>, val replacement: DownloadedContentGroup?)

/** App-private, rebuildable index. Interrupted storage operations leave the dirty marker in place. */
internal class DownloadCatalogIndex(root: Path, storage: String) {
    private val fs = FileSystem.SYSTEM
    private val dir = root / storage.encodeUtf8().sha256().hex()
    private val snapshot = dir / "catalog-v1.json"
    private val journal = dir / "updates.jsonl"
    private val dirty = dir / "pending"

    fun read(apply: (List<DownloadedContentGroup>, List<CatalogEdit>) -> List<DownloadedContentGroup>): List<DownloadedContentGroup>? {
        if (fs.exists(dirty) || !fs.exists(snapshot)) return null
        return runCatching {
            val groups = fs.read(snapshot) { Json.decodeFromString<List<DownloadedContentGroup>>(readUtf8()) }
            if (!fs.exists(journal)) return@runCatching groups
            val edits = fs.read(journal) {
                buildList<CatalogEdit> { while (!exhausted()) add(Json.decodeFromString(readUtf8LineStrict())) }
            }
            apply(groups, edits)
        }.onFailure { Logger.w("DownloadCatalog", "Rebuilding unreadable index", it) }.getOrNull()
    }

    fun begin() {
        try {
            fs.createDirectories(dir)
            fs.write(dirty) { writeByte(1) }
        } catch (error: okio.IOException) {
            // Freeing downloads must still work on a full disk. Removing the old snapshot also forces recovery.
            fs.delete(snapshot)
            Logger.w("DownloadCatalog", "Invalidated snapshot without a journal marker", error)
        }
    }

    fun commit(groups: List<DownloadedContentGroup>, edit: CatalogEdit? = null) {
        runCatching {
            if (edit == null || !fs.exists(snapshot) || (fs.metadataOrNull(journal)?.size ?: 0L) >= 256 * 1024) {
                begin()
                val pending = dir / "snapshot.tmp"
                fs.write(pending) { writeUtf8(Json.encodeToString(groups)) }
                fs.atomicMove(pending, snapshot)
                fs.delete(journal)
            } else {
                fs.appendingSink(journal).buffer().use { it.writeUtf8(Json.encodeToString(edit)).writeByte(10) }
            }
            fs.delete(dirty)
        }.onFailure {
            // A later mutation must checkpoint the complete in-memory catalog before trusting this journal again.
            fs.delete(snapshot)
            Logger.w("DownloadCatalog", "Could not persist download index", it)
        }
    }
}
