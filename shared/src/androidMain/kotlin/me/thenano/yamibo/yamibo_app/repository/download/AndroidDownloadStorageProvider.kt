package me.thenano.yamibo.yamibo_app.repository.download

import android.content.Context
import android.net.Uri
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

    override suspend fun isReady(): Boolean =
        selectedTreeUri()?.let { uri ->
            resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } == true

    override suspend fun writeThreadPage(
        key: ThreadPageDownloadKey,
        manifestBytes: ByteArray,
        threadPageBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val treeUri = selectedTreeUri() ?: error("尚未選擇備份資料夾")
        val root = ensureDirectory(treeUri, rootDocumentUri(treeUri), ROOT_DIR)
        val tmpName = "${key.stableId}.tmp"
        deleteChildByName(treeUri, root, tmpName)
        val tmp = ensureDirectory(treeUri, root, tmpName)
        val imagesDir = ensureDirectory(treeUri, tmp, IMAGES_DIR)
        writeFile(treeUri, tmp, "manifest.json", "application/json", manifestBytes)
        writeFile(treeUri, tmp, "thread_page.json", "application/json", threadPageBytes)
        images.forEach { writeFile(treeUri, imagesDir, it.fileName, "application/octet-stream", it.bytes) }
        val previousName = "${key.stableId}.previous"
        deleteChildByName(treeUri, root, previousName)
        val current = findChild(treeUri, root, key.stableId)
        val previous = current?.let { DocumentsContract.renameDocument(resolver, it, previousName) }
        try {
            val renamed = DocumentsContract.renameDocument(resolver, tmp, key.stableId)
                ?: error("儲存提供者不支援完成下載檔案")
            if (renamed == tmp) {
                findChild(treeUri, root, key.stableId) ?: error("下載資料夾重新命名失敗")
            }
            previous?.let { deleteDocumentSafely(it, "delete previous thread page key=${key.stableId}") }
        } catch (error: Throwable) {
            Logger.e(TAG, "writeThreadPage failed key=${key.stableId}", error)
            deleteDocumentSafely(tmp, "cleanup tmp thread page key=${key.stableId}")
            previous?.let { renameDocumentSafely(it, key.stableId, "restore previous thread page key=${key.stableId}") }
            throw error
        }
    }

    override suspend fun readThreadPage(key: ThreadPageDownloadKey): ByteArray? {
        val treeUri = selectedTreeUri() ?: return null
        val pageDir = findChild(treeUri, downloadsRoot(treeUri) ?: return null, key.stableId) ?: return null
        val file = findChild(treeUri, pageDir, "thread_page.json") ?: return null
        return resolver.openInputStream(file)?.use { it.readBytes() }
    }

    override suspend fun resolveImageUri(key: ThreadPageDownloadKey, fileName: String): String? {
        val treeUri = selectedTreeUri() ?: return null
        val pageDir = findChild(treeUri, downloadsRoot(treeUri) ?: return null, key.stableId) ?: return null
        val imagesDir = findChild(treeUri, pageDir, IMAGES_DIR) ?: return null
        return findChild(treeUri, imagesDir, fileName)?.toString()
    }

    override suspend fun readManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? {
        val treeUri = selectedTreeUri() ?: return null
        val pageDir = findChild(treeUri, downloadsRoot(treeUri) ?: return null, key.stableId) ?: return null
        return readManifestFromDir(treeUri, pageDir)
    }

    override suspend fun listManifests(): List<ThreadPageDownloadManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return listChildren(treeUri, root)
            .filter { isCompletedThreadDownloadDirectory(it.name) }
            .mapNotNull { readManifestFromDir(treeUri, it.uri) }
    }

    override suspend fun writeTagMangaChapter(
        key: TagMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val treeUri = selectedTreeUri() ?: error("尚未選擇備份資料夾")
        val root = ensureDirectory(treeUri, rootDocumentUri(treeUri), ROOT_DIR)
        val tagDir = ensureDirectory(treeUri, root, key.tagStableId)
        val tmpName = "${key.chapterStableId}.tmp"
        deleteChildByName(treeUri, tagDir, tmpName)
        val tmp = ensureDirectory(treeUri, tagDir, tmpName)
        val imagesDir = ensureDirectory(treeUri, tmp, IMAGES_DIR)
        writeFile(treeUri, tmp, "manifest.json", "application/json", manifestBytes)
        images.forEach { writeFile(treeUri, imagesDir, it.fileName, "application/octet-stream", it.bytes) }
        val previousName = "${key.chapterStableId}.previous"
        deleteChildByName(treeUri, tagDir, previousName)
        val current = findChild(treeUri, tagDir, key.chapterStableId)
        val previous = current?.let { DocumentsContract.renameDocument(resolver, it, previousName) }
        try {
            DocumentsContract.renameDocument(resolver, tmp, key.chapterStableId)
                ?: error("儲存提供者不支援完成下載檔案")
            previous?.let { deleteDocumentSafely(it, "delete previous tag manga chapter key=${key.stableId}") }
        } catch (error: Throwable) {
            Logger.e(TAG, "writeTagMangaChapter failed key=${key.stableId}", error)
            deleteDocumentSafely(tmp, "cleanup tmp tag manga chapter key=${key.stableId}")
            previous?.let { renameDocumentSafely(it, key.chapterStableId, "restore previous tag manga chapter key=${key.stableId}") }
            throw error
        }
    }

    override suspend fun resolveTagMangaImageUri(key: TagMangaChapterDownloadKey, fileName: String): String? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = findTagMangaChapterDir(treeUri, key) ?: return@withContext null
        val imagesDir = findChild(treeUri, chapterDir, IMAGES_DIR) ?: return@withContext null
        return@withContext findChild(treeUri, imagesDir, fileName)?.toString()
    }

    override suspend fun resolveTagMangaImageUris(
        key: TagMangaChapterDownloadKey,
        fileNames: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext emptyList()
        val chapterDir = findTagMangaChapterDir(treeUri, key) ?: return@withContext emptyList()
        val imagesDir = findChild(treeUri, chapterDir, IMAGES_DIR) ?: return@withContext emptyList()
        val childrenByName = listChildren(treeUri, imagesDir).associateBy { it.name }
        fileNames.mapNotNull { childrenByName[it]?.uri?.toString() }
    }

    override suspend fun readTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = findTagMangaChapterDir(treeUri, key) ?: return@withContext null
        return@withContext readTagMangaManifestFromDir(treeUri, chapterDir)
    }

    override suspend fun listTagMangaManifests(): List<TagMangaChapterManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return listChildren(treeUri, root)
            .filter { it.name.startsWith("tag_manga_") }
            .flatMap { tagDir ->
                listChildren(treeUri, tagDir.uri).mapNotNull { readTagMangaManifestFromDir(treeUri, it.uri) }
            }
    }

    override suspend fun writeRssMangaChapter(
        key: RssMangaChapterDownloadKey,
        manifestBytes: ByteArray,
        images: List<PendingDownloadedImage>,
    ) {
        val treeUri = selectedTreeUri() ?: error("尚未選擇備份資料夾")
        val root = ensureDirectory(treeUri, rootDocumentUri(treeUri), ROOT_DIR)
        val rssDir = ensureDirectory(treeUri, root, key.rssStableId)
        val tmpName = "${key.chapterStableId}.tmp"
        deleteChildByName(treeUri, rssDir, tmpName)
        val tmp = ensureDirectory(treeUri, rssDir, tmpName)
        val imagesDir = ensureDirectory(treeUri, tmp, IMAGES_DIR)
        writeFile(treeUri, tmp, "manifest.json", "application/json", manifestBytes)
        images.forEach { writeFile(treeUri, imagesDir, it.fileName, "application/octet-stream", it.bytes) }
        val previousName = "${key.chapterStableId}.previous"
        deleteChildByName(treeUri, rssDir, previousName)
        val current = findChild(treeUri, rssDir, key.chapterStableId)
        val previous = current?.let { DocumentsContract.renameDocument(resolver, it, previousName) }
        try {
            DocumentsContract.renameDocument(resolver, tmp, key.chapterStableId)
                ?: error("儲存提供者不支援完成下載檔案")
            previous?.let { deleteDocumentSafely(it, "delete previous RSS manga chapter key=${key.stableId}") }
        } catch (error: Throwable) {
            Logger.e(TAG, "writeRssMangaChapter failed key=${key.stableId}", error)
            deleteDocumentSafely(tmp, "cleanup tmp RSS manga chapter key=${key.stableId}")
            previous?.let { renameDocumentSafely(it, key.chapterStableId, "restore previous RSS manga chapter key=${key.stableId}") }
            throw error
        }
    }

    override suspend fun resolveRssMangaImageUri(key: RssMangaChapterDownloadKey, fileName: String): String? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = findRssMangaChapterDir(treeUri, key) ?: return@withContext null
        val imagesDir = findChild(treeUri, chapterDir, IMAGES_DIR) ?: return@withContext null
        return@withContext findChild(treeUri, imagesDir, fileName)?.toString()
    }

    override suspend fun resolveRssMangaImageUris(
        key: RssMangaChapterDownloadKey,
        fileNames: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext emptyList()
        val chapterDir = findRssMangaChapterDir(treeUri, key) ?: return@withContext emptyList()
        val imagesDir = findChild(treeUri, chapterDir, IMAGES_DIR) ?: return@withContext emptyList()
        val childrenByName = listChildren(treeUri, imagesDir).associateBy { it.name }
        fileNames.mapNotNull { childrenByName[it]?.uri?.toString() }
    }

    override suspend fun readRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? = withContext(Dispatchers.IO) {
        val treeUri = selectedTreeUri() ?: return@withContext null
        val chapterDir = findRssMangaChapterDir(treeUri, key) ?: return@withContext null
        return@withContext readRssMangaManifestFromDir(treeUri, chapterDir)
    }

    override suspend fun listRssMangaManifests(): List<RssMangaChapterManifest> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        return listChildren(treeUri, root)
            .filter { it.name.startsWith("rss_") }
            .flatMap { rssDir ->
                listChildren(treeUri, rssDir.uri).mapNotNull { readRssMangaManifestFromDir(treeUri, it.uri) }
            }
    }

    override suspend fun readQueue(): List<DownloadQueueEntry> {
        val treeUri = selectedTreeUri() ?: return emptyList()
        val root = downloadsRoot(treeUri) ?: return emptyList()
        val file = findChild(treeUri, root, QUEUE_FILE) ?: return emptyList()
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<DownloadQueueEntry>>(bytes.decodeToString()) }
            .onFailure { Logger.w(TAG, "Failed to decode download queue", it) }
            .getOrDefault(emptyList())
    }

    override suspend fun writeQueue(entries: List<DownloadQueueEntry>) {
        val treeUri = selectedTreeUri() ?: return
        val root = ensureDirectory(treeUri, rootDocumentUri(treeUri), ROOT_DIR)
        writeFile(
            treeUri,
            root,
            QUEUE_FILE,
            "application/json",
            json.encodeToString(entries).encodeToByteArray(),
        )
    }

    override suspend fun deleteThreadPage(key: ThreadPageDownloadKey) {
        val treeUri = selectedTreeUri() ?: return
        val root = downloadsRoot(treeUri) ?: return
        deleteChildByName(treeUri, root, key.stableId)
    }

    override suspend fun deleteThread(key: ThreadPageDownloadKey) {
        val treeUri = selectedTreeUri() ?: return
        val root = downloadsRoot(treeUri) ?: return
        listChildren(treeUri, root)
            .filter { it.name.startsWith(key.threadPrefix) }
            .forEach { deleteDocumentSafely(it.uri, "delete thread download item prefix=${key.threadPrefix}") }
    }

    override suspend fun deleteTagMangaChapter(key: TagMangaChapterDownloadKey) {
        val treeUri = selectedTreeUri() ?: return
        val tagDir = findTagMangaDir(treeUri, key.tagId) ?: return
        deleteChildByName(treeUri, tagDir, key.chapterStableId)
    }

    override suspend fun deleteTagManga(tagId: Int) {
        val treeUri = selectedTreeUri() ?: return
        val root = downloadsRoot(treeUri) ?: return
        deleteChildByName(treeUri, root, "tag_manga_$tagId")
    }

    override suspend fun deleteRssMangaChapter(key: RssMangaChapterDownloadKey) {
        val treeUri = selectedTreeUri() ?: return
        val rssDir = findRssMangaDir(treeUri, key.subscriptionId) ?: return
        deleteChildByName(treeUri, rssDir, key.chapterStableId)
    }

    override suspend fun deleteRssManga(subscriptionId: Long) {
        val treeUri = selectedTreeUri() ?: return
        val root = downloadsRoot(treeUri) ?: return
        deleteChildByName(treeUri, root, "rss_$subscriptionId")
    }

    private fun selectedTreeUri(): Uri? =
        appSettingsRepository.backupFolderUri.getValue().takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun downloadsRoot(treeUri: Uri): Uri? =
        findChild(treeUri, rootDocumentUri(treeUri), ROOT_DIR)

    private fun findTagMangaDir(treeUri: Uri, tagId: Int): Uri? =
        findChild(treeUri, downloadsRoot(treeUri) ?: return null, "tag_manga_$tagId")

    private fun findTagMangaChapterDir(treeUri: Uri, key: TagMangaChapterDownloadKey): Uri? =
        findChild(treeUri, findTagMangaDir(treeUri, key.tagId) ?: return null, key.chapterStableId)

    private fun findRssMangaDir(treeUri: Uri, subscriptionId: Long): Uri? =
        findChild(treeUri, downloadsRoot(treeUri) ?: return null, "rss_$subscriptionId")

    private fun findRssMangaChapterDir(treeUri: Uri, key: RssMangaChapterDownloadKey): Uri? =
        findChild(treeUri, findRssMangaDir(treeUri, key.subscriptionId) ?: return null, key.chapterStableId)

    private fun ensureDirectory(treeUri: Uri, parent: Uri, name: String): Uri =
        findChild(treeUri, parent, name)
            ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("無法建立資料夾：$name")

    private fun writeFile(treeUri: Uri, parent: Uri, name: String, mimeType: String, bytes: ByteArray) {
        deleteChildByName(treeUri, parent, name)
        val file = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: error("無法建立檔案：$name")
        resolver.openOutputStream(file, "wt")?.use { it.write(bytes) }
            ?: error("無法寫入檔案：$name")
    }

    private fun readManifestFromDir(treeUri: Uri, dir: Uri): ThreadPageDownloadManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: return null
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString<ThreadPageDownloadManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode thread page manifest", it) }
            .getOrNull()
    }

    private fun readTagMangaManifestFromDir(treeUri: Uri, dir: Uri): TagMangaChapterManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: return null
        val bytes = resolver.openInputStream(file)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString<TagMangaChapterManifest>(bytes.decodeToString()) }
            .onFailure { Logger.d(TAG, "Failed to decode tag manga manifest", it) }
            .getOrNull()
    }

    private fun readRssMangaManifestFromDir(treeUri: Uri, dir: Uri): RssMangaChapterManifest? {
        val file = findChild(treeUri, dir, "manifest.json") ?: return null
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
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
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
