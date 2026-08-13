package me.thenano.yamibo.yamibo_app.repository.pancloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import okio.Buffer
import okio.GzipSink
import okio.buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanCloudBackupStorageProviderTest {

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, String>()

        override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }

        override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
        override fun putFloat(key: String, value: Float) {
            values[key] = value.toString()
        }

        override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value.toString()
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun hasKey(key: String): Boolean = key in values
    }

    private class Harness {
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val settingsStore = MemorySettingsStore()
        val appSettings = AppSettingsRepository(settingsStore)
        var singleUploadCount = 0
        var chunkUploadCount = 0
        val chunkSizes = mutableListOf<Long>()
        val deletedIds = mutableListOf<String>()
        var downloadBytes: ByteArray = ByteArray(0)

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/files" && request.method == HttpMethod.Get -> respond(
                    """{"success":true,"data":[
                        {"id":"file-bak","name":"YamiboApp-20240101-000000.yamibobak","type":"file","size":100,"is_chunks":false},
                        {"id":"file-other","name":"notes.txt","type":"file","size":10,"is_chunks":false},
                        {"id":"folder-x","name":"sub","type":"folder","size":0,"child_count":0}
                    ]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                request.url.encodedPath == "/api/files/upload" -> {
                    singleUploadCount++
                    respond(
                        """{"success":true,"data":{"file_id":"file-single","name":"a.yamibobak","size":1,"mime_type":"application/octet-stream","is_chunks":false}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/api/files/upload/chunk" -> {
                    chunkUploadCount++
                    val index = request.headers["X-Chunk-Index"]?.toIntOrNull() ?: -1
                    chunkSizes += request.body.contentLength ?: 0L
                    respond(
                        """{"success":true,"data":{"index":$index,"file_id":"tg-$index","message_id":1}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath == "/api/files/upload/complete" -> {
                    val text = (request.body as TextContent).text
                    assertTrue(text.contains("\"filename\""))
                    respond(
                        """{"success":true,"data":{"file_id":"file-complete","is_chunks":true}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                request.url.encodedPath.startsWith("/api/files/") &&
                    request.url.encodedPath.endsWith("/download") -> {
                    respond(downloadBytes, HttpStatusCode.OK)
                }
                request.method == HttpMethod.Delete -> {
                    deletedIds += request.url.encodedPath.removePrefix("/api/files/").removeSuffix("/")
                    respond("""{"success":true,"message":"已移入回收站"}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"success":false,"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val apiClient = PanCloudApiClient(HttpClient(engine), baseUrl = "https://pan.test/api").also {
            it.accessToken = "at"
        }
        val accountRepository = PanCloudAccountRepository(apiClient, appSettings)
        val provider = PanCloudBackupStorageProvider(apiClient, accountRepository)

        init {
            appSettings.panCloudFolderId.setValue("folder-1")
        }
    }

    @Test
    fun writeSmallBackupUsesSingleUpload() = runBlocking {
        val h = Harness()
        val original = """{"schemaVersion":1,"appVersionCode":7}""".encodeToByteArray()

        val info = h.provider.writeBackupFile("YamiboApp-test.yamibobak", original).getOrThrow()

        assertEquals("file-single", info.uri)
        assertEquals(original.size.toLong(), info.bytes)
        assertEquals(false, info.automatic)
        assertEquals(1, h.singleUploadCount)
        assertEquals(0, h.chunkUploadCount)
    }

    @Test
    fun writeLargeBackupUsesChunkedUploadAndRoundTrips() = runBlocking {
        val h = Harness()
        val original = ByteArray(10 * 1024 * 1024 + 1)
        Random.Default.nextBytes(original)
        val compressed = gzip(original)
        assertTrue(compressed.size > 10 * 1024 * 1024)

        val info = h.provider.writeBackupFile("YamiboApp-big.yamibobak", original).getOrThrow()

        assertEquals("file-complete", info.uri)
        assertEquals(0, h.singleUploadCount)
        assertTrue(h.chunkUploadCount >= 2)
        assertEquals(compressed.size.toLong(), h.chunkSizes.sum())
    }

    @Test
    fun readBackupGunzipsDownloadedBytes() = runBlocking {
        val h = Harness()
        val original = """{"schemaVersion":1}""".encodeToByteArray()
        h.downloadBytes = gzip(original)

        val decoded = h.provider.readBackupFile("file-1").getOrThrow()

        assertTrue(original.contentEquals(decoded))
    }

    @Test
    fun listBackupFilesFiltersExtension() = runBlocking {
        val h = Harness()

        val files = h.provider.listBackupFiles()

        assertEquals(1, files.size)
        assertEquals("YamiboApp-20240101-000000.yamibobak", files[0].name)
        assertEquals("file-bak", files[0].uri)
    }

    @Test
    fun deleteBackupFileCallsDelete() = runBlocking {
        val h = Harness()

        h.provider.deleteBackupFile(
            BackupRepository.BackupFileInfo("x.yamibobak", 0, "file-x", false, null),
        ).getOrThrow()

        assertTrue(h.deletedIds.contains("file-x"))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = Buffer()
        GzipSink(output).buffer().use { sink -> sink.write(bytes) }
        return output.readByteArray()
    }
}
