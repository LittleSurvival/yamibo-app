package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.backup.CloudBackupPayloadCodec
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.GzipSink
import okio.GzipSource
import okio.buffer

@Serializable
internal data class AppSyncCheckpointTombstone(
    val domainId: SyncDomainId,
    val entityId: SyncEntityId,
    val entityGeneration: Long,
    val operationId: SyncOperationId,
)

@Serializable
internal data class AppSyncCheckpointPayload(
    val checkpointId: String,
    val accountBinding: SyncAccountBinding,
    val coverage: SyncCausalContext,
    val encodedSnapshot: String,
    val resolvedEntities: List<ResolvedSyncEntity> = emptyList(),
    val tombstones: List<AppSyncCheckpointTombstone>,
    val createdAtEpochMillis: Long,
)

internal data class ParsedAppSyncCheckpointEnvelope(
    val payload: AppSyncCheckpointPayload,
    val snapshot: YamiboBackupFile,
    val fingerprint: String,
)

internal sealed interface AppSyncCheckpointValidation {
    data class Valid(
        val envelope: ParsedAppSyncCheckpointEnvelope,
    ) : AppSyncCheckpointValidation

    data class Invalid(
        val reason: String,
        val markerPresent: Boolean,
    ) : AppSyncCheckpointValidation
}

