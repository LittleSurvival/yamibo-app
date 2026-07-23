package me.thenano.yamibo.yamibo_app.repository.appsync.codec

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncValidator
import me.thenano.yamibo.yamibo_app.repository.appsync.migration.AppSyncMigrator
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*

class AppSyncTextCodec(
    private val normalizer: AppSyncInputNormalizer = AppSyncInputNormalizer(),
    private val migrator: AppSyncMigrator = AppSyncMigrator(),
    private val validator: AppSyncValidator = AppSyncValidator(),
    private val maxCompressedBytes: Int = AppSyncFormat.MAX_COMPRESSED_BYTES,
    private val maxDecompressedBytes: Int = AppSyncFormat.MAX_DECOMPRESSED_BYTES,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        explicitNulls = true
    },
) {
    fun encode(snapshot: AppSyncEnvelope): AppSyncResult<String> {
        val violations = validator.validate(snapshot)
        if (violations.isNotEmpty()) return AppSyncResult.Failure(AppSyncError.Validation(violations))
        return try {
            val jsonBytes = json.encodeToString(snapshot).encodeToByteArray()
            if (jsonBytes.size > maxDecompressedBytes) {
                return codecFailure(AppSyncCodecErrorKind.PayloadTooLarge, "JSON exceeds $maxDecompressedBytes bytes")
            }
            val output = Buffer()
            val sink = GzipSink(output).buffer()
            try {
                sink.write(jsonBytes)
            } finally {
                sink.close()
            }
            if (output.size > maxCompressedBytes) {
                return codecFailure(AppSyncCodecErrorKind.PayloadTooLarge, "Gzip payload exceeds $maxCompressedBytes bytes")
            }
            AppSyncResult.Success(AppSyncFormat.FULL_FRAME_PREFIX + output.readByteString().base64())
        } catch (error: Throwable) {
            codecFailure(AppSyncCodecErrorKind.InvalidJson, "Unable to encode app-sync JSON: ${error.message}")
        }
    }

    fun decode(rawText: String): AppSyncResult<AppSyncEnvelope> {
        val normalized = when (val result = normalizer.normalize(rawText)) {
            is AppSyncResult.Success -> result.value
            is AppSyncResult.Failure -> return result
        }
        if (!normalized.startsWith(AppSyncFormat.FRAME_PREFIX)) {
            return codecFailure(AppSyncCodecErrorKind.MalformedFrame, "App-sync framing prefix is missing")
        }
        if (!normalized.startsWith(AppSyncFormat.FULL_FRAME_PREFIX)) {
            return codecFailure(AppSyncCodecErrorKind.UnsupportedCodecVersion, "App-sync codec version is unsupported")
        }
        val encoded = normalized.removePrefix(AppSyncFormat.FULL_FRAME_PREFIX)
        if (encoded.isEmpty()) return codecFailure(AppSyncCodecErrorKind.InvalidBase64, "Base64 payload is empty")
        val compressed = encoded.decodeBase64()
            ?: return codecFailure(AppSyncCodecErrorKind.InvalidBase64, "Base64 payload is invalid")
        if (compressed.size > maxCompressedBytes) {
            return codecFailure(AppSyncCodecErrorKind.PayloadTooLarge, "Gzip payload exceeds $maxCompressedBytes bytes")
        }

        val bytes = try {
            val compressedBuffer = Buffer().write(compressed)
            val source = GzipSource(compressedBuffer).buffer()
            val output = Buffer()
            try {
                while (true) {
                    val remaining = maxDecompressedBytes.toLong() - output.size
                    if (remaining < 0) {
                        return codecFailure(AppSyncCodecErrorKind.PayloadTooLarge, "Decoded JSON exceeds $maxDecompressedBytes bytes")
                    }
                    val read = source.read(output, minOf(8_192L, remaining + 1L))
                    if (read == -1L) break
                    if (output.size > maxDecompressedBytes) {
                        return codecFailure(AppSyncCodecErrorKind.PayloadTooLarge, "Decoded JSON exceeds $maxDecompressedBytes bytes")
                    }
                }
            } finally {
                source.close()
            }
            output.readByteArray()
        } catch (error: Throwable) {
            return codecFailure(AppSyncCodecErrorKind.InvalidGzip, "Gzip payload is invalid: ${error.message}")
        }

        val jsonText = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: Throwable) {
            return codecFailure(AppSyncCodecErrorKind.InvalidUtf8, "Decoded payload is not valid UTF-8")
        }
        val jsonObject = try {
            json.parseToJsonElement(jsonText) as? JsonObject
                ?: return codecFailure(AppSyncCodecErrorKind.InvalidJson, "App-sync JSON root must be an object")
        } catch (error: SerializationException) {
            return codecFailure(AppSyncCodecErrorKind.InvalidJson, "App-sync JSON is invalid: ${error.message}")
        } catch (error: IllegalArgumentException) {
            return codecFailure(AppSyncCodecErrorKind.InvalidJson, "App-sync JSON is invalid: ${error.message}")
        }
        val migrated = when (val result = migrator.migrate(jsonObject)) {
            is AppSyncResult.Success -> result.value
            is AppSyncResult.Failure -> return result
        }
        val snapshot = try {
            json.decodeFromJsonElement<AppSyncEnvelope>(migrated)
        } catch (error: Throwable) {
            return codecFailure(AppSyncCodecErrorKind.InvalidJson, "Current app-sync schema is invalid: ${error.message}")
        }
        val violations = validator.validate(snapshot)
        return if (violations.isEmpty()) {
            AppSyncResult.Success(snapshot)
        } else {
            AppSyncResult.Failure(AppSyncError.Validation(violations))
        }
    }

    private fun <T> codecFailure(kind: AppSyncCodecErrorKind, message: String): AppSyncResult<T> =
        AppSyncResult.Failure(AppSyncError.Codec(kind, message))
}
