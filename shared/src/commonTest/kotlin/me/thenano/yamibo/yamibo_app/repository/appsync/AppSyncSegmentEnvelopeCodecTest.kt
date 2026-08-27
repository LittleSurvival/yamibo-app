package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPayloadBudget
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentPayloadKind
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentReconstruction
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncTransportEnvelopeDispatcher
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce

class AppSyncSegmentEnvelopeCodecTest {
    @Test
    fun finalBodiesStayWithinConfiguredTargetAndRoundTripMultibyteContent() {
        val budget = AppSyncPayloadBudget(rolloutTargetChars = 4_096)
        val codec = AppSyncSegmentEnvelopeCodec(budget)
        val canonical = "正文🌸".repeat(12_000)
        val drafts = codec.split(canonical, "account", AppSyncSegmentPayloadKind.Journal, "replica", "generation")
        val ids = drafts.indices.map { "blog-${it + 1}" }
        val bodies = drafts.mapIndexed { index, draft ->
            codec.encodeSegment(codec.withNextBlogId(draft, ids.getOrNull(index + 1))).also {
                assertTrue(budget.measure(it).fitsTarget)
            }
        }
        val root = codec.root(drafts, ids.first(), canonical)
        val byId = ids.zip(bodies).toMap()

        val result = assertIs<AppSyncSegmentReconstruction.Valid>(
            codec.reconstruct(root, byId::get),
        )

        assertEquals(canonical, result.canonicalEnvelope)
        assertTrue(drafts.size > 1)
    }

    @Test
    fun missingReorderedAndCorruptChainsAreRejected() {
        val codec = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096))
        val canonical = "a".repeat(15_000)
        val drafts = codec.split(canonical, "account", AppSyncSegmentPayloadKind.Checkpoint, "cp", "generation")
        val ids = drafts.indices.map { "blog-${it + 1}" }
        val bodies = drafts.mapIndexed { index, draft ->
            codec.encodeSegment(codec.withNextBlogId(draft, ids.getOrNull(index + 1)))
        }
        val root = codec.root(drafts, ids.first(), canonical)

        assertIs<AppSyncSegmentReconstruction.Invalid>(
            codec.reconstruct(root, ids.dropLast(1).zip(bodies.dropLast(1)).toMap()::get),
        )
        val corrupt = ids.zip(bodies).toMap().toMutableMap().also {
            it[ids.first()] = it.getValue(ids.first()).replace("\"index\":0", "\"index\":1")
        }
        assertIs<AppSyncSegmentReconstruction.Invalid>(codec.reconstruct(root, corrupt::get))
    }

    @Test
    fun productionBoundaryUsesHeadroomBelowProviderHardLimit() {
        val budget = AppSyncPayloadBudget()
        assertEquals(42_000, budget.targetChars)
        assertEquals(50_000, AppSyncPayloadBudget.HARD_LIMIT_CHARS)
        assertTrue(budget.measure("x".repeat(42_000)).fitsTarget)
        assertTrue(!budget.measure("x".repeat(45_000)).fitsTarget)
        assertTrue(budget.measure("x".repeat(49_999)).fitsHardLimit)
    }

    @Test
    fun wrapperGrowthAtRolloutBoundariesIsDeterministicAndBounded() {
        listOf(42_000, 45_000, 49_500).forEach { target ->
            val budget = AppSyncPayloadBudget(target)
            val codec = AppSyncSegmentEnvelopeCodec(budget)
            val canonical = ("boundary-$target-正文🌸|").repeat(8_000)
            val first = codec.split(
                canonical, "account", AppSyncSegmentPayloadKind.Checkpoint, "checkpoint", "generation",
            )
            val second = codec.split(
                canonical, "account", AppSyncSegmentPayloadKind.Checkpoint, "checkpoint", "generation",
            )

            assertEquals(first, second)
            first.forEach { draft ->
                assertTrue(
                    budget.measure(codec.encodeSegment(codec.withNextBlogId(draft, "999999"))).fitsTarget,
                    "target=$target",
                )
            }
        }
    }

    @Test
    fun decodedEnvelopeBoundFailsBeforeAllocationOrPublication() {
        val codec = AppSyncSegmentEnvelopeCodec(
            budget = AppSyncPayloadBudget(4_096),
            maximumDecodedEnvelopeChars = 1_000,
        )

        val error = runCatching {
            codec.split("x".repeat(1_001), "account", AppSyncSegmentPayloadKind.Journal, "id", "gen")
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("decoded-size"))
    }

    @Test
    fun dispatcherReadsLegacyAndSegmentedJournalThroughTheSameCanonicalValidator() {
        val budget = AppSyncPayloadBudget(4_096)
        val segmentCodec = AppSyncSegmentEnvelopeCodec(budget)
        val journalCodec = AppSyncJournalEnvelopeCodec()
        val dispatcher = AppSyncTransportEnvelopeDispatcher(segmentCodec = segmentCodec)
        val payload = AppSyncJournalPayload(
            accountBinding = SyncAccountBinding("account"),
            deviceId = SyncDeviceId("device"),
            deviceEpoch = SyncDeviceEpoch("epoch"),
            writerNonce = SyncWriterNonce("nonce"),
            firstSequence = 0,
            lastSequence = 0,
            operations = emptyList(),
            observed = SyncCausalContext(),
            heartbeatAtEpochMillis = 1,
        )
        val canonical = journalCodec.encode(payload)
        val drafts = segmentCodec.split(
            canonical, "account", AppSyncSegmentPayloadKind.Journal, "device:epoch", "generation",
        )
        val ids = drafts.indices.map { "blog-${it + 1}" }
        val bodies = drafts.mapIndexed { index, draft ->
            segmentCodec.encodeSegment(segmentCodec.withNextBlogId(draft, ids.getOrNull(index + 1)))
        }
        val rootBody = segmentCodec.encodeRoot(segmentCodec.root(drafts, ids.first(), canonical))
        val byId = ids.zip(bodies).toMap()

        assertIs<AppSyncJournalValidation.Valid>(dispatcher.validateJournal(canonical))
        val segmented = assertIs<AppSyncJournalValidation.Valid>(
            dispatcher.validateJournal(rootBody, byId::get),
        )
        assertEquals(payload, segmented.envelope.payload)
    }
}
