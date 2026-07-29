package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding

internal const val APP_SYNC_INDEX_TITLE =
    "Yamibo App Sync Index - DO NOT EDIT - ymb-sync-9f4c2a7"

@Serializable
internal data class AppSyncIndexJournalReference(
    val replicaKey: String,
    val blogId: Int,
    val fingerprint: String?,
)

@Serializable
internal data class AppSyncIndexCheckpointReference(
    val checkpointId: String,
    val blogId: Int,
    val fingerprint: String,
)

@Serializable
internal data class AppSyncIndexPayload(
    val accountBinding: SyncAccountBinding,
    val journals: List<AppSyncIndexJournalReference>,
    val checkpoints: List<AppSyncIndexCheckpointReference> = emptyList(),
    val updatedAtEpochMillis: Long,
)

internal data class ParsedAppSyncIndexEnvelope(
    val payload: AppSyncIndexPayload,
    val fingerprint: String,
)

internal sealed interface AppSyncIndexValidation {
    data class Valid(val envelope: ParsedAppSyncIndexEnvelope) : AppSyncIndexValidation
    data class Invalid(val reason: String, val markerPresent: Boolean) : AppSyncIndexValidation
}
internal class AppSyncIndexEnvelopeCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    fun encode(payload: AppSyncIndexPayload): String {
        val normalized = payload.copy(
            journals = payload.journals.distinctBy { it.replicaKey }.sortedBy { it.replicaKey },
            checkpoints = payload.checkpoints.distinctBy { it.checkpointId }.sortedBy { it.checkpointId },
        )
        val encoded = json.encodeToString(AppSyncIndexPayload.serializer(), normalized)
        return frame(encoded, stableAppSyncFingerprint(encoded))
    }

    fun validateReaderHtml(html: String): AppSyncIndexValidation =
        validate(
            try {
                Ksoup.parseBodyFragment(html).body().text()
            } catch (_: Throwable) {
                html
            },
        )

    fun validate(text: String): AppSyncIndexValidation {
        val markerPresent = text.contains(AppSyncJournalDefaults.INDEX_MARKER)
        if (!markerPresent) return AppSyncIndexValidation.Invalid("Index marker is missing", false)
        val begin = "[${AppSyncJournalDefaults.INDEX_MARKER}:BEGIN]"
        val end = "[${AppSyncJournalDefaults.INDEX_MARKER}:END]"
        val start = text.indexOf(begin)
        val finish = text.indexOf(end, start.coerceAtLeast(0))
        if (start < 0 || finish <= start) {
            return AppSyncIndexValidation.Invalid("Index envelope boundary is incomplete", true)
        }
        val body = text.substring(start + begin.length, finish).trim()
        val schema = SCHEMA.find(body)?.groupValues?.get(1)?.toIntOrNull()
        if (schema != AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION) {
            return AppSyncIndexValidation.Invalid("Unsupported index schema: $schema", true)
        }
        val fingerprint = FINGERPRINT.find(body)?.groupValues?.get(1)
            ?: return AppSyncIndexValidation.Invalid("Index fingerprint is missing", true)
        val payloadText = PAYLOAD.find(body)?.groupValues?.get(1)?.trim()
            ?: return AppSyncIndexValidation.Invalid("Index payload is missing", true)
        if (stableAppSyncFingerprint(payloadText) != fingerprint) {
            return AppSyncIndexValidation.Invalid("Index fingerprint does not match payload", true)
        }
        val payload = try {
            json.decodeFromString(AppSyncIndexPayload.serializer(), payloadText)
        } catch (error: Throwable) {
            return AppSyncIndexValidation.Invalid(
                "Index payload is invalid: ${error.message ?: error::class.simpleName}",
                true,
            )
        }
        return AppSyncIndexValidation.Valid(ParsedAppSyncIndexEnvelope(payload, fingerprint))
    }

    private fun frame(payload: String, fingerprint: String): String = buildString {
        appendLine("[${AppSyncJournalDefaults.INDEX_MARKER}:BEGIN]")
        appendLine("schema=${AppSyncJournalDefaults.JOURNAL_SCHEMA_VERSION}")
        appendLine("fingerprint=$fingerprint")
        appendLine("payload=$payload")
        append("[${AppSyncJournalDefaults.INDEX_MARKER}:END]")
    }

    private companion object {
        val SCHEMA = Regex("""(?:^|\s)schema=(\d+)(?=\s|$)""")
        val FINGERPRINT = Regex("""(?:^|\s)fingerprint=([0-9a-fA-F]+)(?=\s|$)""")
        val PAYLOAD = Regex("""(?:^|\s)payload=(\{.*\})\s*$""", RegexOption.DOT_MATCHES_ALL)
    }
}
