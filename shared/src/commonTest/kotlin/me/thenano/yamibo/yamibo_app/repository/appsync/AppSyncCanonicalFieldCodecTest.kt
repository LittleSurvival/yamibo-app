package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class AppSyncCanonicalFieldCodecTest {
    @Test
    fun goldenTupleUsesOneByteFieldIdsAndIsMapOrderIndependent() {
        val values = linkedMapOf(31 to AppSyncCanonicalValue.Decimal(0.5), 27 to AppSyncCanonicalValue.Integer(2))
        val expected = "021b04041f053fe0000000000000".decodeHex().toByteArray()
        assertContentEquals(expected, AppSyncCanonicalFieldCodec.encode("reading.thread", "id", values))
        assertContentEquals(expected, AppSyncCanonicalFieldCodec.encode("reading.thread", "id", values.entries.reversed().associate { it.toPair() }))
        assertEquals(values, AppSyncCanonicalFieldCodec.decode("reading.thread", "id", expected))
    }

    @Test
    fun signedExtremesAndAllScalarKindsRoundTrip() {
        listOf(Long.MIN_VALUE, -129L, -1L, 0L, 1L, 127L, 128L, Long.MAX_VALUE).forEach { value ->
            roundTrip("reading.thread", "id", mapOf(27 to AppSyncCanonicalValue.Integer(value)))
        }
        roundTrip("reading.thread", "id", mapOf(26 to AppSyncCanonicalValue.Text("標題🙂"),
            31 to AppSyncCanonicalValue.Null, 32 to AppSyncCanonicalValue.Identifier("stable-block"),
            33 to AppSyncCanonicalValue.Enum("text"), 34 to AppSyncCanonicalValue.Decimal(0.25)))
        roundTrip("bookmark", "id", mapOf(21 to AppSyncCanonicalValue.Boolean(false)))
        roundTrip("settings", "novelreadersettings.linespacing", mapOf(2 to AppSyncCanonicalValue.Decimal(1.5)))
    }

    @Test
    fun decodeRejectsCorruptionDuplicatesNonminimalIntegersAndAllocationClaims() {
        val invalid = listOf(
            "", "8000", "ffffffffffffffffff02", // count/truncation/overflow
            "011b0400ff", "021b04001b0402", // trailing / duplicate
            "021f001b0402", // out of order
            "011b048000", "011b04ffffffffffffffffff02", // nonminimal integer / overflow
            "011a01ffffffffffffffffff01", // over-budget string length
            "011a0101ff", // invalid UTF-8
            "011b00", "011b0601", "011bff", // null / type confusion / unknown scalar
            "011f057ff8000000000000", "011f058000000000000000", // NaN / negative zero
            "01440100", // cover field
        )
        invalid.forEach { hex -> assertFails("accepted malformed $hex") {
            AppSyncCanonicalFieldCodec.decode("reading.thread", "id", hex.decodeHex().toByteArray())
        } }
    }

    @Test
    fun writerRejectsBypassedPolicyWithoutReturningPayloadContentInErrors() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AppSyncCanonicalFieldCodec.encode("favorite.item", "private-identity", mapOf(6 to AppSyncCanonicalValue.Text("<html>secret</html>")))
        }
        assertFalse(failure.toString().contains("secret"))
        assertFalse(failure.toString().contains("private-identity"))
        assertFails { AppSyncCanonicalFieldCodec.encode("favorite.item", "id", mapOf(4 to AppSyncCanonicalValue.Integer(1))) }
        assertFails { AppSyncCanonicalFieldCodec.encode("reading.thread", "id", mapOf(27 to AppSyncCanonicalValue.Text("2"))) }
    }

    private fun roundTrip(domain: String, id: String, values: Map<Int, AppSyncCanonicalValue>) {
        val encoded = AppSyncCanonicalFieldCodec.encode(domain, id, values)
        assertEquals(values, AppSyncCanonicalFieldCodec.decode(domain, id, encoded), encoded.toByteString().hex())
    }
}
