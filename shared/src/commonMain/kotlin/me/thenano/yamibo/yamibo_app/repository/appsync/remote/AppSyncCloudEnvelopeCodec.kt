package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults

@Serializable
private data class AppSyncCloudPayload(
    val encodedSnapshot: String,
    val updatedAtEpochMillis: Long,
)

data class ParsedAppSyncCloudEnvelope(
    val encodedSnapshot: String,
    val updatedAtEpochMillis: Long,
    val schemaVersion: Int,
    val fingerprint: String,
)

sealed interface AppSyncCloudEnvelopeValidation {
    data class Valid(
        val envelope: ParsedAppSyncCloudEnvelope,
    ) : AppSyncCloudEnvelopeValidation

    data class Invalid(
        val reason: String,
        val markerPresent: Boolean,
    ) : AppSyncCloudEnvelopeValidation
}

class AppSyncCloudEnvelopeCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    fun encode(encodedSnapshot: String, updatedAtEpochMillis: Long): String {
        val payload = json.encodeToString(
            AppSyncCloudPayload(
                encodedSnapshot = encodedSnapshot,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
        )
        val fingerprint = stableAppSyncFingerprint(payload)
        return buildString {
            appendLine(BEGIN_MARKER)
            appendLine("schema=${AppSyncCloudConfigDefaults.ENVELOPE_SCHEMA_VERSION}")
            appendLine("fingerprint=$fingerprint")
            appendLine("payload=$payload")
            append(END_MARKER)
        }
    }

    fun validateReaderHtml(contentHtml: String): AppSyncCloudEnvelopeValidation =
        validate(normalizeReaderHtml(contentHtml))

    fun validate(text: String): AppSyncCloudEnvelopeValidation {
        val markerPresent = text.contains(AppSyncCloudConfigDefaults.MARKER)
        if (!markerPresent) {
            return AppSyncCloudEnvelopeValidation.Invalid(
                reason = "Config marker is missing",
                markerPresent = false,
            )
        }

        val beginIndex = text.indexOf(BEGIN_MARKER)
        val endIndex = text.indexOf(END_MARKER, startIndex = beginIndex.coerceAtLeast(0))
        if (beginIndex < 0 || endIndex < 0 || endIndex <= beginIndex) {
            return AppSyncCloudEnvelopeValidation.Invalid(
                reason = "Config envelope boundary is incomplete",
                markerPresent = true,
            )
        }

        val body = text.substring(beginIndex + BEGIN_MARKER.length, endIndex).trim()
        val schemaVersion = SCHEMA_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return invalid("Config schema version is missing")
        if (schemaVersion != AppSyncCloudConfigDefaults.ENVELOPE_SCHEMA_VERSION) {
            return invalid("Unsupported config schema version: $schemaVersion")
        }

        val fingerprint = FINGERPRINT_REGEX.find(body)?.groupValues?.get(1)
            ?: return invalid("Config fingerprint is missing")
        val payloadText = PAYLOAD_REGEX.find(body)?.groupValues?.get(1)?.trim()
            ?: return invalid("Config JSON payload is missing")
        val payload = try {
            json.decodeFromString<AppSyncCloudPayload>(payloadText)
        } catch (error: Throwable) {
            return invalid("Config JSON payload is invalid: ${error.message ?: error::class.simpleName}")
        }
        val expectedFingerprint = stableAppSyncFingerprint(
            json.encodeToString(payload),
        )
        if (fingerprint != expectedFingerprint) {
            return invalid("Config fingerprint does not match the payload")
        }

        return AppSyncCloudEnvelopeValidation.Valid(
            ParsedAppSyncCloudEnvelope(
                encodedSnapshot = payload.encodedSnapshot,
                updatedAtEpochMillis = payload.updatedAtEpochMillis,
                schemaVersion = schemaVersion,
                fingerprint = fingerprint,
            ),
        )
    }

    fun fingerprintFor(encodedSnapshot: String, updatedAtEpochMillis: Long): String {
        val payload = json.encodeToString(
            AppSyncCloudPayload(encodedSnapshot, updatedAtEpochMillis),
        )
        return stableAppSyncFingerprint(payload)
    }

    private fun normalizeReaderHtml(contentHtml: String): String =
        try {
            Ksoup.parseBodyFragment(contentHtml).body().text()
        } catch (_: Throwable) {
            contentHtml
        }

    private fun invalid(reason: String): AppSyncCloudEnvelopeValidation.Invalid =
        AppSyncCloudEnvelopeValidation.Invalid(reason = reason, markerPresent = true)

    companion object {
        private const val BEGIN_MARKER =
            "[${AppSyncCloudConfigDefaults.MARKER}:BEGIN]"
        private const val END_MARKER =
            "[${AppSyncCloudConfigDefaults.MARKER}:END]"
        private val SCHEMA_REGEX = Regex("""(?:^|\s)schema=(\d+)(?=\s|$)""")
        private val FINGERPRINT_REGEX =
            Regex("""(?:^|\s)fingerprint=([0-9a-fA-F]+)(?=\s|$)""")
        private val PAYLOAD_REGEX =
            Regex("""(?:^|\s)payload=(\{[\s\S]*\})\s*$""")
    }
}
