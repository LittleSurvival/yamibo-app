package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.*

class AppSyncV3EnvelopeCodecTest {
    private val codec = AppSyncV3EnvelopeCodec()
    private val account = "synthetic-account"
    private val identity = "synthetic-generation"

    @Test
    fun bothKindsRoundTripCanonicalFieldsWithDeterministicMetadataAndBytes() {
        val fields = mapOf(27 to AppSyncCanonicalValue.Integer(2), 31 to AppSyncCanonicalValue.Decimal(0.5))
        val bytes = AppSyncCanonicalFieldCodec.encode("reading.thread", "id", fields).toByteString()
        for (kind in AppSyncV3PayloadKind.entries) {
            val wire = codec.encode(kind, account, identity, bytes)
            assertEquals(wire, codec.encode(kind, account, identity, bytes))
            val result = assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(codec.decode(wire, account, kind, identity))
            assertEquals(bytes, result.bytes)
            assertEquals(AppSyncV3EnvelopeMetadata(3, 1, 1, kind, account, identity, bytes.size,
                bytes.sha256().hex()), result.metadata)
            assertEquals(fields, AppSyncCanonicalFieldCodec.decode("reading.thread", "id", result.bytes.toByteArray()))
        }
    }

    @Test
    fun emptyAndExactLimitPayloadsAreBoundedAndRoundTrip() {
        val bounded = AppSyncV3EnvelopeCodec(maximumCanonicalBytes = 8, maximumCompressedBytes = 100)
        for (bytes in listOf(ByteString.EMPTY, "12345678".encodeUtf8())) {
            assertEquals(bytes, assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(bounded.decode(
                bounded.encode(AppSyncV3PayloadKind.Journal, account, identity, bytes), account,
                AppSyncV3PayloadKind.Journal, identity)).bytes)
        }
        assertFailsWith<IllegalArgumentException> {
            bounded.encode(AppSyncV3PayloadKind.Journal, account, identity, "123456789".encodeUtf8())
        }
        assertFailsWith<IllegalArgumentException> {
            AppSyncV3EnvelopeCodec(maximumCompressedBytes = 1).encode(
                AppSyncV3PayloadKind.Journal, account, identity, ByteString.EMPTY)
        }
    }

    @Test
    fun futureVersionsReturnCompatibilityWithoutExposingOrDecodingPayload() {
        val wire = encode("synthetic")
        for ((key, value) in listOf("schema" to 4, "codec" to 2, "compressor" to 2)) {
            val result = assertIs<AppSyncV3EnvelopeRead.Unsupported>(decode(replace(wire, key, value.toString())))
            assertEquals(if (key == "schema") 4 else 3, result.schemaVersion)
            assertEquals(if (key == "codec") 2 else 1, result.codecVersion)
            assertEquals(if (key == "compressor") 2 else 1, result.compressorId)
        }
    }

    @Test
    fun accountKindAndIdentityAreCheckedAgainstExpectedContext() {
        val wire = encode("synthetic")
        assertError(AppSyncV3EnvelopeError.BindingMismatch, codec.decode(wire, "another-account", AppSyncV3PayloadKind.Journal, identity))
        assertError(AppSyncV3EnvelopeError.BindingMismatch, codec.decode(wire, account, AppSyncV3PayloadKind.Checkpoint, identity))
        assertError(AppSyncV3EnvelopeError.BindingMismatch, codec.decode(wire, account, AppSyncV3PayloadKind.Journal, "another-id"))
        // Supplying the tampered expected binding does not bypass the envelope integrity check.
        assertError(AppSyncV3EnvelopeError.Integrity, codec.decode(replace(wire, "account", "another-account"),
            "another-account", AppSyncV3PayloadKind.Journal, identity))
        assertError(AppSyncV3EnvelopeError.Integrity, codec.decode(replace(wire, "kind", "2"),
            account, AppSyncV3PayloadKind.Checkpoint, identity))
        assertError(AppSyncV3EnvelopeError.Integrity, codec.decode(replace(wire, "identity", "another-id"),
            account, AppSyncV3PayloadKind.Journal, "another-id"))
    }

    @Test
    fun strictFramingRejectsDuplicatesUnknownKeysAndNoncanonicalNumbersOrBase64() {
        val wire = encode("synthetic")
        listOf(wire + "\n", wire.replace("codec=1", "schema=3"), wire.replace("codec=1", "future=1"),
            wire.replace("codec=1", "codec=1\ncodec=1"), wire.replace("\n", "\r\n")).forEach {
            assertError(AppSyncV3EnvelopeError.Framing, decode(it))
        }
        listOf("-1", "+3", "03", "2147483648", "NaN").forEach {
            assertError(AppSyncV3EnvelopeError.Metadata, decode(replace(wire, "schema", it)))
        }
        assertError(AppSyncV3EnvelopeError.Base64, decode(replace(wire, "payload", "!")))
        assertError(AppSyncV3EnvelopeError.Base64, decode(replace(wire, "payload", value(wire, "payload") + " ")))
    }

