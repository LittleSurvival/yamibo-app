package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce

internal object AppSyncJournalDefaults {
    const val JOURNAL_SCHEMA_VERSION = 1
    const val JOURNAL_MARKER = "YAMIBO_APP_SYNC_JOURNAL:ymb-sync-9f4c2a7"
    const val INDEX_MARKER = "YAMIBO_APP_SYNC_INDEX:ymb-sync-9f4c2a7"
    const val CHECKPOINT_MARKER = "YAMIBO_APP_SYNC_CHECKPOINT:ymb-sync-9f4c2a7"
    const val JOURNAL_TITLE_PREFIX = "Yamibo App Sync Journal - DO NOT EDIT - "
    const val CHECKPOINT_TITLE_PREFIX = "Yamibo App Sync Checkpoint - DO NOT EDIT - "

    fun journalTitle(deviceId: SyncDeviceId, epoch: SyncDeviceEpoch): String =
        "$JOURNAL_TITLE_PREFIX${deviceId.value.take(12)}-${epoch.value.take(12)}"

    fun checkpointTitle(checkpointId: String): String =
        "$CHECKPOINT_TITLE_PREFIX${checkpointId.take(24)}"
}

@Serializable
internal data class AppSyncCheckpointAcknowledgement(
    val checkpointId: String,
    val coverage: SyncCausalContext,
)

@Serializable
internal data class AppSyncJournalPayload(
    val accountBinding: SyncAccountBinding,
    val deviceId: SyncDeviceId,
    val deviceEpoch: SyncDeviceEpoch,
    val writerNonce: SyncWriterNonce,
    val firstSequence: Long,
    val lastSequence: Long,
    val operations: List<SyncOperation>,
    val observed: SyncCausalContext,
    val checkpointAcknowledgements: List<AppSyncCheckpointAcknowledgement> = emptyList(),
    val heartbeatAtEpochMillis: Long,
)

internal data class ParsedAppSyncJournalEnvelope(
    val payload: AppSyncJournalPayload,
    val schemaVersion: Int,
    val fingerprint: String,
)

internal sealed interface AppSyncJournalValidation {
    data class Valid(val envelope: ParsedAppSyncJournalEnvelope) : AppSyncJournalValidation
    data class Invalid(val reason: String, val markerPresent: Boolean) : AppSyncJournalValidation
}

internal class AppSyncJournalEnvelopeCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) {
    fun encode(payload: AppSyncJournalPayload): String {
        validatePayload(payload)?.let { throw IllegalArgumentException(it) }
        val payloadJson = json.encodeToString(AppSyncJournalPayload.serializer(), payload)
        val fingerprint = stableAppSyncFingerprint(payloadJson)
        return buildString {
            appendLine("[${AppSyncJournalDefaults.JOURNAL_MARKER}:BEGIN]")
            appendLine("schema=${AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION}")
            appendLine("fingerprint=$fingerprint")
            appendLine("payload=$payloadJson")
            append("[${AppSyncJournalDefaults.JOURNAL_MARKER}:END]")
        }
    }

    fun validateReaderHtml(contentHtml: String): AppSyncJournalValidation =
        validate(
            try {
                Ksoup.parseBodyFragment(contentHtml).body().text()
            } catch (_: Throwable) {
                contentHtml
            },
        )

    fun validate(text: String): AppSyncJournalValidation {
        val markerPresent = text.contains(AppSyncJournalDefaults.JOURNAL_MARKER)
        if (!markerPresent) return invalid("Journal marker is missing", false)
        val begin = "[${AppSyncJournalDefaults.JOURNAL_MARKER}:BEGIN]"
        val end = "[${AppSyncJournalDefaults.JOURNAL_MARKER}:END]"
        val beginIndex = text.indexOf(begin)
        val endIndex = text.indexOf(end, beginIndex.coerceAtLeast(0))
        if (beginIndex < 0 || endIndex <= beginIndex) {
            return invalid("Journal envelope boundary is incomplete", true)
        }
        val body = text.substring(beginIndex + begin.length, endIndex).trim()
        val schema = SCHEMA_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return invalid("Journal schema is missing", true)
        if (schema != AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION) {
            return invalid("Unsupported journal schema: $schema", true)
        }
        val fingerprint = FINGERPRINT_REGEX.find(body)?.groupValues?.get(1)
            ?: return invalid("Journal fingerprint is missing", true)
        val payloadJson = PAYLOAD_REGEX.find(body)?.groupValues?.get(1)?.trim()
            ?: return invalid("Journal payload is missing", true)
        val payload = try {
            json.decodeFromString(AppSyncJournalPayload.serializer(), payloadJson)
        } catch (error: Throwable) {
            return invalid("Journal payload is invalid: ${error.message ?: error::class.simpleName}", true)
        }
        if (stableAppSyncFingerprint(json.encodeToString(AppSyncJournalPayload.serializer(), payload)) != fingerprint) {
            return invalid("Journal fingerprint does not match payload", true)
        }
        validatePayload(payload)?.let { return invalid(it, true) }
        return AppSyncJournalValidation.Valid(
            ParsedAppSyncJournalEnvelope(payload, schema, fingerprint),
        )
    }

    private fun validatePayload(payload: AppSyncJournalPayload): String? {
        if (payload.operations.isEmpty()) {
            if (payload.firstSequence != 0L || payload.lastSequence != 0L) {
                return "Empty journal must use a zero sequence range"
            }
            return null
        }
        val sorted = payload.operations.sortedBy { it.sequence.value }
        if (payload.firstSequence != sorted.first().sequence.value ||
            payload.lastSequence != sorted.last().sequence.value
        ) {
            return "Journal sequence range does not match operations"
        }
        sorted.zipWithNext().forEach { (left, right) ->
            if (right.sequence.value != left.sequence.value + 1L) {
                return "Journal operation sequence is not contiguous"
            }
        }
        if (sorted.any {
                it.deviceId != payload.deviceId ||
                    it.deviceEpoch != payload.deviceEpoch ||
                    it.accountBinding != payload.accountBinding
            }
        ) {
            return "Journal operation identity does not match journal owner"
        }
        return null
    }

    private fun invalid(reason: String, markerPresent: Boolean) =
        AppSyncJournalValidation.Invalid(reason, markerPresent)

    private companion object {
        val SCHEMA_REGEX = Regex("""(?:^|\s)schema=(\d+)(?=\s|$)""")
        val FINGERPRINT_REGEX = Regex("""(?:^|\s)fingerprint=([0-9a-fA-F]+)(?=\s|$)""")
        val PAYLOAD_REGEX = Regex("""(?:^|\s)payload=(\{.*\})\s*$""", RegexOption.DOT_MATCHES_ALL)
    }
}
