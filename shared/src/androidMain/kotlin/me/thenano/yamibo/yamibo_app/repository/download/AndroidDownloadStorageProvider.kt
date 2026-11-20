package me.thenano.yamibo.yamibo_app.repository.download

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import no.synth.kmpzip.io.SeekableSource
import no.synth.kmpzip.okio.asSeekableSource
import okio.source
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.nio.ByteBuffer
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository

class AndroidDownloadStorageProvider(
    context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DownloadStorageProvider {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun getSelectedFolderLabel(): String? =
        selectedTreeUri()?.let { queryDisplayName(rootDocumentUri(it)) }

    override suspend fun getStorageKey(): String? = selectedTreeUri()?.toString()

    override suspend fun isReady(): Boolean =
        selectedTreeUri()?.let { uri ->
            resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } == true

    private val archiveCache = File(context.cacheDir, "download-archive-cache")

    private fun itemDir(tree: Uri, key: DownloadTaskKey): Uri? {
        val root = downloadsRoot(tree) ?: return null
        val parent = key.parentDirectory?.let { findChild(tree, root, it) ?: return null } ?: root
        return findChild(tree, parent, key.itemDirectory) ?: findChild(tree, parent, "${key.itemDirectory}.previous")
    }

    override suspend fun publishItem(storageKey: String, key: DownloadTaskKey, manifest: ByteArray, page: ByteArray?,
                                     archive: Path?, verifyArchive: ((SeekableSource) -> Unit)?) {
        val tree = checkNotNull(selectedTreeUri()).also { check(it.toString() == storageKey) }
        val root = ensureDirectory(tree, rootDocumentUri(tree), ROOT_DIR)
        val parent = key.parentDirectory?.let { ensureDirectory(tree, root, it) } ?: root
        val name = key.itemDirectory
        val children = listChildren(tree, parent).associateBy { it.name }
        var current = children[name]?.uri
        children["$name.previous"]?.uri?.let { previous ->
            if (current == null) current = checkNotNull(DocumentsContract.renameDocument(resolver, previous, name))
            else check(DocumentsContract.deleteDocument(resolver, previous))
        }
        children["$name.tmp"]?.let { check(DocumentsContract.deleteDocument(resolver, it.uri)) }
        val tmp = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "$name.tmp"))
        var previous: Uri? = null
        try {
            if (archive != null) {
                val packed = checkNotNull(DocumentsContract.createDocument(resolver, tmp, "application/zip", "images.cbz"))
                checkNotNull(resolver.openOutputStream(packed, "wt")).use { output -> archive.toFile().inputStream().use { it.copyTo(output) } }
                verifyArchive?.invoke(seekable(packed, archive.name))
            }
            if (page != null) writeFile(tree, tmp, "thread_page.json", "application/json", page)
            writeFile(tree, tmp, "manifest.json", "application/json", manifest)
            check(getStorageKey() == storageKey)
            previous = current?.let { checkNotNull(DocumentsContract.renameDocument(resolver, it, "$name.previous")) }
            checkNotNull(DocumentsContract.renameDocument(resolver, tmp, name))
        } catch (error: Throwable) {
            previous?.let { renameDocumentSafely(it, name, "restore $name") }
            deleteDocumentSafely(tmp, "discard incomplete $name")
            throw error
        }
        previous?.let { deleteDocumentSafely(it, "clean previous $name") }
    }

    override suspend fun looseImages(key: DownloadTaskKey, names: List<String>?): Map<String, String> {
        if (names != null && names.isEmpty()) return emptyMap()
        val tree = selectedTreeUri() ?: return emptyMap()
        val dir = itemDir(tree, key) ?: return emptyMap()
        val images = findChild(tree, dir, IMAGES_DIR) ?: return emptyMap()
        val wanted = names?.toHashSet()
        return listChildren(tree, images).filter { wanted == null || it.name in wanted }.associate { it.name to it.uri.toString() }
    }

    override fun openLoose(uri: String) = checkNotNull(resolver.openInputStream(Uri.parse(uri))).source()

    override suspend fun openArchive(key: DownloadTaskKey, generation: String): SeekableSource? {
        val tree = selectedTreeUri() ?: return null
        val dir = itemDir(tree, key) ?: return null
        val file = findChild(tree, dir, "images.cbz") ?: return null
        return seekable(file, generation)
    }

    private fun seekable(file: Uri, generation: String): SeekableSource {
        val fd = checkNotNull(resolver.openFileDescriptor(file, "r"))
        val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        val channel = input.channel
        try {
            val length = channel.size()
            if (length > 0 && channel.read(ByteBuffer.allocate(1), 0) == 1) return object : SeekableSource {
                override val size = length
                override fun read(position: Long, into: ByteArray, offset: Int, length: Int) =
                    channel.read(ByteBuffer.wrap(into, offset, length), position)
                override fun close() = input.close()
            }
        } catch (_: java.io.IOException) {
            // A documents provider may return a pipe instead of a seekable descriptor.
        }
        input.close()
        archiveCache.mkdirs()
        val cache = File(archiveCache, (file.toString() + generation).encodeUtf8().sha256().hex())
        if (!cache.exists()) {
            val partial = File.createTempFile("reading-", ".tmp", archiveCache)
            try {
                checkNotNull(resolver.openInputStream(file)).use { source -> partial.outputStream().use { source.copyTo(it) } }
                check(partial.renameTo(cache))
            } finally {
                partial.delete()
            }
        }
        cache.setLastModified(System.currentTimeMillis())
        val handle = FileSystem.SYSTEM.openReadOnly(cache.path.toPath()).asSeekableSource()
        var used = 0L
        archiveCache.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            used += it.length()
            if (used > 256L * 1024 * 1024) it.delete()
        }
        return handle
    }

    override suspend fun writeThreadImage(
        storageKey: String, key: ThreadPageDownloadKey, image: PendingDownloadedImage, manifestBytes: ByteArray,
    ): String {
        val tree = requireNotNull(selectedTreeUri()).also { check(it.toString() == storageKey) }
        val page = requireNotNull(itemDir(tree, key))
        val images = ensureDirectory(tree, page, IMAGES_DIR)
        val pending = writeFile(tree, images, "${image.fileName}.tmp", "application/octet-stream", image.bytes)
        deleteChildByName(tree, images, image.fileName)
        val file = checkNotNull(DocumentsContract.renameDocument(resolver, pending, image.fileName))
        replaceFile(tree, page, "manifest.json", manifestBytes)
        return file.toString()
    }

    override suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray? {
        val treeUri = selectedTreeUri() ?: return null
        val pageDir = itemDir(treeUri, key) ?: return null
        val file = findChild(treeUri, pageDir, "thread_page.json") ?: findChild(treeUri, pageDir, "thread_page.json.previous") ?: return null
        return resolver.openInputStream(file)?.use { it.readBytes() }
    }

    override suspend fun readPageRecord(key: ThreadPageDownloadKey): Pair<ByteArray, ThreadPageDownloadManifest?>? {
        val tree = selectedTreeUri() ?: return null
        val dir = itemDir(tree, key) ?: return null
        val files = listChildren(tree, dir).associateBy { it.name }
        val page = (files["thread_page.json"] ?: files["thread_page.json.previous"])?.let { resolver.openInputStream(it.uri)?.use { stream -> stream.readBytes() } } ?: return null
        val manifest = (files["manifest.json"] ?: files["manifest.json.previous"])?.let { file ->
            resolver.openInputStream(file.uri)?.bufferedReader()?.use { input -> runCatching { json.decodeFromString<ThreadPageDownloadManifest>(input.readText()) }.getOrNull() }
        }
        return page to manifest
    }

    override suspend fun updateThreadPage(key: ThreadPageDownloadKey, manifestBytes: ByteArray, threadPageBytes: ByteArray) {
        val treeUri = selectedTreeUri() ?: error("尚未選擇備份資料夾")
        val pageDir = itemDir(treeUri, key)
            ?: error("下載內容不存在")
        replaceFile(treeUri, pageDir, "thread_page.json", threadPageBytes)
        replaceFile(treeUri, pageDir, "manifest.json", manifestBytes)
    }

    override suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? {
        val treeUri = selectedTreeUri() ?: return null
        val pageDir = itemDir(treeUri, key) ?: return null
        return readManifestFromDir(treeUri, pageDir)
    }

    override suspend fun listManifests(): List<ThreadPageDownloadManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return completedDirectories(treeUri, root, "thread_")
            .mapNotNull { readManifestFromDir(treeUri, it.uri) }
    }

    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = itemDir(treeUri, key) ?: return@withContext null
        return@withContext readTagMangaManifestFromDir(treeUri, chapterDir)
    }

    override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return listChildren(treeUri, root)
            .filter { it.name.startsWith("tag_manga_") }
            .flatMap { tagDir ->
                completedDirectories(treeUri, tagDir.uri, "chapter_").mapNotNull { readTagMangaManifestFromDir(treeUri, it.uri) }
            }
    }

    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = itemDir(treeUri, key) ?: return@withContext null
        return@withContext readRssMangaManifestFromDir(treeUri, chapterDir)
    }

    override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return listChildren(treeUri, root)
            .filter { it.name.startsWith("rss_") }
            .flatMap { rssDir ->
                completedDirectories(treeUri, rssDir.uri, "chapter_").mapNotNull { readRssMangaManifestFromDir(treeUri, it.uri) }
            }
    }

    override suspend fun readQueue(): List<DownloadQueueEntry> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        val file = findChild(treeUri, root, QUEUE_FILE) ?: findChild(treeUri, root, "$QUEUE_FILE.previous") ?: return emptyList()
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<DownloadQueueEntry>>(bytes.decodeToString()) }
            .onFailure { Logger.w(TAG, "Failed to decode download queue", it) }
            .getOrDefault(emptyList())
    }

    override suspend fun writeQueue(entries: List<DownloadQueueEntry>) {
        val treeUri = selectedTreeUri() ?: return
        val root = ensureDirectory(treeUri, rootDocumentUri(treeUri), ROOT_DIR)
        replaceFile(treeUri, root, QUEUE_FILE, json.encodeToString(entries).encodeToByteArray())
    }

    override suspend fun groupKeys(storageKey: String, group: DownloadTaskKey): Set<DownloadTaskKey> {
        val tree = checkNotNull(selectedTreeUri()).also { check(it.toString() == storageKey) }
        val root = downloadsRoot(tree) ?: return emptySet()
        val parent = group.parentDirectory?.let { findChild(tree, root, it) ?: return emptySet() } ?: root
        return listChildren(tree, parent).mapNotNullTo(mutableSetOf()) { groupMember(group, it.name) }
    }

    override suspend fun deleteItems(storageKey: String, keys: Set<DownloadTaskKey>, onProgress: (Int) -> Unit): Map<DownloadTaskKey, Throwable> {
        val tree = checkNotNull(selectedTreeUri()).also { check(it.toString() == storageKey) }
        val root = downloadsRoot(tree) ?: return emptyMap()
        val roots = listChildren(tree, root).associateBy { it.name }
        var completed = 0
        val errors = mutableMapOf<DownloadTaskKey, Throwable>()
        for ((parentName, group) in keys.groupBy { it.parentDirectory }) {
            val parent = if (parentName == null) root else roots[parentName]?.uri ?: continue
            try {
                val before = if (parentName == null) roots else listChildren(tree, parent).associateBy { it.name }
                for (key in group) {
                    try {
                        for (name in itemDirectoryNames(key)) {
                            before[name]?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
                        }
                    } catch (error: Exception) {
                        errors[key] = error
                    }
                    onProgress(++completed)
                }
                val remaining = listChildren(tree, parent).mapTo(hashSetOf()) { it.name }
                for (key in group) {
                    if (itemDirectoryNames(key).none { it in remaining }) errors.remove(key)
                    else errors.getOrPut(key) { IllegalStateException("下載內容未完全刪除，請重試") }
                }
            } catch (error: Exception) {
                group.forEach { errors[it] = error }
            }
        }
        return errors
    }

    private fun selectedTreeUri(): Uri? =
        appSettingsRepository.backupFolderUri.getValue().takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun downloadsRoot(treeUri: Uri): Uri? =
        findChild(treeUri, rootDocumentUri(treeUri), ROOT_DIR)

    private fun completedDirectories(tree: Uri, parent: Uri, prefix: String): List<DocumentChild> {
        val children = listChildren(tree, parent).filter { it.name.startsWith(prefix) && !it.name.endsWith(".tmp") }
        val current = children.filterNot { it.name.endsWith(".previous") }.associateByTo(linkedMapOf()) { it.name }
        for (child in children.filter { it.name.endsWith(".previous") }) {
            val name = child.name.removeSuffix(".previous")
            if (name !in current) current[name] = child
        }
        return current.values.toList()
    }

    private fun ensureDirectory(treeUri: Uri, parent: Uri, name: String): Uri =
        findChild(treeUri, parent, name)
            ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("無法建立資料夾：$name")

    private fun replaceFile(treeUri: Uri, parent: Uri, name: String, bytes: ByteArray) {
        val children = listChildren(treeUri, parent).associateBy { it.name }
        val backup = children["$name.previous"]?.uri
        var current = children[name]?.uri
        if (backup != null) {
            if (current == null) current = checkNotNull(DocumentsContract.renameDocument(resolver, backup, name))
            else check(DocumentsContract.deleteDocument(resolver, backup))
        }
        val pending = writeFile(treeUri, parent, "$name.tmp", "application/json", bytes)
        val previous = current?.let { checkNotNull(DocumentsContract.renameDocument(resolver, it, "$name.previous")) }
        try {
            checkNotNull(DocumentsContract.renameDocument(resolver, pending, name))
        } catch (error: Exception) {
            previous?.let { renameDocumentSafely(it, name, "restore $name") }
            throw error
        }
        previous?.let { deleteDocumentSafely(it, "clean previous $name") }
    }

    private fun writeFile(treeUri: Uri, parent: Uri, name: String, mimeType: String, bytes: ByteArray): Uri {
        deleteChildByName(treeUri, parent, name)
        val file = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: error("無法建立檔案：$name")
        resolver.openOutputStream(file, "wt")?.use { it.write(bytes) }
            ?: error("無法寫入檔案：$name")
        return file
    }

    private fun readManifestFromDir(treeUri: Uri, dir: Uri): ThreadPageDownloadManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: findChild(treeUri, dir, "manifest.json.previous") ?: return null
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString<ThreadPageDownloadManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode thread page manifest", it) }
            .getOrNull()
    }

    private fun readTagMangaManifestFromDir(treeUri: Uri, dir: Uri): TagMangaChapterManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: findChild(treeUri, dir, "manifest.json.previous") ?: return null
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString<TagMangaChapterManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode tag manga manifest", it) }
            .getOrNull()
    }

    private fun readRssMangaManifestFromDir(treeUri: Uri, dir: Uri): RssMangaChapterManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: findChild(treeUri, dir, "manifest.json.previous") ?: return null
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString<RssMangaChapterManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode RSS manga manifest", it) }
            .getOrNull()
    }

    private fun deleteChildByName(treeUri: Uri, parent: Uri, name: String) {
        findChild(treeUri, parent, name)?.let { deleteDocumentSafely(it, "delete child name=$name") }
    }

    private fun deleteDocumentSafely(uri: Uri, operation: String) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Logger.w(TAG, "$operation failed", it) }
    }

    private fun renameDocumentSafely(uri: Uri, newName: String, operation: String) {
        runCatching { DocumentsContract.renameDocument(resolver, uri, newName) }
            .onFailure { Logger.w(TAG, "$operation failed", it) }
    }

    private fun findChild(treeUri: Uri, parent: Uri, name: String): Uri? =
        listChildren(treeUri, parent).firstOrNull { it.name == name }?.uri

    private fun listChildren(treeUri: Uri, parent: Uri): List<DocumentChild> {
        val docId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val items = mutableListOf<DocumentChild>()
        try {
            checkNotNull(resolver.query(childrenUri, projection, null, null, null)) { "無法讀取下載資料夾" }.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex)
                    val childName = cursor.getString(nameIndex).orEmpty()
                    items += DocumentChild(childName, DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                }
            }
        } catch (error: Exception) {
            if (!error.isMissingDocumentFailure()) throw error
            Logger.d(TAG, "Document disappeared while listing children uri=$parent", error)
        }
        return items
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private data class DocumentChild(val name: String, val uri: Uri)

    private companion object {
        const val TAG = "AndroidDownloadStorageProvider"
        const val ROOT_DIR = "YamiboDownloads"
        const val IMAGES_DIR = "images"
        const val QUEUE_FILE = "queue.json"
    }
}
internal fun isCompletedThreadDownloadDirectory(name: String): Boolean =
    name.startsWith("thread_") &&
        !name.endsWith(".tmp") &&
        !name.endsWith(".previous")

internal fun Throwable.isMissingDocumentFailure(): Boolean {
    var error: Throwable? = this
    while (error != null) {
        if (error is FileNotFoundException) return true
        error = error.cause
    }
    return false
}

internal actual fun isReadableDownloadedBitmap(bytes: ByteArray): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        return try {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / 256).coerceAtLeast(1))
            }.recycle()
            true
        } catch (_: java.io.IOException) {
            false
        }
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
    bitmap.recycle()
    return true
}
