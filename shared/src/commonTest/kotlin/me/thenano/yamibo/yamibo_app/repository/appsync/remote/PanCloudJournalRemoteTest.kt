package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudAccountRepository
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudApiClient
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class PanCloudJournalRemoteTest {

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

    private data class FileEntry(
        val id: String,
        val name: String,
        val updatedAt: Long,
        val content: String,
    )

    private class Harness {
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val settingsStore = MemorySettingsStore()
        val appSettings = AppSettingsRepository(settingsStore)
        val files = mutableListOf<FileEntry>()
        var downloadCount = 0

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/files" && request.method == HttpMethod.Get -> {
                    val items = files.joinToString(",") { f ->
                        """{"id":"${f.id}","name":"${f.name}","type":"file","size":1,"is_chunks":false,"updated_at":${f.updatedAt}}"""
                    }
                    respond("""{"success":true,"data":[$items]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath.startsWith("/api/files/") &&
                    request.url.encodedPath.endsWith("/download") -> {
                    val id = request.url.encodedPath
                        .removePrefix("/api/files/")
                        .removeSuffix("/download")
                    downloadCount++
                    respond(files.firstOrNull { it.id == id }?.content ?: "", HttpStatusCode.OK)
                }
                else -> respond("""{"success":false,"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val apiClient = PanCloudApiClient(HttpClient(engine), baseUrl = "https://pan.test/api").also {
            it.accessToken = "at"
        }
        val accountRepository = PanCloudAccountRepository(apiClient, appSettings)
        val remote = PanCloudJournalRemote(apiClient, accountRepository)

        init {
            appSettings.panCloudFolderId.setValue("folder-1")
        }
    }

    private val accountBinding = SyncAccountBinding("account-1")

    private fun emptyJournalPayload() = AppSyncJournalPayload(
        accountBinding = accountBinding,
        deviceId = SyncDeviceId("device-1"),
        deviceEpoch = SyncDeviceEpoch("epoch-1"),
        writerNonce = SyncWriterNonce("nonce-1"),
        firstSequence = 0,
        lastSequence = 0,
        operations = emptyList(),
        observed = SyncCausalContext(),
        heartbeatAtEpochMillis = 0,
    )

    @Test
    fun loadJournalsUsesCacheWhenUnchanged() = runBlocking {
        val h = Harness()
        val encoded = AppSyncJournalEnvelopeCodec().encode(emptyJournalPayload())
        h.files += FileEntry("file-1", "journal-device-1_epoch-1.json", 1000L, encoded)

        val first = h.remote.loadJournals(accountBinding, false)
        assertEquals(1, h.downloadCount)
        assertEquals(1, (first as AppSyncJournalLoadResult.Success).journals.size)

        h.remote.loadJournals(accountBinding, false)
        // updatedAt 未变，不重复下载
        assertEquals(1, h.downloadCount)
    }

    @Test
    fun loadJournalsRedownloadsWhenUpdatedAtChanges() = runBlocking {
        val h = Harness()
        val encoded = AppSyncJournalEnvelopeCodec().encode(emptyJournalPayload())
        h.files += FileEntry("file-1", "journal-device-1_epoch-1.json", 1000L, encoded)

        h.remote.loadJournals(accountBinding, false)
        assertEquals(1, h.downloadCount)

        h.files[0] = h.files[0].copy(updatedAt = 2000L)
        h.remote.loadJournals(accountBinding, false)
        assertEquals(2, h.downloadCount)
    }
}
