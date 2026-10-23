package me.thenano.yamibo.yamibo_app.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.thenano.yamibo.yamibo_app.repository.download.AndroidDownloadStorageProvider
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

class DownloadedReaderQaBenchmark : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var status by mutableStateOf("Benchmark running")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text(status) }
        val settings = AppSettingsRepository(AndroidSettingsStore(this))
        settings.backupFolderUri.setValue("content://$packageName.qa.documents/tree/root")
        val storage = AndroidDownloadStorageProvider(this, settings)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val key = ThreadPageDownloadKey(43, 1, 7)
                val names = (1..300).map { it.toString().padStart(4, '0') + ".png" }
                val samples = buildJsonArray {
                    repeat(3) {
                        val beforeBatch = DownloadedReaderQaDocuments.queryCount()
                        val startBatch = System.nanoTime()
                        val batch = storage.resolveImageUris(key, names)
                        val batchMs = (System.nanoTime() - startBatch) / 1_000_000.0
                        val batchQueries = DownloadedReaderQaDocuments.queryCount() - beforeBatch
                        val beforeLegacy = DownloadedReaderQaDocuments.queryCount()
                        val startLegacy = System.nanoTime()
                        val legacy = names.mapNotNull { name -> storage.resolveImageUri(key, name)?.let { name to it } }.toMap()
                        val legacyMs = (System.nanoTime() - startLegacy) / 1_000_000.0
                        val legacyQueries = DownloadedReaderQaDocuments.queryCount() - beforeLegacy
                        check(batch.size == 300 && batch == legacy)
                        check(batchQueries == 4 && legacyQueries == 1_200)
                        add(buildJsonObject {
                            put("batch_ms", batchMs); put("legacy_ms", legacyMs)
                            put("batch_queries", batchQueries); put("legacy_queries", legacyQueries)
                        })
                    }
                }
                check(DownloadedReaderQaDocuments.mainQueryCount() == 0)
                buildJsonObject { put("image_count", 300); put("samples", samples); put("main_queries", 0) }
                    .also { File(filesDir, "qa_benchmark.json").writeText(it.toString()) }
            }
            status = "BENCHMARK PASS: $result"
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
