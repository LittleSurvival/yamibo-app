package me.thenano.yamibo.yamibo_app.repository.download

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
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
        appSettingsRepository.backupFolderUri.getValue().takeIf { it.isNotBlank() }

    override suspend fun isReady(): Boolean =
        appSettingsRepository.backupFolderUri.getValue().isNotBlank()

    override suspend fun writeThreadPage(
        key: ThreadPageDownloadKey,
        manifestBytes: ByteArray,
        threadPageBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val root = rootDir()
        fileSystem.createDirectories(root)
        val final = root / key.stableId
        val tmp = root / "${key.stableId}.tmp"
        if (fileSystem.exists(tmp)) fileSystem.deleteRecursively(tmp)
        fileSystem.createDirectories(tmp / "images")
        write(tmp / "manifest.json", manifestBytes)
        write(tmp / "thread_page.json", threadPageBytes)
        images.forEach { write(tmp / "images" / it.fileName, it.bytes) }
        if (fileSystem.exists(final)) fileSystem.deleteRecursively(final)
        fileSystem.atomicMove(tmp, final)
    }

    override suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray? {
        val path = rootDir() / key.stableId / "thread_page.json"
        if (!fileSystem.exists(path)) return null
        return fileSystem.source(path).buffer().use { it.readByteArray() }
    }

    override suspend fun resolveImageUri(key: ThreadPageDownloadKey, fileName: String): String? {
        val path = rootDir() / key.stableId / "images" / fileName
        return path.takeIf(fileSystem::exists)?.let { "file://$it" }
    }

    override suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? =
        readManifest(rootDir() / key.stableId)

    override suspend fun listManifests(): List<ThreadPageDownloadManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root)
            .filter { it.name.startsWith("thread_") }
            .mapNotNull(::readManifest)
    }

    override suspend fun writeTagMangaChapter(
        key: TagMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val tagDir = rootDir() / key.tagStableId
        fileSystem.createDirectories(tagDir)
        val final = tagDir / key.chapterStableId
        val tmp = tagDir / "${key.chapterStableId}.tmp"
        if (fileSystem.exists(tmp)) fileSystem.deleteRecursively(tmp)
        fileSystem.createDirectories(tmp / "images")
        write(tmp / "manifest.json", manifestBytes)
        images.forEach { write(tmp / "images" / it.fileName, it.bytes) }
        if (fileSystem.exists(final)) fileSystem.deleteRecursively(final)
        fileSystem.atomicMove(tmp, final)
    }

    override suspend fun resolveTagMangaImageUri(key: TagMangaChapterDownloadKey, fileName: String): String? {
        val path = rootDir() / key.tagStableId / key.chapterStableId / "images" / fileName
        return path.takeIf(fileSystem::exists)?.let { "file://$it" }
    }

    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? =
        readTagMangaManifest(rootDir() / key.tagStableId / key.chapterStableId)

    override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root)
            .filter { it.name.startsWith("tag_manga_") }
            .flatMap { tagDir -> fileSystem.list(tagDir).mapNotNull(::readTagMangaManifest) }
    }

    override suspend fun writeRssMangaChapter(
        key: RssMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val rssDir = rootDir() / key.rssStableId
        fileSystem.createDirectories(rssDir)
        val final = rssDir / key.chapterStableId
        val tmp = rssDir / "${key.chapterStableId}.tmp"
        if (fileSystem.exists(tmp)) fileSystem.deleteRecursively(tmp)
        fileSystem.createDirectories(tmp / "images")
        write(tmp / "manifest.json", manifestBytes)
        images.forEach { write(tmp / "images" / it.fileName, it.bytes) }
        if (fileSystem.exists(final)) fileSystem.deleteRecursively(final)
        fileSystem.atomicMove(tmp, final)
    }

    override suspend fun resolveRssMangaImageUri(key: RssMangaChapterDownloadKey, fileName: String): String? {
        val path = rootDir() / key.rssStableId / key.chapterStableId / "images" / fileName
        return path.takeIf(fileSystem::exists)?.let { "file://$it" }
    }

    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? =
        readRssMangaManifest(rootDir() / key.rssStableId / key.chapterStableId)

    override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> {
        val root = rootDir()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root)
            .filter { it.name.startsWith("rss_") }
            .flatMap { rssDir -> fileSystem.list(rssDir).mapNotNull(::readRssMangaManifest) }
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
        if (fileSystem.exists(target)) fileSystem.delete(target)
        fileSystem.atomicMove(tmp, target)
    }

    override suspend fun deleteThreadPage(key: ThreadPageDownloadKey) {
        val path = rootDir() / key.stableId
        if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
    }

    override suspend fun deleteThread(key: ThreadPageDownloadKey) {
        val root = rootDir()
        if (!fileSystem.exists(root)) return
        fileSystem.list(root)
            .filter { it.name.startsWith(key.threadPrefix) }
            .forEach { fileSystem.deleteRecursively(it) }
    }

    override suspend fun deleteTagMangaChapter(key: TagMangaChapterDownloadKey) {
        val path = rootDir() / key.tagStableId / key.chapterStableId
        if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
    }

    override suspend fun deleteTagManga(tagId: Int) {
        val path = rootDir() / "tag_manga_$tagId"
        if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
    }

    override suspend fun deleteRssMangaChapter(key: RssMangaChapterDownloadKey) {
        val path = rootDir() / key.rssStableId / key.chapterStableId
        if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
    }

    override suspend fun deleteRssManga(subscriptionId: Long) {
        val path = rootDir() / "rss_$subscriptionId"
        if (fileSystem.exists(path)) fileSystem.deleteRecursively(path)
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

    private fun selectedFolder(): Path {
        val stored = appSettingsRepository.backupFolderUri.getValue()
        if (stored.isNotBlank()) return stored.toPath()
        return defaultDocumentsPath() / "YamiboApp"
    }

    private fun defaultDocumentsPath(): Path {
        val path = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        return (path ?: ".").toPath()
    }

    private companion object {
        const val TAG = "IOSDownloadStorageProvider"
        const val QUEUE_FILE = "queue.json"
    }
}
