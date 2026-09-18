package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BulkDeleteGuard
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class AppSyncCanonicalOperationBlockCodecTest {
    private val codec = AppSyncCanonicalOperationBlockCodec()
    private fun operation(sequence: Long = 1) = AppSyncCanonicalOperation("d", "e", sequence, 8, "1|Normal|0|Direct", 1,
        SyncOperationKind.Patch, 0, SyncOperationOrigin.UserAction,
        fields = mapOf(27 to AppSyncCanonicalValue.Integer(2)))
    private fun block(vararg operations: AppSyncCanonicalOperation) = AppSyncCanonicalOperationBlock("a", operations.toList())

    @Test
    fun fixedWireVectorAndEmptyBlockRoundTrip() {
        assertEquals("594f423302000101610000", codec.encode(block()).hex())
        val expected = "594f4233020001016100010c0101640101650108040201064e6f726d616c00010644697265637401020001000004011b0404".decodeHex()
        assertEquals(expected, codec.encode(block(operation())))
        assertEquals(block(operation()), codec.decode("a", expected))
        assertEquals(block(), codec.decode("a", codec.encode(block())))
    }

    @Test
    fun operationFieldAndCausalMapOrderDoNotChangeBytes() {
        val a = operation().copy(causalContext = linkedMapOf("replica-z" to 4, "replica-a" to 2),
            fields = linkedMapOf(31 to AppSyncCanonicalValue.Decimal(0.5), 27 to AppSyncCanonicalValue.Integer(2)))
        val b = operation(2).copy(deviceEpoch = "different-epoch")
        val reversed = a.copy(causalContext = a.causalContext.entries.reversed().associate { it.toPair() },
            fields = a.fields.entries.reversed().associate { it.toPair() })
        assertEquals(codec.encode(block(a, b)), codec.encode(block(b, reversed)))
        assertEquals(listOf(b, a), codec.decode("a", codec.encode(block(a, b))).operations)
    }

    @Test
    fun allKindsOriginsScalarsAndConflictEvidenceSurviveRoundTrip() {
        val operations = SyncOperationKind.entries.mapIndexed { index, kind ->
            operation(index + 1L).copy(kind = kind, generation = Long.MAX_VALUE,
                createdAtEpochMillis = if (index % 2 == 0) Long.MIN_VALUE else Long.MAX_VALUE,
                authorizationId = if (kind == SyncOperationKind.Delete) "authorization-proof-reference" else null,
                origin = if (kind == SyncOperationKind.Put) SyncOperationOrigin.Migration else SyncOperationOrigin.RemoteReplay,
                causalContext = mapOf("device:epoch" to Long.MAX_VALUE, "other:epoch" to 0),
                fields = if (kind in setOf(SyncOperationKind.Delete, SyncOperationKind.RelationRemove)) emptyMap() else mapOf(
                    26 to AppSyncCanonicalValue.Text("測試標題🙂"), 27 to AppSyncCanonicalValue.Integer(Long.MIN_VALUE),
                    31 to AppSyncCanonicalValue.Null, 32 to AppSyncCanonicalValue.Identifier("block-key"),
                    33 to AppSyncCanonicalValue.Enum("text"), 34 to AppSyncCanonicalValue.Decimal(0.25)))
        }.map { op ->
            if (op.kind in setOf(SyncOperationKind.RelationAdd, SyncOperationKind.RelationRemove)) {
                op.copy(domainId = 15, entityId = "ThreadNormal|1|0|category",
                    fields = if (op.kind == SyncOperationKind.RelationAdd) mapOf(7 to AppSyncCanonicalValue.Integer(1)) else emptyMap())
            } else op
        } + operation(6).copy(domainId = 7, entityId = "ThreadNormal|1|2", fields = mapOf(21 to AppSyncCanonicalValue.Boolean(true))) +
            operation(7).copy(domainId = 1, entityId = "novelreadersettings.linespacing", fields = mapOf(2 to AppSyncCanonicalValue.Decimal(1.5)))
        val value = AppSyncCanonicalOperationBlock("account", operations, listOf(AppSyncCanonicalDeleteProof(
            "authorization-proof-reference", 8, "reading-history:selected", 1, Long.MAX_VALUE)))
        assertEquals(value, codec.decode("account", codec.encode(value)))
    }

    @Test
    fun repeatedStringsAreStoredOnceAndReferencesPreserveScalarTypes() {
        val repeated = "shared-title-and-block-identity-".repeat(3)
        val ops = (1L..200L).map { operation(it).copy(deviceId = "shared-device-identity", deviceEpoch = "shared-device-epoch",
            entityId = "1|Normal|0|Direct", fields = mapOf(26 to AppSyncCanonicalValue.Text(repeated),
                32 to AppSyncCanonicalValue.Identifier(repeated), 33 to AppSyncCanonicalValue.Enum(repeated))) }
        val encoded = codec.encode(AppSyncCanonicalOperationBlock("a", ops))
        assertEquals(ops, codec.decode("a", encoded).operations)
        val payload = encoded.utf8()
        assertEquals(1, Regex(Regex.escape(repeated)).findAll(payload).count())
        assertTrue(encoded.size < ops.sumOf { AppSyncCanonicalFieldCodec.encode("reading.thread", "i", it.fields).size } / 4)
        assertTrue(AppSyncCanonicalStringTable.select(mapOf("x" to 2, "unique-long-value" to 1)).entries.isEmpty())
    }

    @Test
    fun tableReferenceWidthsAboveOneByteRemainDeterministic() {
        val operations = (1L..300L).map { sequence -> operation(sequence).copy(fields = mapOf(
            26 to AppSyncCanonicalValue.Text("value-${sequence % 150}-".repeat(8)))) }
        val input = AppSyncCanonicalOperationBlock("a", operations)
        val bytes = codec.encode(input)
        assertEquals(input, codec.decode("a", bytes))
        assertEquals(bytes, codec.encode(input.copy(operations = operations.reversed())))
    }

    @Test
    fun malformedFramingArityIdsCountsReferencesAndTruncationFailAtomically() {
        val valid = codec.encode(block(operation()))
        for (length in 0 until valid.size) assertFails { codec.decode("a", valid.substring(0, length)) }
        assertFails { codec.decode("a", (valid.toByteArray() + 0).toByteString()) }
        // Each mutation targets a structural byte of the fixed vector, not user text.
        for ((offset, value) in listOf(4 to 1, 5 to 127, 11 to 11, 17 to 0, 18 to 0, 19 to 127, 20 to 3, 22 to 0)) {
            val bytes = valid.toByteArray(); bytes[offset] = value.toByte()
            val error = assertFailsWith<IllegalArgumentException>("offset $offset") { codec.decode("a", bytes.toByteString()) }
            assertEquals("Invalid canonical operation block", error.message)
            assertNull(error.cause)
        }
        listOf("594f42330200020000", // table reference into empty table
            "594f423302020161016101016100", // duplicate table entries
            "594f4233020101ff01016100", // invalid UTF-8 table entry
            "594f423302800001016100", // nonminimal table count
            "594f4233020101750101610000" // unused table entry
        ).forEach { assertFails { codec.decode("a", it.decodeHex()) } }
    }

    @Test
    fun encodedExpandedAndCollectionLimitsAreIndependent() {
        val input = block(operation())
        val bytes = codec.encode(input)
        assertEquals(input, AppSyncCanonicalOperationBlockCodec(maximumBytes = bytes.size).decode("a", bytes))
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumBytes = bytes.size - 1).encode(input) }
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumBytes = bytes.size - 1).decode("a", bytes) }
        val expandedInput = AppSyncCanonicalOperationBlock("a", (1L..100).map { operation(it).copy(fields = mapOf(
            26 to AppSyncCanonicalValue.Text("title".repeat(80)))) })
        val compact = codec.encode(expandedInput)
        assertTrue(compact.size < 10_000)
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumExpandedBytes = 10_000).encode(expandedInput) }
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumExpandedBytes = 10_000).decode("a", compact) }
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumOperations = 1).encode(block(operation(), operation(2))) }
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumOperations = 1).decode("a", codec.encode(block(operation(), operation(2)))) }
        val manyStrings = AppSyncCanonicalOperationBlock("a", (1L..20).map { operation(it).copy(
            deviceId = "shared-device-identity", deviceEpoch = "shared-device-epoch") })
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumStrings = 1).encode(manyStrings) }
        assertFails { AppSyncCanonicalOperationBlockCodec(maximumStrings = 1).decode("a", codec.encode(manyStrings)) }
    }

    @Test
    fun policyBypassesAndDuplicateIdentitiesAreRejectedBeforeTransport() {
        val invalid = listOf(operation().copy(domainId = 999), operation().copy(sequence = 0),
            operation().copy(generation = 0), operation().copy(deviceId = "bad\nidentity"),
            operation().copy(entityId = "\uD800"), operation().copy(causalContext = mapOf("replica" to -1)),
            operation().copy(fields = emptyMap()), operation().copy(kind = SyncOperationKind.Delete),
            operation().copy(kind = SyncOperationKind.RelationRemove, origin = SyncOperationOrigin.Migration, fields = emptyMap()),
            operation().copy(fields = mapOf(68 to AppSyncCanonicalValue.Text("secret-cover"))),
            operation().copy(fields = mapOf(23 to AppSyncCanonicalValue.Integer(2))),
            operation().copy(fields = mapOf(26 to AppSyncCanonicalValue.Text("<html>private</html>"))))
        invalid.forEach { op -> assertFails { codec.encode(block(op)) } }
        assertFails { codec.encode(block(operation(), operation())) }
        assertFails { codec.encode(block(operation(), operation().copy(createdAtEpochMillis = 2))) }
    }

    @Test
    fun fieldReferencesCannotBypassTypePolicyOrExpandedFieldBudget() {
        val strings = AppSyncCanonicalStringTable(listOf("<html>private</html>", "x".repeat(513)))
        listOf("011a8100", "011a8101", "011b8400", "011a8102").forEach { hex -> assertFails {
            AppSyncCanonicalFieldCodec.decode("reading.thread", "i", hex.decodeHex().toByteArray(), strings)
        } }
    }

    @Test
    fun envelopeAndOperationBlockComposeWithoutChangingConflictEvidence() {
        val input = block(operation().copy(causalContext = mapOf("other-device:epoch" to 42)))
        val bytes = codec.encode(input)
        val envelope = AppSyncV3EnvelopeCodec()
        val encoded = envelope.encode(AppSyncV3PayloadKind.Journal, "a", "journal", bytes)
        val verified = assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(envelope.decode(encoded, "a", AppSyncV3PayloadKind.Journal, "journal"))
        assertEquals(input, codec.decode("a", verified.bytes))
        assertFails { codec.decode("other-account", verified.bytes) }
    }

    @Test
    fun sharedDeleteProofPreservesExistingGuardSemanticsWithoutRepeatingItsBody() {
        val proof = AppSyncCanonicalDeleteProof("authorization", 8, "reading-history:selected", 120, 20)
        val deletes = (1L..120).map { operation(it).copy(entityId = "$it|Normal|0|Direct", kind = SyncOperationKind.Delete,
            createdAtEpochMillis = 10, authorizationId = proof.authorizationId, fields = emptyMap()) }
        val source = AppSyncCanonicalOperationBlock("a", deletes, listOf(proof))
        val bytes = codec.encode(source)
        val decoded = codec.decode("a", bytes)
        assertEquals(source, decoded)
        assertEquals(1, Regex(Regex.escape(proof.scope)).findAll(bytes.utf8()).count())
        val legacy = decoded.operations.map { op ->
            val device = SyncDeviceId(op.deviceId)
            val epoch = SyncDeviceEpoch(op.deviceEpoch)
            val sequence = SyncSequence(op.sequence)
            SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence, SyncAccountBinding("a"),
                SyncDomainId("reading.thread"), SyncEntityId(op.entityId), kind = op.kind,
                fields = mapOf(AppSyncBulkDeleteProofFields.SCOPE to proof.scope,
                    AppSyncBulkDeleteProofFields.COUNT to proof.operationCount.toString(),
                    AppSyncBulkDeleteProofFields.EXPIRES_AT to proof.expiresAtEpochMillis.toString()),
                createdAtEpochMillis = op.createdAtEpochMillis, origin = op.origin, bulkDeleteAuthorizationId = op.authorizationId)
        }
        val guard = BulkDeleteGuard { null }
        assertTrue(guard.evaluate(legacy) { 200 }.quarantined.isEmpty())
        assertEquals(120, guard.evaluate(legacy.map { it.copy(fields = emptyMap()) }) { 200 }.quarantined.size)
        // A checkpoint may retain only some winners from a batch; the original count must survive.
        val subset = source.copy(operations = deletes.take(2))
        assertEquals(subset, codec.decode("a", codec.encode(subset)))
    }

    @Test
    fun sharedProofMustBeUniqueReferencedAndMatchDeletionDomainCountAndTime() {
        val op = operation().copy(kind = SyncOperationKind.Delete, fields = emptyMap(), createdAtEpochMillis = 10, authorizationId = "proof")
        val proof = AppSyncCanonicalDeleteProof("proof", 8, "scope", 1, 20)
        val valid = AppSyncCanonicalOperationBlock("a", listOf(op), listOf(proof))
        listOf(valid.copy(authorizations = emptyList()), valid.copy(authorizations = listOf(proof, proof)),
            valid.copy(authorizations = listOf(proof.copy(domainId = 9))),
            valid.copy(authorizations = listOf(proof.copy(operationCount = 0))),
            valid.copy(authorizations = listOf(proof.copy(expiresAtEpochMillis = 9))),
            valid.copy(operations = listOf(op, op.copy(sequence = 2))),
            valid.copy(operations = listOf(operation().copy(authorizationId = "proof"))),
            valid.copy(operations = emptyList()),
            valid.copy(authorizations = listOf(proof.copy(authorizationId = "other")))).forEach {
            assertFails { codec.encode(it) }
        }
    }
}
