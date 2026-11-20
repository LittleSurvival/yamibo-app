package me.thenano.yamibo.yamibo_app.repository.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceGetStatusAtIndex
import platform.ImageIO.kCGImageStatusComplete
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.okio.asSeekableSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

class IOSDownloadStorageProvider(
    private val appSettingsRepository: AppSettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DownloadStorageProvider {
    private val fileSystem = FileSystem.SYSTEM

    override suspend fun getSelectedFolderLabel(): String? =
        runCatching { selectedFolder().name }.getOrNull()

    private val documentsDir by lazy { resolveIosStorageFolder("").parent!! }

    private fun storageKeyFor(folder: Path): String {
        // The sandbox container UUID can change on an app update. Covers retain this key across launches.
        val path = folder.toString()
        val documents = documentsDir.toString()
        return when {
            path == documents -> "ios:Documents"
            path.startsWith("$documents/") -> "ios:Documents/" + path.removePrefix("$documents/")
            else -> path
        }
    }

    override suspend fun getStorageKey(): String = storageKeyFor(selectedFolder())

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching { fileSystem.createDirectories(rootDir()) }
            .onFailure { Logger.w(TAG, "Download folder is unavailable", it) }
            .isSuccess
    }

    private fun itemPath(key: DownloadTaskKey, root: Path = rootDir()): Path {
        val parent = key.parentDirectory?.let { root / it } ?: root
        return parent / key.itemDirectory
    }

    private fun readablePath(key: DownloadTaskKey): Path {
        val final = itemPath(key)
        return if (fileSystem.exists(final)) final else final.parent!! / "${final.name}.previous"
    }

    override suspend fun publishItem(storageKey: String, key: DownloadTaskKey, manifest: ByteArray, page: ByteArray?,
                                     archive: Path?, verifyArchive: ((SeekableSource) -> Unit)?) {
        val folder = selectedFolder().also { check(storageKeyFor(it) == storageKey) }
        val final = itemPath(key, folder / "YamiboDownloads")
        val parent = final.parent!!
        val tmp = parent / "${final.name}.tmp"
        val previous = parent / "${final.name}.previous"
        if (fileSystem.exists(previous)) {
            if (fileSystem.exists(final)) fileSystem.deleteRecursively(previous) else fileSystem.atomicMove(previous, final)
        }
        if (fileSystem.exists(tmp)) fileSystem.deleteRecursively(tmp)
        fileSystem.createDirectories(tmp)
        try {
            if (archive != null) {
                fileSystem.copy(archive, tmp / "images.cbz")
                verifyArchive?.invoke(fileSystem.openReadOnly(tmp / "images.cbz").asSeekableSource())
            }
            if (page != null) write(tmp / "thread_page.json", page)
            write(tmp / "manifest.json", manifest)
            check(getStorageKey() == storageKey)
            if (fileSystem.exists(final)) fileSystem.atomicMove(final, previous)
            fileSystem.atomicMove(tmp, final)
        } catch (error: Throwable) {
            if (!fileSystem.exists(final) && fileSystem.exists(previous)) fileSystem.atomicMove(previous, final)
            throw error
        } finally {
            if (fileSystem.exists(tmp)) fileSystem.deleteRecursively(tmp)
        }
        if (fileSystem.exists(previous)) fileSystem.deleteRecursively(previous)
    }

    override suspend fun looseImages(key: DownloadTaskKey, names: List<String>?): Map<String, String> {
        if (names != null && names.isEmpty()) return emptyMap()
        val dir = readablePath(key) / "images"
        return (names ?: fileSystem.listOrNull(dir).orEmpty().map { it.name }).mapNotNull { name -> (dir / name).takeIf(fileSystem::exists)?.let { name to "file://$it" } }.toMap()
    }

    override fun openLoose(uri: String) = fileSystem.source(uri.removePrefix("file://").toPath())

    override suspend fun openArchive(key: DownloadTaskKey, generation: String): SeekableSource? {
        val path = readablePath(key) / "images.cbz"
        return if (fileSystem.exists(path)) fileSystem.openReadOnly(path).asSeekableSource() else null
    }

    override suspend fun writeImage(
        storageKey: String, key: DownloadTaskKey, image: PendingDownloadedImage, manifestBytes: ByteArray,
    ): String {
        check(getStorageKey() == storageKey)
        val page = readablePath(key)
        check(fileSystem.exists(page / "manifest.json") || (key is ThreadPageDownloadKey && fileSystem.exists(page / "thread_page.json")))
        val images = page / "images"
        fileSystem.createDirectories(images)
        val file = images / image.fileName
        val pending = images / "${image.fileName}.tmp"
        write(pending, image.bytes)
        fileSystem.atomicMove(pending, file)
        write(page / "manifest.json.tmp", manifestBytes)
        fileSystem.atomicMove(page / "manifest.json.tmp", page / "manifest.json")
        return "file://$file"
    }

    override suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray? {
        val path = readablePath(key) / "thread_page.json"
        if (!fileSystem.exists(path)) return null
        return fileSystem.source(path).buffer().use { it.readByteArray() }
    }

    override suspend fun updateThreadPage(key: ThreadPageDownloadKey, manifestBytes: ByteArray, threadPageBytes: ByteArray) {
        val pageDir = readablePath(key)
        write(pageDir / "thread_page.json.tmp", threadPageBytes)
        write(pageDir / "manifest.json.tmp", manifestBytes)
        fileSystem.atomicMove(pageDir / "thread_page.json.tmp", pageDir / "thread_page.json")
        fileSystem.atomicMove(pageDir / "manifest.json.tmp", pageDir / "manifest.json")
    }

    override suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? =
        readManifest(readablePath(key))

    private fun completedDirectories(parent: Path, prefix: String): List<Path> {
        val children = fileSystem.list(parent).filter { it.name.startsWith(prefix) && !it.name.endsWith(".tmp") }
        val current = children.filterNot { it.name.endsWith(".previous") }.associateByTo(linkedMapOf()) { it.name }
        for (child in children.filter { it.name.endsWith(".previous") }) current.getOrPut(child.name.removeSuffix(".previous")) { child }
        return current.values.toList()
    }

    override suspend fun listManifests(): List<ThreadPageDownloadManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return completedDirectories(root, "thread_")
            .mapNotNull { readManifest(it) ?: recoverListing(it.name, json) }
    }

    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? =
        readTagMangaManifest(readablePath(key))

    override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root)
            .filter { it.name.startsWith("tag_manga_") }
            .flatMap { tagDir -> completedDirectories(tagDir, "chapter_").mapNotNull(::readTagMangaManifest) }
    }

    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? =
        readRssMangaManifest(readablePath(key))

    override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root)
            .filter { it.name.startsWith("rss_") }
            .flatMap { rssDir -> completedDirectories(rssDir, "chapter_").mapNotNull(::readRssMangaManifest) }
    }

    override suspend fun readQueue(): List<DownloadQueueEntry> {
        val path = rootDir() / QUEUE_FILE
        if (!fileSystem.exists(path)) return emptyList()
        val bytes = fileSystem.source(path).buffer().use { it.readByteArray() }
        return runCatching { json.decodeFromString<List<DownloadQueueEntry>>(bytes.decodeToString()) }
            .onFailure { Logger.w(TAG, "Failed to decode download queue", it) }
            .getOrDefault(emptyList())
    }

    override suspend fun writeQueue(entries: List<DownloadQueueEntry>) {
        val root = rootDir()
        fileSystem.createDirectories(root)
        val target = root / QUEUE_FILE
        val tmp = root / "$QUEUE_FILE.tmp"
        write(tmp, json.encodeToString(entries).encodeToByteArray())
        fileSystem.atomicMove(tmp, target)
    }

    override suspend fun groupKeys(storageKey: String, group: DownloadTaskKey): Set<DownloadTaskKey> {
        val root = selectedFolder().also { check(storageKeyFor(it) == storageKey) } / "YamiboDownloads"
        val parent = group.parentDirectory?.let { root / it } ?: root
        return fileSystem.listOrNull(parent).orEmpty().mapNotNullTo(mutableSetOf()) { groupMember(group, it.name) }
    }

    override suspend fun deleteItems(storageKey: String, keys: Set<DownloadTaskKey>, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> {
        val folder = selectedFolder().also { check(storageKeyFor(it) == storageKey) }
        val root = folder / "YamiboDownloads"
        val errors = mutableMapOf<DownloadTaskKey, Throwable>()
        for ((index, key) in keys.withIndex()) {
            val parent = key.parentDirectory?.let { root / it } ?: root
            try {
                for (name in itemDirectoryNames(key)) {
                    val path = parent / name
                    if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
                    check(!fileSystem.exists(path)) { "下載內容未完全刪除，請重試" }
                }
            } catch (error: Exception) {
                errors[key] = error
            }
            onProgress(index + 1)
        }
        return errors
    }

    private fun write(path: Path, bytes: ByteArray) {
        fileSystem.sink(path).buffer().use { it.write(bytes) }
    }

    private fun readManifest(dir: Path): ThreadPageDownloadManifest? {
        val path = dir / "manifest.json"
        if (!fileSystem.exists(path)) return null
        val bytes = fileSystem.source(path).buffer().use { it.readByteArray() }
        return runCatching { json.decodeFromString<ThreadPageDownloadManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode thread page manifest", it) }
            .getOrNull()
    }

    private fun readTagMangaManifest(dir: Path): TagMangaChapterManifest? {
        val path = dir / "manifest.json"
        if (!fileSystem.exists(path)) return null
        val bytes = fileSystem.source(path).buffer().use { it.readByteArray() }
        return runCatching { json.decodeFromString<TagMangaChapterManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode tag manga manifest", it) }
            .getOrNull()
    }

    private fun readRssMangaManifest(dir: Path): RssMangaChapterManifest? {
        val path = dir / "manifest.json"
        if (!fileSystem.exists(path)) return null
        val bytes = fileSystem.source(path).buffer().use { it.readByteArray() }
        return runCatching { json.decodeFromString<RssMangaChapterManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode RSS manga manifest", it) }
            .getOrNull()
    }

    private fun rootDir(): Path = selectedFolder() / "YamiboDownloads"

    private fun selectedFolder(): Path = resolveIosStorageFolder(appSettingsRepository.backupFolderUri.getValue())

    private companion object {
        const val TAG = "IOSDownloadStorageProvider"
        const val QUEUE_FILE = "queue.json"
    }
}

/** Resolve the current sandbox each time; its absolute path must not become a synced preference. */
internal fun resolveIosStorageFolder(stored: String): Path {
    val folder = if (stored.isNotBlank()) {
        stored.toPath()
    } else {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        check(!documents.isNullOrBlank()) { "App Documents directory is unavailable" }
        documents.toPath() / "YamiboApp"
    }
    require(folder.isAbsolute) { "Storage folder must be an absolute path" }
    return folder
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isReadableDownloadedBitmap(bytes: ByteArray): Boolean = bytes.usePinned { pinned ->
    val data = CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()) ?: return@usePinned false
    try {
        val source = CGImageSourceCreateWithData(data, null) ?: return@usePinned false
        try {
            val image = CGImageSourceCreateImageAtIndex(source, 0uL, null) ?: return@usePinned false
            CFRelease(image)
            CGImageSourceGetStatusAtIndex(source, 0uL) == kCGImageStatusComplete
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(data)
    }
}
