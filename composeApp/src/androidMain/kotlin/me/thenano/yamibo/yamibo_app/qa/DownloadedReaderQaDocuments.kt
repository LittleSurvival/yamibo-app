package me.thenano.yamibo.yamibo_app.qa

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Validation-only SAF provider backed by deterministic, isolated fixture files. */
class DownloadedReaderQaDocuments : DocumentsProvider() {
    companion object {
        private val queries = AtomicInteger()
        private val mainQueries = AtomicInteger()
        private val opens = AtomicInteger()
        fun queryCount(): Int = queries.get()
        fun mainQueryCount(): Int = mainQueries.get()
    }
    private val root get() = File(requireNotNull(context).filesDir, "qa_downloads").canonicalFile
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
        addRow(arrayOf<Any>("root", "root", "QA Downloads", Root.FLAG_LOCAL_ONLY))
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (documentId == "metrics") return MatrixCursor(arrayOf("queries", "mainQueries", "opens")).apply {
            addRow(arrayOf(queries.get(), mainQueries.get(), opens.get()))
        }
        recordQuery()
        return MatrixCursor(projection ?: columns).apply { addFile(documentId, file(documentId)) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        recordQuery()
        return MatrixCursor(projection ?: columns).apply {
            file(parentDocumentId).listFiles().orEmpty().forEach { child ->
                addFile(child.relativeTo(root).invariantSeparatorsPath, child)
            }
        }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        opens.incrementAndGet()
        check(Looper.myLooper() != Looper.getMainLooper()) { "QA document opened on the UI thread" }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)
    private fun file(id: String): File {
        val file = (if (id == "root") root else File(root, id)).canonicalFile
        require(file == root.canonicalFile || file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }
    private fun recordQuery() {
        queries.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) mainQueries.incrementAndGet()
        android.util.Log.i("DownloadReaderQA", "queries=${queries.get()} mainQueries=${mainQueries.get()} opens=${opens.get()}")
    }
    private fun MatrixCursor.addFile(id: String, file: File) {
        val row = newRow()
        columnNames.forEach { name -> row.add(when (name) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else if (file.extension == "png") "image/png" else "application/json"
            Document.COLUMN_SIZE -> file.length()
            Document.COLUMN_FLAGS -> 0
            else -> null
        }) }
    }
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
}
