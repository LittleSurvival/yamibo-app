package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import okio.ByteString.Companion.encodeUtf8

class AppSyncV3SegmentCodecTest {
    private val codec = AppSyncV3SegmentCodec(AppSyncPayloadBudget(4096))
    private val documents = AppSyncV3DocumentCodec()
    private fun coverage() = (1..2000).associate { (it.toString().encodeUtf8().sha256().hex() + ":epoch") to 1L }
    private fun checkpoint(account: String = "account") = documents.encodeCheckpoint(
        AppSyncCanonicalCheckpoint("checkpoint", account, 1, coverage(), emptyList()))
    private fun journal() = documents.encodeJournal("device:epoch", AppSyncCanonicalJournal(
        AppSyncCanonicalOperationBlock("account", emptyList()), "device", "epoch", "writer", 0, 0,
        coverage(), emptyList(), 1, 3, 3, "test", 0))
    private fun chain(plan: AppSyncV3SegmentPlan): Pair<AppSyncV3SegmentRoot, Map<Int, String>> {
        val bodies = mutableMapOf<Int, String>()
        var next: AppSyncV3SegmentReference? = null
        for (draft in plan.drafts.reversed()) {
            val body = codec.encodeSegment(draft, next)
            val id = 100 + draft.index
            bodies[id] = body
            next = codec.reference(id, body)
        }
        return codec.root(plan, requireNotNull(next)) to bodies
    }

    @Test fun bothNativeDocumentsRoundTripWithoutAnotherCompressionOrBase64Layer() = runBlocking {
        for ((kind, envelope) in listOf(AppSyncV3PayloadKind.Checkpoint to checkpoint(), AppSyncV3PayloadKind.Journal to journal())) {
            val plan = codec.plan(envelope, "account", kind)
            assertTrue(plan.drafts.size > 1)
            assertEquals(plan, codec.plan(envelope, "account", kind))
            assertEquals(envelope, plan.drafts.joinToString("") { it.chunk })
            val (root, bodies) = chain(plan)
            assertTrue(bodies.values.all { it.length <= 4096 && it.encodeUtf8().size <= 4096 })
            assertEquals(root, codec.decodeRoot(codec.encodeRoot(root)).getOrThrow())
            val result = assertIs<AppSyncV3SegmentRead.Verified>(codec.reconstruct(root, "account", kind) { bodies[it] })
            assertEquals(envelope, result.envelope)
            assertEquals(documents.discover(envelope, "account", kind), result.document)
        }
    }

    @Test fun finalTransportBoundaryIncludesWrapperAndNextReference() {
        val next = AppSyncV3SegmentReference(Int.MAX_VALUE, "f".repeat(64))
        val draft = AppSyncV3Segment("account", AppSyncV3PayloadKind.Journal, "device:epoch", "a".repeat(64), 0, 2, "A", next)
        val extra = 4096 - codec.encodeSegment(draft).length
        val exact = draft.copy(chunk = "A".repeat(extra + 1))
        assertEquals(4096, codec.encodeSegment(exact).length)
        assertEquals(exact, codec.decodeSegment(codec.encodeSegment(exact)).getOrThrow())
        assertFailsWith<IllegalArgumentException> { codec.encodeSegment(exact.copy(chunk = exact.chunk + "A")) }
        assertFailsWith<IllegalArgumentException> { codec.encodeSegment(draft, null) }
        assertTrue(codec.decodeSegment("prefix" + codec.encodeSegment(draft)).isFailure)
    }

    @Test fun missingCorruptSwappedAndForeignSegmentsCannotReconstruct(): Unit = runBlocking {
        val (root, bodies) = chain(codec.plan(checkpoint(), "account", AppSyncV3PayloadKind.Checkpoint))
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "other", AppSyncV3PayloadKind.Checkpoint) { error("Must reject binding before fetch") })
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "account", AppSyncV3PayloadKind.Journal) { error("Must reject kind before fetch") })
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "account", AppSyncV3PayloadKind.Checkpoint) { if (it == 101) null else bodies[it] })
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "account", AppSyncV3PayloadKind.Checkpoint) { bodies[it]?.replace("payload=", "payload= ") })
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "account", AppSyncV3PayloadKind.Checkpoint) { bodies[101] })
        val changed = root.copy(metadata = root.metadata.copy(canonicalFingerprint = "f".repeat(64)))
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(changed, "account", AppSyncV3PayloadKind.Checkpoint) { bodies[it] })
    }

    @Test fun cumulativeBoundsAndChainCyclesStopBeforeUnboundedFetches(): Unit = runBlocking {
        val plan = codec.plan(checkpoint(), "account", AppSyncV3PayloadKind.Checkpoint)
        val first = codec.encodeSegment(plan.drafts.first(), AppSyncV3SegmentReference(100, "a".repeat(64)))
        val root = codec.root(plan, codec.reference(100, first))
        var reads = 0
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(root, "account", AppSyncV3PayloadKind.Checkpoint) { reads++; first })
        assertEquals(1, reads)
        val (valid, bodies) = chain(plan)
        reads = 0
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(valid.copy(envelopeChars = 1), "account", AppSyncV3PayloadKind.Checkpoint) { reads++; bodies[it] })
        assertEquals(1, reads)
        assertIs<AppSyncV3SegmentRead.Invalid>(codec.reconstruct(valid.copy(count = 4097), "account", AppSyncV3PayloadKind.Checkpoint) { error("No fetch for invalid count") })
    }

    @Test fun unsupportedInvalidOversizedAndExcessSegmentSourcesFailBeforePlanning() {
        val source = checkpoint()
        assertFails { codec.plan(source.replace("codec=1", "codec=9"), "account", AppSyncV3PayloadKind.Checkpoint) }
        assertFails { codec.plan(source, "other", AppSyncV3PayloadKind.Checkpoint) }
        assertFails { AppSyncV3SegmentCodec(maximumEnvelopeChars = 10).plan(source, "account", AppSyncV3PayloadKind.Checkpoint) }
        assertFails { AppSyncV3SegmentCodec(AppSyncPayloadBudget(4096), maximumSegments = 2).plan(source, "account", AppSyncV3PayloadKind.Checkpoint) }
        val invalid = AppSyncV3EnvelopeCodec().encode(AppSyncV3PayloadKind.Checkpoint, "account", "checkpoint", "not canonical".encodeUtf8())
        assertFails { codec.plan(invalid, "account", AppSyncV3PayloadKind.Checkpoint) }
    }

    @Test fun unicodeBindingsSurviveAndRemainWithinUtf8TransportBudget() = runBlocking {
        val account = "帳號😀".repeat(90)
        val source = checkpoint(account)
        val plan = codec.plan(source, account, AppSyncV3PayloadKind.Checkpoint)
        val (root, bodies) = chain(plan)
        assertTrue(bodies.values.all { it.encodeUtf8().size <= 4096 })
        assertEquals(source, assertIs<AppSyncV3SegmentRead.Verified>(codec.reconstruct(root, account,
            AppSyncV3PayloadKind.Checkpoint) { bodies[it] }).envelope)
    }

    @Test fun escapedRootMetadataMustFitBeforeReturningAnyPlan() {
        val account = "\"".repeat(1000)
        val source = documents.encodeCheckpoint(AppSyncCanonicalCheckpoint("\"".repeat(1000), account, 1, emptyMap(), emptyList()))
        assertFailsWith<IllegalArgumentException> { codec.plan(source, account, AppSyncV3PayloadKind.Checkpoint) }
    }
}
