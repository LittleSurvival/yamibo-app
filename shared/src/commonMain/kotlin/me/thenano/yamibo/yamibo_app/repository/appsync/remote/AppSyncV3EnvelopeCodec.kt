package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.GzipSink
import okio.GzipSource
import okio.buffer

internal enum class AppSyncV3PayloadKind(val wireId: Int) { Journal(1), Checkpoint(2) }

internal data class AppSyncV3EnvelopeMetadata(
    val schemaVersion: Int,
    val codecVersion: Int,
    val compressorId: Int,
    val kind: AppSyncV3PayloadKind,
    val accountBinding: String,
    val identity: String,
    val uncompressedLength: Int,
    val canonicalFingerprint: String,
)

internal enum class AppSyncV3EnvelopeError {
    EnvelopeLimit, Framing, Metadata, BindingMismatch, CompressedLimit, Base64,
    Integrity, Compression, Length, CanonicalFingerprint,
}

internal sealed interface AppSyncV3EnvelopeRead {
    /** Transport verified only: the tuple/schema reader must still validate before reduction. */
    data class VerifiedBytes(val metadata: AppSyncV3EnvelopeMetadata, val bytes: ByteString) : AppSyncV3EnvelopeRead
    data class Unsupported(val schemaVersion: Int, val codecVersion: Int, val compressorId: Int) : AppSyncV3EnvelopeRead
    data class Invalid(val reason: AppSyncV3EnvelopeError) : AppSyncV3EnvelopeRead
}

/**
 * Reader-first v3 transport foundation, intentionally not connected to publication/capabilities.
 * Canonical bytes are gzip-compressed once, then Base64 encoded at the text boundary. Numeric
 * IDs are permanent: schema 3, canonical tuple codec 1, gzip 1. Other compressor IDs are reserved
 * until platform benchmarking. SHA-256 checks integrity, not sender authenticity.
 */
