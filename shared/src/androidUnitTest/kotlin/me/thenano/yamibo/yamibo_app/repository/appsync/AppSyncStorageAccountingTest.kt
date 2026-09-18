package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import kotlin.test.*

/** Accounting fixture only. Deletes/VACUUM below run on an isolated synthetic in-memory DB. */
class AppSyncStorageAccountingTest {
    @Test
    fun supersessionDeletionAndPageReclamationAreDifferentMeasurements() {
        AppSyncStorageAccountingFixture().use { fixture ->
            val before = fixture.measure()
            assertTrue(before.livePayloadBytes > 0)
            assertTrue(before.supersededPayloadBytes > 1024 * 1024)
            assertEquals(1L, before.auditRows)
            assertEquals(0L, before.auditPayloadBytes)
            assertEquals(2, before.remoteArtifacts)
            assertTrue(before.remoteBytes > 0)

            // Merely marking a source superseded must not be reported as bytes removed.
            fixture.supersedeLiveRow()
            val marked = fixture.measure()
            assertEquals(0L, marked.livePayloadBytes)
            assertEquals(before.livePayloadBytes + before.supersededPayloadBytes, marked.supersededPayloadBytes)
            assertEquals(before.allocatedDatabaseBytes, marked.allocatedDatabaseBytes)

            fixture.deleteSupersededBodies()
            val deleted = fixture.measure()
            assertEquals(0L, deleted.supersededPayloadBytes)
            assertTrue(deleted.freeDatabaseBytes > marked.freeDatabaseBytes)
            assertEquals(marked.allocatedDatabaseBytes, deleted.allocatedDatabaseBytes)
            assertEquals(before.auditRows, deleted.auditRows)
            assertEquals(before.remoteBytes, deleted.remoteBytes)
            assertEquals(before.remoteArtifacts, deleted.remoteArtifacts)

            fixture.compactFixture()
            val compacted = fixture.measure()
            assertTrue(compacted.allocatedDatabaseBytes < deleted.allocatedDatabaseBytes)
            assertEquals(0L, compacted.freeDatabaseBytes)
            assertEquals(before.remoteBytes, compacted.remoteBytes)
            assertEquals(before.auditRows, compacted.auditRows)
        }
    }

    @Test
    fun localCleanupDoesNotImplyRemoteDeletionAndUtf8CountsAreBytes() {
        AppSyncStorageAccountingFixture().use { fixture ->
            val before = fixture.measure()
            assertEquals(fixture.liveFields.encodeToByteArray().size.toLong(), before.livePayloadBytes)
            assertTrue(before.livePayloadBytes > fixture.liveFields.length)
            fixture.removeOneFakeRemoteArtifact()
            val after = fixture.measure()
            assertEquals(1, after.remoteArtifacts)
            assertTrue(after.remoteBytes < before.remoteBytes)
            assertEquals(before.livePayloadBytes, after.livePayloadBytes)
            assertEquals(before.supersededPayloadBytes, after.supersededPayloadBytes)
            assertEquals(before.allocatedDatabaseBytes, after.allocatedDatabaseBytes)
        }
    }
}

internal class AppSyncStorageAccountingFixture : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val remoteArtifacts = linkedMapOf<String, ByteArray>()
    val liveFields = Json.encodeToString(mapOf("content" to "合成筆記🙂"))

    data class Measurement(
        val livePayloadBytes: Long,
        val supersededPayloadBytes: Long,
        val auditRows: Long,
        val auditPayloadBytes: Long,
        val allocatedDatabaseBytes: Long,
        val freeDatabaseBytes: Long,
        val remoteArtifacts: Int,
        val remoteBytes: Long,
    )

    init {
        driver.execute(null, "PRAGMA auto_vacuum = NONE", 0)
        Database.Schema.create(driver)
        val queries = Database(driver).appSyncOperationQueries
        val corpus = AppSyncSyntheticCorpus.create()
        val source = corpus.journal.operations.first { it.domainId.value == "detail-note" }
        listOf(liveFields, Json.encodeToString(mapOf("content" to "合成".repeat(200_000))))
            .forEachIndexed { index, fields ->
                queries.insertOutboxOperation(
                    operationId = "synthetic-$index", deviceId = source.deviceId.value,
                    deviceEpoch = source.deviceEpoch.value, sequence = index + 1L,
                    accountBinding = source.accountBinding.value, domainId = source.domainId.value,
                    entityId = "synthetic-$index", entityGeneration = 1, kind = source.kind.name,
                    fieldsJson = fields, causalContextJson = Json.encodeToString(SyncCausalContext()),
                    createdAtEpochMillis = AppSyncSyntheticCorpus.TIMESTAMP, origin = source.origin.name,
                    bulkDeleteAuthorizationId = null, schemaVersion = 1,
                    lifecycle = if (index == 0) "PENDING_LOCAL" else "SUPERSEDED_BY_RECOVERY",
                    acknowledgedAtEpochMillis = null,
                )
            }
        // Existing applied-operation receipts are payload-free audit examples. This is not a
        // claim that the future recovery audit retention implementation already exists.
        driver.execute(null, """INSERT INTO AppSyncAppliedOperation
            (operationId, deviceId, deviceEpoch, sequence, appliedAtEpochMillis)
            VALUES ('synthetic-receipt', 'synthetic-device', 'synthetic-epoch', 3, 1800000000000)""", 0)
        remoteArtifacts["journal"] = AppSyncJournalEnvelopeCodec().encode(corpus.journal).encodeToByteArray()
        remoteArtifacts["checkpoint"] = AppSyncCheckpointEnvelopeCodec().encode(corpus.checkpoint()).encodeToByteArray()
    }

    fun measure(): Measurement {
        val pageSize = scalar("PRAGMA page_size")
        return Measurement(
            scalar("SELECT COALESCE(SUM(length(CAST(fieldsJson AS BLOB))), 0) FROM AppSyncOutbox WHERE lifecycle = 'PENDING_LOCAL'"),
            scalar("SELECT COALESCE(SUM(length(CAST(fieldsJson AS BLOB))), 0) FROM AppSyncOutbox WHERE lifecycle = 'SUPERSEDED_BY_RECOVERY'"),
            scalar("SELECT count(*) FROM AppSyncAppliedOperation"),
            0, // Receipt schema has no operation field/body column; metadata still occupies pages.
            scalar("PRAGMA page_count") * pageSize,
            scalar("PRAGMA freelist_count") * pageSize,
            remoteArtifacts.size, remoteArtifacts.values.sumOf { it.size.toLong() },
        )
    }

    fun supersedeLiveRow() {
        driver.execute(null, "UPDATE AppSyncOutbox SET lifecycle = 'SUPERSEDED_BY_RECOVERY' WHERE lifecycle = 'PENDING_LOCAL'", 0)
    }

    fun deleteSupersededBodies() {
        driver.execute(null, "DELETE FROM AppSyncOutbox WHERE lifecycle = 'SUPERSEDED_BY_RECOVERY'", 0)
    }

    fun compactFixture() { driver.execute(null, "VACUUM", 0) }
    fun removeOneFakeRemoteArtifact() { remoteArtifacts.remove("journal") }

    private fun scalar(sql: String): Long = driver.executeQuery(null, sql, { cursor ->
        check(cursor.next().value)
        QueryResult.Value(requireNotNull(cursor.getLong(0)))
    }, 0).value

    override fun close() = driver.close()
}
