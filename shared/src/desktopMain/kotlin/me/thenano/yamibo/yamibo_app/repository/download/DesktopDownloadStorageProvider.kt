package me.thenano.yamibo.yamibo_app.repository.download

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.util.Comparator
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository

/** Uses the same manifests as Android, with transactional per-chapter replacement on local disks. */
class DesktopDownloadStorageProvider(
    private val settings: AppSettingsRepository,
    private val folderUri: String? = null,
    private val onReadFailure: () -> Unit = {},
) : DownloadStorageProvider {
    private val mutex = Mutex()
    private var readFailureReported = false
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun getSelectedFolderLabel(): String? = selected()?.toString()
    override suspend fun isReady() = withContext(Dispatchers.IO) {
        selected()?.let { Files.isDirectory(it) && Files.isWritable(it) } == true
    }

    override suspend fun writeThreadPage(key: ThreadPageDownloadKey, manifestBytes: ByteArray,
        threadPageBytes: ByteArray, images: List<PendingDownloadedImage>) = io {
        replace(directory(key.stableId), manifestBytes, images, threadPageBytes)
    }
    override suspend fun writeTagMangaChapter(key: TagMangaChapterDownloadKey, manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>) = io { replace(directory(key.tagStableId, key.chapterStableId), manifestBytes, images) }
    override suspend fun writeRssMangaChapter(key: RssMangaChapterDownloadKey, manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>) = io { replace(directory(key.rssStableId, key.chapterStableId), manifestBytes, images) }
    override suspend fun readThreadPage(key: ThreadPageDownloadKey) = readIfConfigured { read(directory(key.stableId).resolve("thread_page.json")) }
    override suspend fun readManifest(key: ThreadPageDownloadKey) = readIfConfigured { manifest<ThreadPageDownloadManifest>(directory(key.stableId)) }
    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey) = readIfConfigured { manifest<TagMangaChapterManifest>(directory(key.tagStableId, key.chapterStableId)) }
    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey) = readIfConfigured { manifest<RssMangaChapterManifest>(directory(key.rssStableId, key.chapterStableId)) }
    override suspend fun resolveImageUri(key: ThreadPageDownloadKey, fileName: String) = readIfConfigured { image(directory(key.stableId), fileName) }
    override suspend fun resolveTagMangaImageUri(key: TagMangaChapterDownloadKey, fileName: String) = readIfConfigured { image(directory(key.tagStableId, key.chapterStableId), fileName) }
    override suspend fun resolveRssMangaImageUri(key: RssMangaChapterDownloadKey, fileName: String) = readIfConfigured { image(directory(key.rssStableId, key.chapterStableId), fileName) }

    override suspend fun listManifests(): List<ThreadPageDownloadManifest> = readIfConfigured {
        completedChildren(root()).filter { it.fileName.toString().startsWith("thread_") }
            .mapNotNull { optionalRead { manifest<ThreadPageDownloadManifest>(it) } }
    }.orEmpty()
    override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> = readIfConfigured {
        chapterDirectories("tag_manga_").mapNotNull { optionalRead { manifest<TagMangaChapterManifest>(it) } }
    }.orEmpty()
    override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> = readIfConfigured {
        chapterDirectories("rss_").mapNotNull { optionalRead { manifest<RssMangaChapterManifest>(it) } }
    }.orEmpty()
    override suspend fun readQueue(): List<DownloadQueueEntry> = io {
        if (selected() == null) emptyList() else read(root().resolve("queue.json"))?.let {
            json.decodeFromString<List<DownloadQueueEntry>>(it.decodeToString())
        }.orEmpty()
    }
    override suspend fun writeQueue(entries: List<DownloadQueueEntry>) = io<Unit> {
        val target = root().resolve("queue.json")
        safe(target)
        // A failed startup read must never be followed by replacing an unreadable queue with an
        // empty/new one. Preserve it for recovery; a missing file is a valid first-run state.
        read(target)?.let { json.decodeFromString<List<DownloadQueueEntry>>(it.decodeToString()) }
        val temp = Files.createTempFile(target.parent, ".queue-", ".tmp")
        try {
            Files.write(temp, json.encodeToString(entries).encodeToByteArray())
            try { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    override suspend fun deleteThreadPage(key: ThreadPageDownloadKey) = io { delete(directory(key.stableId)) }
    override suspend fun deleteThread(key: ThreadPageDownloadKey) = io {
        completedChildren(root()).filter { it.fileName.toString().startsWith(key.threadPrefix) }.forEach(::delete)
    }
    override suspend fun deleteTagMangaChapter(key: TagMangaChapterDownloadKey) = io { delete(directory(key.tagStableId, key.chapterStableId)) }
    override suspend fun deleteTagManga(tagId: Int) = io { delete(directory("tag_manga_$tagId")) }
    override suspend fun deleteRssMangaChapter(key: RssMangaChapterDownloadKey) = io { delete(directory(key.rssStableId, key.chapterStableId)) }
    override suspend fun deleteRssManga(subscriptionId: Long) = io { delete(directory("rss_$subscriptionId")) }

    private fun selected(): Path? = (folderUri ?: settings.backupFolderUri.getValue()).takeIf(String::isNotBlank)?.let {
        if (it.startsWith("file:", true)) Path.of(URI(it)) else Path.of(it)
    }
    private fun root(): Path {
        val folder = requireNotNull(selected()) { "尚未選擇備份資料夾" }.toRealPath()
        val root = folder.resolve("YamiboDownloads")
        require(!Files.isSymbolicLink(root)) { "下載資料夾不能是符號連結" }
        return Files.createDirectories(root)
    }
    private fun safe(path: Path): Path {
        val root = root()
        require(path.normalize().startsWith(root) && path.normalize() != root) { "下載路徑無效" }
        var parent: Path? = path
        while (parent != null && parent != root) {
            require(!Files.isSymbolicLink(parent)) { "下載路徑不能包含符號連結" }
            parent = parent.parent
        }
        return path
    }
    private fun directory(vararg parts: String): Path {
        val dir = parts.fold(root()) { path, part -> path.resolve(part) }
        safe(dir)
        val previous = safe(dir.resolveSibling("${dir.fileName}.previous"))
        if (!Files.exists(dir, NOFOLLOW_LINKS) && Files.exists(previous, NOFOLLOW_LINKS)) Files.move(previous, dir)
        return dir
    }
    private fun replace(target: Path, manifest: ByteArray, images: List<PendingDownloadedImage>, page: ByteArray? = null) {
        images.forEach { require(validFileName(it.fileName)) { "圖片檔名無效" } }
        Files.createDirectories(target.parent)
        val temporary = Files.createTempDirectory(target.parent, ".download-")
        val previous = safe(target.resolveSibling("${target.fileName}.previous"))
        try {
            Files.createDirectory(temporary.resolve("images"))
            images.forEach { Files.write(temporary.resolve("images").resolve(it.fileName), it.bytes) }
            page?.let { Files.write(temporary.resolve("thread_page.json"), it) }
            Files.write(temporary.resolve("manifest.json"), manifest)
            delete(previous)
            if (Files.exists(target, NOFOLLOW_LINKS)) moveDirectory(target, previous)
            try { moveDirectory(temporary, target) }
            catch (error: Exception) {
                if (Files.exists(previous, NOFOLLOW_LINKS)) moveDirectory(previous, target)
                throw error
            }
            delete(previous)
        } finally { delete(temporary) }
    }
    private fun moveDirectory(source: Path, target: Path) {
        // Windows may briefly deny a rename while another process inspects newly written files.
        // Retry only this error, without replacing targets or concealing persistent permission failures.
        repeat(3) { attempt ->
            try {
                Files.move(source, target)
                return
            } catch (error: java.nio.file.AccessDeniedException) {
                if (attempt == 2 || !System.getProperty("os.name").startsWith("Windows", true)) throw error
                Thread.sleep(100)
            }
        }
    }
    private fun validFileName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
    private fun image(dir: Path, name: String): String? {
        require(validFileName(name))
        val path = safe(dir.resolve("images").resolve(name))
        return path.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }?.toUri()?.toString()
    }
    private fun read(path: Path): ByteArray? = safe(path).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }?.let(Files::readAllBytes)
    private inline fun <reified T> manifest(dir: Path): T? = read(dir.resolve("manifest.json"))?.let {
        json.decodeFromString<T>(it.decodeToString())
    }
    private fun completedChildren(parent: Path): List<Path> {
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(parent).use { entries -> entries.filter {
            Files.isDirectory(it, NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".")
        }.collect(Collectors.toList()) }.map { candidate ->
            if (candidate.fileName.toString().endsWith(".previous")) {
                val original = candidate.resolveSibling(candidate.fileName.toString().removeSuffix(".previous"))
                safe(original)
                if (!Files.exists(original, NOFOLLOW_LINKS)) Files.move(candidate, original)
                original
            } else safe(candidate)
        }.distinct()
    }
    private fun chapterDirectories(prefix: String): List<Path> = if (selected() == null) emptyList()
        else completedChildren(root()).filter { it.fileName.toString().startsWith(prefix) }.flatMap(::completedChildren)
    private fun delete(path: Path) {
        safe(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        // walk does not follow directory symlinks; every deletion remains under the validated target.
        Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    private suspend fun <T> readIfConfigured(action: () -> T?): T? = io {
        optionalRead { if (selected() == null) null else action() }
    }
    // Called under mutex; isolate each malformed manifest without discarding healthy list entries.
    private fun <T> optionalRead(action: () -> T?): T? =
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional offline lookup must not terminate the reader when removable storage disappears.
            // Do not rewrite or delete the unreadable data, and report once per application instance.
            if (!readFailureReported) {
                readFailureReported = true
                onReadFailure()
            }
            null
        }
    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { action() } }
}