internal class AppSyncV3EnvelopeCodec(
    private val maximumCanonicalBytes: Int = 64 * 1024 * 1024,
    private val maximumCompressedBytes: Int = 12 * 1024 * 1024,
) {
    init {
        require(maximumCanonicalBytes > 0 && maximumCompressedBytes > 0) { "Invalid v3 envelope limits" }
    }

    private val maximumBase64Chars = ((maximumCompressedBytes.toLong() + 2) / 3) * 4
    private val maximumEnvelopeChars = maximumBase64Chars + 4096

    fun encode(kind: AppSyncV3PayloadKind, accountBinding: String, identity: String, canonicalBytes: ByteString): String {
        require(validIdentity(accountBinding) && validIdentity(identity)) { "Invalid v3 envelope binding" }
        require(canonicalBytes.size <= maximumCanonicalBytes) { "V3 canonical byte limit exceeded" }
        val metadata = AppSyncV3EnvelopeMetadata(SCHEMA, CODEC, GZIP, kind, accountBinding, identity,
            canonicalBytes.size, canonicalBytes.sha256().hex())
        val output = Buffer()
        GzipSink(output).buffer().use { it.write(canonicalBytes) }
        require(output.size <= maximumCompressedBytes) { "V3 compressed byte limit exceeded" }
        val compressed = output.readByteString()
        val header = header(metadata)
        return header + "integrity=${integrity(header, compressed)}\npayload=${compressed.base64()}\n$END"
    }

    fun decode(text: String, expectedAccount: String, expectedKind: AppSyncV3PayloadKind,
        expectedIdentity: String?): AppSyncV3EnvelopeRead {
        if (text.length.toLong() > maximumEnvelopeChars) return invalid(AppSyncV3EnvelopeError.EnvelopeLimit)
        // Limit splitting so an attacker cannot allocate one object for each newline.
        val lines = text.split('\n', limit = 13)
        if (lines.size != 12 || lines.first() != BEGIN || lines.last() != END) {
            return invalid(AppSyncV3EnvelopeError.Framing)
        }
        val keys = listOf("schema", "codec", "compressor", "kind", "account", "identity", "length", "canonical", "integrity", "payload")
        val values = keys.mapIndexed { index, key ->
            val line = lines[index + 1]
            if (!line.startsWith("$key=")) return invalid(AppSyncV3EnvelopeError.Framing)
            line.substring(key.length + 1)
        }
        val schema = unsignedInt(values[0]) ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        val codec = unsignedInt(values[1]) ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        val compressor = unsignedInt(values[2]) ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        val kindId = unsignedInt(values[3]) ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        val kind = AppSyncV3PayloadKind.entries.singleOrNull { it.wireId == kindId }
            ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        val account = values[4]
        val identity = values[5]
        val length = unsignedInt(values[6]) ?: return invalid(AppSyncV3EnvelopeError.Metadata)
        if (!validIdentity(account) || !validIdentity(identity) || length > maximumCanonicalBytes ||
            !isDigest(values[7]) || !isDigest(values[8])) return invalid(AppSyncV3EnvelopeError.Metadata)
        if (account != expectedAccount || kind != expectedKind || (expectedIdentity != null && identity != expectedIdentity)) {
            return invalid(AppSyncV3EnvelopeError.BindingMismatch)
        }
        if (schema != SCHEMA || codec != CODEC || compressor != GZIP) {
            return AppSyncV3EnvelopeRead.Unsupported(schema, codec, compressor)
        }
        val base64 = values[9]
        if (base64.length.toLong() > maximumBase64Chars) return invalid(AppSyncV3EnvelopeError.CompressedLimit)
        // Okio accepts whitespace/noncanonical padding; the v3 wire contract deliberately does not.
        val compressed = base64.decodeBase64() ?: return invalid(AppSyncV3EnvelopeError.Base64)
        if (compressed.size > maximumCompressedBytes) return invalid(AppSyncV3EnvelopeError.CompressedLimit)
        if (compressed.base64() != base64) return invalid(AppSyncV3EnvelopeError.Base64)
        val metadata = AppSyncV3EnvelopeMetadata(schema, codec, compressor, kind, account, identity, length, values[7])
        if (integrity(header(metadata), compressed) != values[8]) return invalid(AppSyncV3EnvelopeError.Integrity)
        val decompressed = Buffer()
        try {
            GzipSource(Buffer().write(compressed)).buffer().use { source ->
                while (true) {
                    // Read at most one byte beyond the declared length, including for zero length.
                    val remaining = length.toLong() - decompressed.size
                    val count = source.read(decompressed, minOf(8192L, remaining + 1))
                    if (count == -1L) break
                    if (decompressed.size > length) return invalid(AppSyncV3EnvelopeError.Length)
                }
            }
        } catch (_: Exception) {
            return invalid(AppSyncV3EnvelopeError.Compression)
        }
        if (decompressed.size != length.toLong()) return invalid(AppSyncV3EnvelopeError.Length)
        val bytes = decompressed.readByteString()
        if (bytes.sha256().hex() != metadata.canonicalFingerprint) return invalid(AppSyncV3EnvelopeError.CanonicalFingerprint)
        return AppSyncV3EnvelopeRead.VerifiedBytes(metadata, bytes)
    }

    private fun header(metadata: AppSyncV3EnvelopeMetadata) = buildString {
        appendLine(BEGIN)
        appendLine("schema=${metadata.schemaVersion}")
        appendLine("codec=${metadata.codecVersion}")
        appendLine("compressor=${metadata.compressorId}")
        appendLine("kind=${metadata.kind.wireId}")
        appendLine("account=${metadata.accountBinding}")
        appendLine("identity=${metadata.identity}")
        appendLine("length=${metadata.uncompressedLength}")
        appendLine("canonical=${metadata.canonicalFingerprint}")
    }

    private fun integrity(header: String, compressed: ByteString): String =
        Buffer().writeUtf8(header).write(compressed).readByteString().sha256().hex()

    private fun unsignedInt(text: String): Int? = text.toIntOrNull()?.takeIf { it >= 0 && it.toString() == text }
    private fun isDigest(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
    private fun validIdentity(text: String): Boolean = text.isNotBlank() && text == text.trim() &&
        text.length <= 1024 && text.none { it.code < 32 || it.code == 127 } &&
        runCatching { text.encodeToByteArray(throwOnInvalidSequence = true).size <= 1024 }.getOrDefault(false)
    private fun invalid(reason: AppSyncV3EnvelopeError) = AppSyncV3EnvelopeRead.Invalid(reason)

    private companion object {
        const val SCHEMA = 3
        const val CODEC = 1
        const val GZIP = 1
        const val BEGIN = "[YAMIBO_APP_SYNC_ENVELOPE:v3:BEGIN]"
        const val END = "[YAMIBO_APP_SYNC_ENVELOPE:v3:END]"
    }
}
