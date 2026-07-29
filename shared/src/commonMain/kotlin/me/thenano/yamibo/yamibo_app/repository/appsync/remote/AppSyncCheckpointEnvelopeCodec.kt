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
        return buildString {
            appendLine("[${AppSyncJournalDefaults.CHECKPOINT_MARKER}:BEGIN]")
            appendLine("schema=${AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION}")
            appendLine("fingerprint=$fingerprint")
            appendLine("payload=$payloadJson")
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
        if (beginIndex < 0 || endIndex <= beginIndex) {
            return invalid("Checkpoint envelope boundary is incomplete", true)
        }
        val body = text.substring(beginIndex + begin.length, endIndex).trim()
        val schema = SCHEMA.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return invalid("Checkpoint schema is missing", true)
        if (schema != AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION) {
            return invalid("Unsupported checkpoint schema: $schema", true)
        }
        val fingerprint = FINGERPRINT.find(body)?.groupValues?.get(1)
            ?: return invalid("Checkpoint fingerprint is missing", true)
        val payloadText = PAYLOAD.find(body)?.groupValues?.get(1)?.trim()
            ?: return invalid("Checkpoint payload is missing", true)
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

    private fun invalid(reason: String, markerPresent: Boolean) =
        AppSyncCheckpointValidation.Invalid(reason, markerPresent)

    private companion object {
        val SCHEMA = Regex("""(?:^|\s)schema=(\d+)(?=\s|$)""")
        val FINGERPRINT = Regex("""(?:^|\s)fingerprint=([0-9a-fA-F]+)(?=\s|$)""")
        val PAYLOAD = Regex("""(?:^|\s)payload=(\{.*\})\s*$""", RegexOption.DOT_MATCHES_ALL)
    }
}
