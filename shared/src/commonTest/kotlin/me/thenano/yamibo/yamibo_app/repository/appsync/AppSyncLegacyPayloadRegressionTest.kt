package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncField
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.backup.BackupSetting
import me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType
import me.thenano.yamibo.yamibo_app.repository.backup.CloudBackupPayloadCodec
import okio.Buffer
import okio.GzipSink
import okio.buffer
import kotlin.test.*

/** Historical wire fixtures deliberately bypass today's writer using synthetic data only. */
class AppSyncLegacyPayloadRegressionTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun legacyReadersRetainUnknownSourceEvidenceButReductionExcludesItsValues() {
        val corpus = AppSyncSyntheticCorpus.create()
        val payload = corpus.journal.copy(operations = corpus.journal.operations.map { operation ->
            when {
                operation.domainId.value == "settings" && operation.entityId.value == "appsettings.thememode" ->
                    operation.copy(entityId = SyncEntityId("appsettings.syntheticunknowncache"),
                        fields = mapOf("type" to "string", "value" to "synthetic-private-setting"))
                operation.domainId.value == "favorite.item" ->
                    operation.copy(fields = operation.fields + ("futureCache" to "synthetic-private-field"))
                else -> operation
            }
        })
        val sourceJson = json.encodeToString(AppSyncJournalPayload.serializer(), payload)
        for (schema in 1..2) {
            val wire = historicalEnvelope(AppSyncJournalDefaults.JOURNAL_MARKER, sourceJson, schema)
            val decoded = assertIs<AppSyncJournalValidation.Valid>(AppSyncJournalEnvelopeCodec().validate(wire)).envelope
            // Reader compatibility must verify the original fingerprint before any sanitizer.
            assertEquals(payload, decoded.payload)
            assertEquals(stableAppSyncFingerprint(sourceJson), decoded.fingerprint)
            assertTrue(decoded.payload.operations.any { it.fields["futureCache"] == "synthetic-private-field" })
            assertTrue(decoded.payload.operations.any { it.fields["value"] == "synthetic-private-setting" })
            val reduced = OperationReducer().reduce(operations = decoded.payload.operations)
            assertTrue(reduced.entities.values.none { it.key.entityId.value == "appsettings.syntheticunknowncache" })
            val values = reduced.entities.values.flatMap { entity ->
                entity.fields.values.flatMap { listOf(it.value) + it.operation.fields.values }
            }
            assertFalse(values.any { it == "synthetic-private-field" || it == "synthetic-private-setting" })
            assertTrue(reduced.entities.values.any { it.key.domainId.value == "favorite.item" && it.fields.containsKey("title") })
            assertEquals(sourceJson, json.encodeToString(AppSyncJournalPayload.serializer(), decoded.payload))
        }
    }

    @Test
    fun historicalCheckpointsExposeCoverIdentityParentAndWinningOperationDuplication() {
        val corpus = AppSyncSyntheticCorpus.create()
        val cover = "https://example.test/synthetic-legacy-cover"
        val favorite = corpus.resolved.first { it.key.domainId.value == "favorite.item" }
        val dirtyOperation = favorite.fields.getValue("title").operation.copy(
            fields = favorite.fields.getValue("title").operation.fields + ("coverUrl" to cover),
        )
        val dirtyFavorite = favorite.copy(fields = favorite.fields.mapValues { (_, field) ->
            field.copy(operation = dirtyOperation)
        } + ("coverUrl" to ResolvedSyncField(cover, dirtyOperation)))
        val dirtySnapshot = corpus.snapshot.withPortableAppSyncPayloads().copy(
            favorites = corpus.snapshot.favorites.copy(items = corpus.snapshot.favorites.items.map { it.copy(coverUrl = cover) }),
            settings = corpus.snapshot.settings + BackupSetting("appsettings.syntheticunknowncache",
                BackupSettingType.String, "synthetic-private-setting"),
        )
        val legacy = corpus.checkpoint().copy(
            encodedSnapshot = CloudBackupPayloadCodec().encode(dirtySnapshot).getOrThrow(),
            resolvedEntities = corpus.resolved.map { if (it.key == favorite.key) dirtyFavorite else it },
        )
        val sourceJson = json.encodeToString(AppSyncCheckpointPayload.serializer(), legacy)
        val codec = AppSyncCheckpointEnvelopeCodec()
        for (schema in 1..2) {
            val validation = codec.validate(historicalEnvelope(AppSyncJournalDefaults.CHECKPOINT_MARKER, sourceJson, schema))
            val decoded = assertIs<AppSyncCheckpointValidation.Valid>(validation,
                (validation as? AppSyncCheckpointValidation.Invalid)?.reason).envelope
            assertEquals(legacy, decoded.payload)
            assertEquals(cover, decoded.snapshot.favorites.items.first().coverUrl)
            val entity = decoded.payload.resolvedEntities.single { it.key == favorite.key }
            assertEquals(cover, entity.fields.getValue("coverUrl").value)
            assertTrue(entity.fields.values.count { it.operation.fields["coverUrl"] == cover } > 1)
            assertEquals(dirtyOperation.fields["targetId"], entity.fields.getValue("targetId").value)
            assertTrue(decoded.payload.resolvedEntities.any { it.fields.containsKey("subscriptionTitle") })
            assertTrue(decoded.payload.resolvedEntities.any { it.fields.containsKey("subscriptionQuery") })
            val copies = decoded.payload.resolvedEntities.flatMap { it.fields.values }.map { it.operation }
            assertTrue(copies.size > copies.map { it.operationId }.distinct().size * 3)

            // Newly published checkpoints must not silently forward the old wire fixture.
            assertFailsWith<IllegalArgumentException> { codec.encode(decoded.payload) }
            val rebuilt = codec.createPayload(legacy.checkpointId, legacy.accountBinding, legacy.coverage,
                decoded.snapshot, decoded.payload.resolvedEntities, legacy.tombstones, legacy.createdAtEpochMillis)
            val clean = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(codec.encode(rebuilt))).envelope
            assertTrue(clean.snapshot.favorites.items.all { it.coverUrl == null })
            assertFalse(clean.snapshot.settings.any { it.key == "appsettings.syntheticunknowncache" })
            assertTrue(clean.payload.resolvedEntities.all { entity ->
                "coverUrl" !in entity.fields && entity.fields.values.none { it.operation.fields.values.contains(cover) }
            })
            assertEquals(sourceJson, json.encodeToString(AppSyncCheckpointPayload.serializer(), decoded.payload))
        }
    }

    private fun historicalEnvelope(marker: String, sourceJson: String, schema: Int): String {
        val body = if (schema == 1) sourceJson else {
            val output = Buffer()
            GzipSink(output).buffer().use { it.writeUtf8(sourceJson) }
            "gzip-base64:" + output.readByteString().base64()
        }
        return "[$marker:BEGIN]\nschema=$schema\nfingerprint=${stableAppSyncFingerprint(sourceJson)}\npayload=$body\n[$marker:END]"
    }
}
