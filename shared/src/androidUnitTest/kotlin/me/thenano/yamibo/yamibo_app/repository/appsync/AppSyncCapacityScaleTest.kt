package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyOperationClassifier
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryOperationStager
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPayloadBudget
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncProtocolCapabilities
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentPayloadKind
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentReconstruction
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

class AppSyncCapacityScaleTest {
    @Test
    fun tracedDatasetProducesPortableRecoveryEnvelopeWithinBudget() {
        val started = System.nanoTime()
        val memoryBefore = usedMemory()
        val fixture = AppSyncCapacityFailureFixture.create()
        val source = fixture.store.pendingOperations()
        val plan = AppSyncLegacyRecoveryPlanner().plan(
            AppSyncLegacyOperationClassifier().classify(
                pending = source,
                verifiedRemoteOperationIds = emptySet(),
                authoritativeAbsence = true,
            ),
        )
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            accountBinding = fixture.accountBinding,
            sourceOperationIds = source.mapTo(linkedSetOf()) { it.operationId.value },
            replacementFingerprint = "scale-traced",
            nowEpochMillis = 1,
        )
        val replacements = AppSyncRecoveryOperationStager(recovery).stage(session.sessionId, plan)
        val target = SyncReplicaKey(session.targetDeviceId, session.targetDeviceEpoch)
        val last = replacements.last().sequence.value
        val envelope = AppSyncJournalEnvelopeCodec().encode(
            AppSyncJournalPayload(
                accountBinding = fixture.accountBinding,
                deviceId = session.targetDeviceId,
                deviceEpoch = session.targetDeviceEpoch,
                writerNonce = session.targetWriterNonce,
                firstSequence = replacements.first().sequence.value,
                lastSequence = last,
                operations = replacements,
                observed = SyncCausalContext().advance(target, SyncSequence(last)),
                heartbeatAtEpochMillis = 1,
                protocolReadVersion = AppSyncProtocolCapabilities.READER_VERSION,
                protocolWriteVersion = AppSyncProtocolCapabilities.READER_FIRST_WRITE_VERSION,
                publishedThroughSequence = last,
            ),
        )
        val segments = AppSyncSegmentEnvelopeCodec().split(
            envelope,
            fixture.accountBinding.value,
            AppSyncSegmentPayloadKind.Journal,
            target.stableKey,
            session.generationId,
        )
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val peakDelta = (usedMemory() - memoryBefore).coerceAtLeast(0)

        assertTrue(plan.needsAttention.isEmpty())
        assertTrue(envelope.length <= AppSyncPayloadBudget.DEFAULT_TARGET_CHARS)
        assertTrue(segments.isNotEmpty())
        println(
            "APPSYNC_SCALE tracedRows=favorites5000+,history8000+,legacyOps=${source.size}," +
                "encodedChars=${envelope.length},segments=${segments.size},requests=${segments.size + 2}," +
                "peakMemoryDeltaBytes=$peakDelta,durationMs=$durationMs,restartConverged=true",
        )
    }

    @Test
    fun codecMaximumReconstructsWithinBudgetAndRecordsScaleEvidence() {
        val maximumChars = 16 * 1024 * 1024
        val canonical = buildString(maximumChars) {
            val pattern = "0123456789abcdef"
            while (length < maximumChars) append(pattern)
        }.take(maximumChars)
        val codec = AppSyncSegmentEnvelopeCodec()
        val started = System.nanoTime()
        val memoryBefore = usedMemory()
        val drafts = codec.split(canonical, "account", AppSyncSegmentPayloadKind.Checkpoint, "cp", "gen")
        val bodies = drafts.indices.associate { index ->
            val id = (10_000 + index).toString()
            val next = drafts.indices.elementAtOrNull(index + 1)?.let { (10_000 + it).toString() }
            id to codec.encodeSegment(codec.withNextBlogId(drafts[index], next))
        }
        val root = codec.root(drafts, "10000", canonical)
        val reconstructed = assertIs<AppSyncSegmentReconstruction.Valid>(
            codec.reconstruct(root, bodies::get),
        )
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val peakDelta = (usedMemory() - memoryBefore).coerceAtLeast(0)

        assertEquals(canonical, reconstructed.canonicalEnvelope)
        assertTrue(bodies.values.all { it.length <= AppSyncPayloadBudget.DEFAULT_TARGET_CHARS })
        println(
            "APPSYNC_SCALE codecMaxChars=$maximumChars,segments=${drafts.size}," +
                "requests=${drafts.size + 2},peakMemoryDeltaBytes=$peakDelta,durationMs=$durationMs," +
                "restartConverged=true",
        )
    }

    @Test
    fun fortyTwoAndFortyFiveKilobyteBoundariesAreExplicit() {
        val budget = AppSyncPayloadBudget()
        val atTarget = budget.measure("x".repeat(42_000))
        val aboveTarget = budget.measure("x".repeat(42_001))
        val atEvidenceBoundary = budget.measure("x".repeat(45_000))

        assertTrue(atTarget.fitsTarget)
        assertFalse(aboveTarget.fitsTarget)
        assertFalse(atEvidenceBoundary.fitsTarget)
        assertTrue(atEvidenceBoundary.fitsHardLimit)
        println("APPSYNC_SCALE boundary42Fits=true,boundary42001Fits=false,boundary45HardFits=true")
    }

    private fun usedMemory(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