internal class AppSyncCheckpointEnvelopeCodec(
    private val backupCodec: CloudBackupPayloadCodec = CloudBackupPayloadCodec(),
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) {
    fun createPayload(
        checkpointId: String,
        accountBinding: SyncAccountBinding,
        coverage: SyncCausalContext,
        snapshot: YamiboBackupFile,
        resolvedEntities: Collection<ResolvedSyncEntity> = emptyList(),
        tombstones: List<AppSyncCheckpointTombstone>,
        createdAtEpochMillis: Long,
    ): AppSyncCheckpointPayload {
        require(checkpointId.isNotBlank()) { "Checkpoint id cannot be blank" }
        return AppSyncCheckpointPayload(
            checkpointId = checkpointId,
            accountBinding = accountBinding,
            coverage = coverage,
            encodedSnapshot = backupCodec.encode(snapshot).getOrThrow(),
            resolvedEntities = resolvedEntities.sortedWith(
                compareBy(
                    { it.key.domainId.value },
                    { it.key.entityId.value },
                    { it.key.generation },
                ),
            ),
            tombstones = tombstones.sortedWith(
                compareBy(
                    { it.domainId.value },
                    { it.entityId.value },
                    { it.entityGeneration },
                    { it.operationId.value },
                ),
            ),
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    fun encode(payload: AppSyncCheckpointPayload): String {
        validatePayload(payload)?.let { throw IllegalArgumentException(it) }
        val payloadJson = json.encodeToString(AppSyncCheckpointPayload.serializer(), payload)
        val fingerprint = stableAppSyncFingerprint(payloadJson)
        val encodedPayload = compress(payloadJson)
        return buildString {
            appendLine("[${AppSyncJournalDefaults.CHECKPOINT_MARKER}:BEGIN]")
            appendLine("schema=${AppSyncJournalDefaults.COMPRESSED_ENVELOPE_SCHEMA_VERSION}")
            appendLine("fingerprint=$fingerprint")
            appendLine("payload=$encodedPayload")
            append("[${AppSyncJournalDefaults.CHECKPOINT_MARKER}:END]")
        }
    }

    fun validateReaderHtml(contentHtml: String): AppSyncCheckpointValidation =
        validate(
            try {
                Ksoup.parseBodyFragment(contentHtml).body().text()
            } catch (_: Throwable) {
                contentHtml
            },
        )

    fun validate(text: String): AppSyncCheckpointValidation {
        val marker = AppSyncJournalDefaults.CHECKPOINT_MARKER
        val markerPresent = text.contains(marker)
        if (!markerPresent) return invalid("Checkpoint marker is missing", false)
        val begin = "[$marker:BEGIN]"
        val end = "[$marker:END]"
        val beginIndex = text.indexOf(begin)
        val endIndex = text.indexOf(end, beginIndex.coerceAtLeast(0))
        if (beginIndex !in 0..<endIndex) {
            return invalid("Checkpoint envelope boundary is incomplete", true)
        }
        val body = text.substring(beginIndex + begin.length, endIndex).trim()
        val schema = SCHEMA.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return invalid("Checkpoint schema is missing", true)
        if (
            schema != AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION &&
            schema != AppSyncJournalDefaults.COMPRESSED_ENVELOPE_SCHEMA_VERSION
        ) {
            return invalid("Unsupported checkpoint schema: $schema", true)
        }
        val fingerprint = FINGERPRINT.find(body)?.groupValues?.get(1)
            ?: return invalid("Checkpoint fingerprint is missing", true)
        val encodedPayload = PAYLOAD.find(body)?.groupValues?.get(1)?.trim()
            ?: return invalid("Checkpoint payload is missing", true)
        val payloadText = if (schema == AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION) {
            encodedPayload
        } else {
            decompress(encodedPayload).getOrElse {
                return invalid(
                    "Checkpoint compressed payload is invalid: " +
                        (it.message ?: it::class.simpleName),
                    true,
                )
            }
        }
        val payload = try {
            json.decodeFromString(AppSyncCheckpointPayload.serializer(), payloadText)
        } catch (error: Throwable) {
            return invalid("Checkpoint payload is invalid: ${error.message}", true)
        }
        val canonical = json.encodeToString(AppSyncCheckpointPayload.serializer(), payload)
        if (stableAppSyncFingerprint(canonical) != fingerprint) {
            return invalid("Checkpoint fingerprint does not match payload", true)
        }
        validatePayload(payload)?.let { return invalid(it, true) }
        val snapshot = backupCodec.decode(payload.encodedSnapshot).getOrElse {
            return invalid("Checkpoint snapshot is invalid: ${it.message}", true)
        }
        validateFavoriteUpdateProjection(snapshot, payload.resolvedEntities)?.let {
            return invalid(it, true)
        }
        return AppSyncCheckpointValidation.Valid(
            ParsedAppSyncCheckpointEnvelope(payload, snapshot, fingerprint),
        )
    }

    private fun validatePayload(payload: AppSyncCheckpointPayload): String? {
        if (payload.checkpointId.isBlank()) return "Checkpoint id cannot be blank"
        if (payload.encodedSnapshot.isBlank()) return "Checkpoint snapshot cannot be blank"
        val duplicateTombstone = payload.tombstones
            .groupBy { Triple(it.domainId, it.entityId, it.entityGeneration) }
            .values
            .any { it.size > 1 }
        if (duplicateTombstone) return "Checkpoint contains duplicate tombstones"
        return null
    }

    private fun validateFavoriteUpdateProjection(
        snapshot: YamiboBackupFile,
        entities: List<ResolvedSyncEntity>,
    ): String? {
        val live = entities.filter { it.tombstone == null }
        val eventEntities = live.filter { it.key.domainId.value == "favorite.update-event" }
            .associateBy { it.key.entityId.value }
        val snapshotEvents = snapshot.favoriteUpdates.events.associateBy { it.syncId }
        if (eventEntities.keys != snapshotEvents.keys) {
            return "Checkpoint FavoriteUpdate event projection does not match resolved entities"
        }
        snapshotEvents.forEach { (syncId, event) ->
            val fields = eventEntities.getValue(syncId).fields.mapValues { it.value.value }
            val expected = mapOf(
                "targetType" to event.targetType,
                "targetId" to event.targetId.toString(),
                "authorId" to (event.authorId ?: 0L).toString(),
                "fid" to event.fid?.toString(),
                "forumName" to event.forumName,
                "title" to event.title,
                "latestPostTitle" to event.latestPostTitle,
                "mode" to event.mode,
                "summary" to event.summary,
                "detailIds" to event.detailIds.distinct().sorted().joinToString(","),
                "coverUrl" to event.coverUrl,
                "detectedAt" to event.detectedAt.toString(),
                "readAt" to event.readAt?.toString(),
                "dismissedAt" to event.dismissedAt?.toString(),
                "ambiguous" to event.ambiguous.toString(),
                "sourceFingerprint" to event.sourceFingerprint,
                "sourceDiscriminator" to event.sourceDiscriminator,
            )
            if (expected.any { (key, value) -> fields[key] != value }) {
                return "Checkpoint FavoriteUpdate event $syncId differs from resolved state"
            }
        }

        val fidEntities = live.filter { it.key.domainId.value == "favorite.update-fid-filter" }
            .associateBy { it.key.entityId.value }
        val snapshotFids = snapshot.favoriteUpdates.fidFilters.associateBy { "fid:${it.fid}" }
        if (fidEntities.keys != snapshotFids.keys) {
            return "Checkpoint FavoriteUpdate FID choices do not match resolved entities"
        }
        snapshotFids.forEach { (entityId, choice) ->
            val fields = fidEntities.getValue(entityId).fields.mapValues { it.value.value }
            if (fields["fid"] != choice.fid.toString() || fields["enabled"] != choice.enabled.toString()) {
                return "Checkpoint FavoriteUpdate FID choice $entityId differs from resolved state"
            }
        }

        val categoryEntities = live
            .filter { it.key.domainId.value == "favorite.update-category-filter" }
            .associateBy { it.key.entityId.value }
        val snapshotCategories = snapshot.favoriteUpdates.categoryFilters
            .associateBy { "category:${it.categorySyncId}" }
        if (categoryEntities.keys != snapshotCategories.keys) {
            return "Checkpoint FavoriteUpdate category choices do not match resolved entities"
        }
        snapshotCategories.forEach { (entityId, choice) ->
            val fields = categoryEntities.getValue(entityId).fields.mapValues { it.value.value }
            if (
                fields["categorySyncId"] != choice.categorySyncId ||
                fields["enabled"] != choice.enabled.toString()
            ) {
                return "Checkpoint FavoriteUpdate category choice $entityId differs from resolved state"
            }
        }
        return null
    }

    private fun invalid(reason: String, markerPresent: Boolean) =
        AppSyncCheckpointValidation.Invalid(reason, markerPresent)

    private fun compress(value: String): String {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_DECOMPRESSED_BYTES) {
            "Checkpoint JSON exceeds $MAX_DECOMPRESSED_BYTES bytes"
        }
        val output = Buffer()
        val sink = GzipSink(output).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        require(output.size <= MAX_COMPRESSED_BYTES) {
            "Compressed checkpoint exceeds $MAX_COMPRESSED_BYTES bytes"
        }
        return COMPRESSED_PAYLOAD_PREFIX + output.readByteString().base64()
    }

    private fun decompress(value: String): Result<String> = runCatching {
        require(value.startsWith(COMPRESSED_PAYLOAD_PREFIX)) {
            "Compressed checkpoint framing prefix is missing"
        }
        val compressed = requireNotNull(
            value.removePrefix(COMPRESSED_PAYLOAD_PREFIX).decodeBase64(),
        ) {
            "Checkpoint Base64 payload is invalid"
        }
        require(compressed.size <= MAX_COMPRESSED_BYTES) {
            "Compressed checkpoint exceeds $MAX_COMPRESSED_BYTES bytes"
        }
        val source = GzipSource(Buffer().write(compressed)).buffer()
        val output = Buffer()
        try {
            while (true) {
                val remaining = MAX_DECOMPRESSED_BYTES.toLong() - output.size
                require(remaining >= 0) {
                    "Decoded checkpoint exceeds $MAX_DECOMPRESSED_BYTES bytes"
                }
                val read = source.read(output, minOf(8_192L, remaining + 1L))
                if (read == -1L) break
                require(output.size <= MAX_DECOMPRESSED_BYTES) {
                    "Decoded checkpoint exceeds $MAX_DECOMPRESSED_BYTES bytes"
                }
            }
        } finally {
            source.close()
        }
        output.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private companion object {
        const val COMPRESSED_PAYLOAD_PREFIX = "gzip-base64:"
        const val MAX_COMPRESSED_BYTES = 12 * 1024 * 1024
        const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
        val SCHEMA = Regex("""(?:^|\s)schema=(\d+)(?=\s|$)""")
        val FINGERPRINT = Regex("""(?:^|\s)fingerprint=([0-9a-fA-F]+)(?=\s|$)""")
        val PAYLOAD = Regex("""(?:^|\s)payload=([\s\S]*)\s*$""")
    }
}