    @Test
    fun declaredSizeCapsExpansionEvenWhenTheAttackerRecomputesTheIntegrityDigest() {
        val wire = encode("x".repeat(100_000))
        assertError(AppSyncV3EnvelopeError.Length, decode(resign(replace(wire, "length", "8"))))
        assertError(AppSyncV3EnvelopeError.Length, decode(resign(replace(wire, "length", "0"))))
        assertError(AppSyncV3EnvelopeError.Length, decode(resign(replace(wire, "length", "100001"))))
        val bounded = AppSyncV3EnvelopeCodec(maximumCanonicalBytes = 99_999)
        assertError(AppSyncV3EnvelopeError.Metadata, bounded.decode(wire, account, AppSyncV3PayloadKind.Journal, identity))
        val smallCompressed = AppSyncV3EnvelopeCodec(maximumCompressedBytes = 8)
        assertError(AppSyncV3EnvelopeError.CompressedLimit,
            smallCompressed.decode(wire, account, AppSyncV3PayloadKind.Journal, identity))
        assertError(AppSyncV3EnvelopeError.EnvelopeLimit, smallCompressed.decode("x".repeat(5000),
            account, AppSyncV3PayloadKind.Journal, identity))
    }

    @Test
    fun hashesAndGzipTrailerAreVerifiedAndErrorsNeverReturnPrivatePayloads() {
        val wire = encode("synthetic-private-payload")
        val compressed = requireNotNull(value(wire, "payload").decodeBase64()).toByteArray()
        compressed[compressed.lastIndex - 4] = (compressed[compressed.lastIndex - 4].toInt() xor 1).toByte()
        val damaged = replace(wire, "payload", compressed.toByteString().base64())
        assertError(AppSyncV3EnvelopeError.Integrity, decode(damaged))
        assertError(AppSyncV3EnvelopeError.Compression, decode(resign(damaged)))
        assertError(AppSyncV3EnvelopeError.CanonicalFingerprint, decode(resign(replace(wire, "canonical", "0".repeat(64)))))
        assertError(AppSyncV3EnvelopeError.Compression, decode(resign(replace(wire, "payload", "not-gzip".encodeUtf8().base64()))))
        val withTrailingByte = Buffer().write(requireNotNull(value(wire, "payload").decodeBase64())).writeByte(0).readByteString()
        assertError(AppSyncV3EnvelopeError.Compression, decode(resign(replace(wire, "payload", withTrailingByte.base64()))))
        assertFalse(decode(damaged).toString().contains("synthetic-private"))
    }

    @Test
    fun metadataTextCannotInjectHeadersAndUsesUtf8ByteLimits() {
        for (invalid in listOf("", " ", "id\ncodec=2", "id\rvalue", "id\u0000", "\uD800", "百".repeat(342))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                codec.encode(AppSyncV3PayloadKind.Journal, account, invalid, ByteString.EMPTY)
            }
            assertEquals("Invalid v3 envelope binding", failure.message)
        }
        val valid = "百".repeat(341) + "a"
        val wire = codec.encode(AppSyncV3PayloadKind.Checkpoint, account, valid, ByteString.EMPTY)
        assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(codec.decode(wire, account, AppSyncV3PayloadKind.Checkpoint, valid))
    }

    private fun encode(text: String) = codec.encode(AppSyncV3PayloadKind.Journal, account, identity, text.encodeUtf8())
    private fun decode(wire: String) = codec.decode(wire, account, AppSyncV3PayloadKind.Journal, identity)
    private fun assertError(error: AppSyncV3EnvelopeError, result: AppSyncV3EnvelopeRead) {
        assertEquals(error, assertIs<AppSyncV3EnvelopeRead.Invalid>(result).reason)
    }
    private fun value(wire: String, key: String) = wire.lineSequence().single { it.startsWith("$key=") }.substringAfter('=')
    private fun replace(wire: String, key: String, value: String) = wire.lines().joinToString("\n") {
        if (it.startsWith("$key=")) "$key=$value" else it
    }
    private fun resign(wire: String): String {
        val header = wire.substringBefore("integrity=")
        val compressed = requireNotNull(value(wire, "payload").decodeBase64())
        val hash = Buffer().writeUtf8(header).write(compressed).readByteString().sha256().hex()
        return replace(wire, "integrity", hash)
    }
}
