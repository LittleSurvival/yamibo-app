package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class AppSyncCanonicalJournalCodecTest {
    private val codec = AppSyncCanonicalJournalCodec()
    private fun empty() = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock("a", emptyList()),
        "d", "e", "n", 0, 0, emptyMap(), emptyList(), 0, 3, 3, "v", null)
    private fun operation(sequence: Long) = AppSyncCanonicalOperation("d", "e", sequence, 14, "2026-09-18", 1,
        SyncOperationKind.Patch, 10, SyncOperationOrigin.UserAction, fields = mapOf(54 to AppSyncCanonicalValue.Integer(42)))
    private fun journal() = empty().copy(block = AppSyncCanonicalOperationBlock("a", listOf(operation(10), operation(11))),
        firstSequence = 10, lastSequence = 11, observed = mapOf("d:e" to 11, "other:epoch" to 42),
        acknowledgements = listOf(AppSyncCanonicalAcknowledgement("checkpoint", mapOf("d:e" to 9))),
        heartbeatAtEpochMillis = Long.MIN_VALUE, publishedThroughSequence = 20)
    private fun read(bytes: okio.ByteString) = codec.decode("a", "d", "e", bytes)

    @Test
    fun metadataOnlyGoldenVectorPreservesAbsentPublishedWatermark() {
        val expected = "594a52330101640165016e000000000003030176000b594f423302000101610000".decodeHex()
        assertEquals(expected, codec.encode(empty()))
        assertEquals(empty(), read(expected))
        assertNotEquals(expected, codec.encode(empty().copy(publishedThroughSequence = 0)))
        val retired = empty().copy(observed = mapOf("d:e" to 90), publishedThroughSequence = 100)
        assertEquals(retired, read(codec.encode(retired)))
    }

    @Test
    fun everyMetadataFieldSurvivesRoundTripAndInputOrderDoesNotAffectBytes() {
        val source = journal().copy(acknowledgements = listOf(
            AppSyncCanonicalAcknowledgement("a", mapOf("d:e" to 9, "other:epoch" to 20)),
            AppSyncCanonicalAcknowledgement("z", mapOf("d:e" to 8))))
        val encoded = codec.encode(source)
        assertEquals(source, read(encoded))
        val reordered = source.copy(block = source.block.copy(operations = source.block.operations.reversed()),
            observed = source.observed.entries.reversed().associate { it.toPair() },
            acknowledgements = source.acknowledgements.reversed().map {
                it.copy(coverage = it.coverage.entries.reversed().associate { entry -> entry.toPair() })
            })
        assertEquals(encoded, codec.encode(reordered))
        val envelope = AppSyncV3EnvelopeCodec()
        val transport = envelope.encode(AppSyncV3PayloadKind.Journal, "a", "journal-identity", encoded)
        val verified = assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(envelope.decode(transport, "a", AppSyncV3PayloadKind.Journal, "journal-identity"))
        assertEquals(source, read(verified.bytes))
    }

    @Test
    fun invalidOwnersRangesGapsCapabilitiesAndPublishedClaimsFailBeforeEncoding() {
        val source = journal()
        listOf(source.copy(firstSequence = 9), source.copy(lastSequence = 12), source.copy(firstSequence = -1),
            source.copy(deviceId = "other"), source.copy(deviceEpoch = "other"), source.copy(writerNonce = "\n"),
            source.copy(protocolReadVersion = 2), source.copy(protocolWriteVersion = 2),
            source.copy(publishedThroughSequence = 10), source.copy(observed = mapOf("d:e" to 21)),
            source.copy(observed = mapOf("d:e" to -1)),
            source.copy(acknowledgements = source.acknowledgements + source.acknowledgements),
            source.copy(acknowledgements = listOf(AppSyncCanonicalAcknowledgement("c", mapOf("d:e" to -1)))),
            source.copy(lastSequence = 12, block = source.block.copy(operations = listOf(operation(10), operation(12)))),
            empty().copy(lastSequence = 1)).forEach { assertFails { codec.encode(it) } }
        val extreme = empty().copy(block = AppSyncCanonicalOperationBlock("a", listOf(operation(Long.MAX_VALUE))),
            firstSequence = Long.MAX_VALUE, lastSequence = Long.MAX_VALUE, publishedThroughSequence = Long.MAX_VALUE)
        assertEquals(extreme, read(codec.encode(extreme)))
    }

    @Test
    fun combinedMetadataBudgetCountsAcknowledgementCoverageAndExactByteLimit() {
        val source = journal()
        val bytes = codec.encode(source)
        assertEquals(source, AppSyncCanonicalJournalCodec(maximumBytes = bytes.size, maximumMetadataEntries = 4).decode("a", "d", "e", bytes))
        assertFails { AppSyncCanonicalJournalCodec(maximumMetadataEntries = 3).encode(source) }
        assertFails { AppSyncCanonicalJournalCodec(maximumMetadataEntries = 3).decode("a", "d", "e", bytes) }
        assertFails { AppSyncCanonicalJournalCodec(maximumBytes = bytes.size - 1).encode(source) }
        assertFails { AppSyncCanonicalJournalCodec(maximumBytes = bytes.size - 1).decode("a", "d", "e", bytes) }
    }

    @Test
    fun malformedOrTruncatedDocumentsReturnNoPartialOperationsOrPrivateErrors() {
        val bytes = codec.encode(journal())
        for (size in 0 until bytes.size) assertFails { read(bytes.substring(0, size)) }
        assertFails { read((bytes.toByteArray() + 0).toByteString()) }
        for ((account, device, epoch) in listOf(Triple("wrong", "d", "e"), Triple("a", "wrong", "e"), Triple("a", "d", "wrong"))) {
            val failure = assertFailsWith<IllegalArgumentException> { codec.decode(account, device, epoch, bytes) }
            assertEquals("Invalid canonical journal", failure.message)
            assertNull(failure.cause)
        }
        val golden = codec.encode(empty()).toByteArray()
        for ((offset, value) in listOf(4 to 2, 5 to 127, 11 to 255, 13 to 127, 14 to 127, 16 to 2, 20 to 2, 21 to 127)) {
            assertFails("offset $offset") { read(golden.copyOf().also { it[offset] = value.toByte() }.toByteString()) }
        }
    }
}
