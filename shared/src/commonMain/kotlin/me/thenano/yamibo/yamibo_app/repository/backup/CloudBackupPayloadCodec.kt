package me.thenano.yamibo.yamibo_app.repository.backup

import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * Encodes the existing backup wire model for storage inside the cloud blog envelope.
 *
 * Gzip and Base64 are transport encodings, not encryption.
 */
internal class CloudBackupPayloadCodec(
    private val maxCompressedBytes: Int = MAX_COMPRESSED_BYTES,
    private val maxDecompressedBytes: Int = MAX_DECOMPRESSED_BYTES,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        explicitNulls = true
    },
) {
    fun encode(snapshot: YamiboBackupFile): Result<String> = runCatching {
        require(snapshot.schemaVersion == CURRENT_BACKUP_SCHEMA_VERSION) {
            "Unsupported backup schema version: ${snapshot.schemaVersion}"
        }
        val jsonBytes = json.encodeToString(YamiboBackupFile.serializer(), snapshot).encodeToByteArray()
        require(jsonBytes.size <= maxDecompressedBytes) {
            "Backup JSON exceeds $maxDecompressedBytes bytes"
        }

        val output = Buffer()
        val sink = GzipSink(output).buffer()
        try {
            sink.write(jsonBytes)
        } finally {
            sink.close()
        }
        require(output.size <= maxCompressedBytes) {
            "Compressed backup exceeds $maxCompressedBytes bytes"
        }
        FULL_FRAME_PREFIX + output.readByteString().base64()
    }

    fun decode(rawText: String): Result<YamiboBackupFile> = runCatching {
        val normalized = rawText
            .replace(NBSP_ENTITY, "")
            .replace("\u00A0", "")
            .trim()
        require(normalized.startsWith(FULL_FRAME_PREFIX)) {
            "Cloud backup framing prefix is missing or unsupported"
        }

        val encoded = normalized.removePrefix(FULL_FRAME_PREFIX)
        require(encoded.isNotEmpty()) { "Base64 payload is empty" }
        val compressed = requireNotNull(encoded.decodeBase64()) { "Base64 payload is invalid" }
        require(compressed.size <= maxCompressedBytes) {
            "Compressed backup exceeds $maxCompressedBytes bytes"
        }

        val compressedBuffer = Buffer().write(compressed)
        val output = Buffer()
        val source = GzipSource(compressedBuffer).buffer()
        try {
            while (true) {
                val remaining = maxDecompressedBytes.toLong() - output.size
                require(remaining >= 0) { "Decoded backup exceeds $maxDecompressedBytes bytes" }
                val read = source.read(output, minOf(8_192L, remaining + 1L))
                if (read == -1L) break
                require(output.size <= maxDecompressedBytes) {
                    "Decoded backup exceeds $maxDecompressedBytes bytes"
                }
            }
        } finally {
            source.close()
        }

        val decoded = json.decodeFromString(
            YamiboBackupFile.serializer(),
            output.readByteArray().decodeToString(throwOnInvalidSequence = true),
        )
        require(decoded.schemaVersion == CURRENT_BACKUP_SCHEMA_VERSION) {
            "Unsupported backup schema version: ${decoded.schemaVersion}"
        }
        decoded
    }

    private companion object {
        const val FULL_FRAME_PREFIX = "yamibo-app-sync:gzip-base64:1:"
        const val MAX_COMPRESSED_BYTES = 12 * 1024 * 1024
        const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
        val NBSP_ENTITY = Regex("""&(?:nbsp|#160|#x0*a0);""", RegexOption.IGNORE_CASE)
    }
}
