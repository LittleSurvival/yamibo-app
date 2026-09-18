package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncLegacyCloudMigrationTest {
    private val corpus = AppSyncSyntheticCorpus.create()
    private val account = AppSyncSyntheticCorpus.account
    private val installation = AppSyncInstallation("db", account, SyncDeviceId("local"), SyncDeviceEpoch("epoch"),
        SyncWriterNonce("local-writer"), 2, AppSyncInstallationState.Active, null, null, null, false,
        AppSyncScheduleSettings(), 0, 0)
    private val planner = AppSyncLegacyCloudMigration()
    private fun cloud(payloads: List<AppSyncCheckpointPayload> = listOf(corpus.checkpoint()),
        journals: List<AppSyncJournalPayload> = listOf(corpus.journal)): AppSyncJournalLoadResult.Success {
        val codec = AppSyncCheckpointEnvelopeCodec()
        val bodies = payloads.map(codec::encode)
        val parsed = bodies.map { assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(it)).envelope }
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = parsed.mapIndexed { i, p -> AppSyncIndexCheckpointReference(p.payload.checkpointId, 42 + i, p.fingerprint) },
            updatedAtEpochMillis = 1))
        return AppSyncJournalLoadResult.Success(journals.mapIndexed { i, journal -> LoadedAppSyncJournal("${100+i}", "fixture", journal) },
            checkpoints = parsed.mapIndexed { i, p -> LoadedAppSyncCheckpoint("${42+i}", p) }, authoritativeDiscovery = true,
            verifiedLegacyCheckpoints = bodies.mapIndexed { i, body -> assertNotNull(
                AppSyncVerifiedLegacyCheckpoint.verify(account.value, 42L+i, index, body)) })
    }
    private fun pending(): SyncOperation {
        val original = corpus.journal.operations.first { it.domainId.value == "detail-note" }
        return original.copy(deviceId = installation.deviceId, deviceEpoch = installation.deviceEpoch,
            sequence = SyncSequence(1), operationId = SyncOperation.idFor(installation.deviceId, installation.deviceEpoch, SyncSequence(1)),
            causalContext = corpus.journal.observed, kind = SyncOperationKind.Patch,
            fields = mapOf("content" to "new local note"), createdAtEpochMillis = AppSyncSyntheticCorpus.TIMESTAMP + 1000)
    }
    private fun prepare(cloud: AppSyncJournalLoadResult.Success, pending: List<SyncOperation> = emptyList()) =
        planner.prepare(account, installation, cloud, pending, "new-native", AppSyncSyntheticCorpus.TIMESTAMP + 2000)
    private fun failure(cloud: AppSyncJournalLoadResult.Success, pending: List<SyncOperation> = emptyList()) =
        assertIs<AppSyncLegacyCloudMigrationResult.NeedsAttention>(prepare(cloud, pending)).reason

    @Test fun cloudPlannerSuppliesJournalHistoryForSnapshotOnlySources() {
        val historical = AppSyncSyntheticCorpus.createWithoutFavoriteUpdates()
        val payload = historical.checkpoint().copy(resolvedEntities = emptyList())
        val pending = pending().copy(causalContext = historical.journal.observed)
        val ready = assertIs<AppSyncLegacyCloudMigrationResult.Ready>(prepare(cloud(listOf(payload), listOf(historical.journal)), listOf(pending)))
        val resolved = assertIs<AppSyncLegacyCloudMigrationResult.Ready>(prepare(cloud(listOf(historical.checkpoint()), listOf(historical.journal)), listOf(pending)))
        assertEquals(resolved.checkpoint, ready.checkpoint)
        assertEquals(AppSyncLegacyCloudFailure.Source, failure(cloud(listOf(payload), emptyList())))
    }

    @Test fun mergesAllPortableDomainsAndLaterPendingWithoutCreatingCanonicalVerification() {
        val loaded = cloud()
        val edit = pending()
        val ready = assertIs<AppSyncLegacyCloudMigrationResult.Ready>(prepare(loaded, listOf(edit)))
        assertEquals(19, ready.checkpoint.entities.map { it.domainId }.distinct().size)
        assertEquals("new-native", ready.checkpoint.checkpointId)
        assertEquals(1, ready.checkpoint.coverage[edit.replicaKey.stableKey])
        assertEquals(setOf(edit.operationId), ready.representedPendingIds)
        val note = ready.checkpoint.entities.single { it.domainId == AppSyncCanonicalSchema.domains.getValue("detail-note").id &&
            it.entityId == edit.entityId.value }
        assertTrue(note.values().values.any { it.legacyValue() == "new local note" })
        assertEquals(loaded.verifiedLegacyCheckpoints.single().fingerprint, ready.source.fingerprint)
        assertTrue(loaded.verifiedCanonicalCheckpoints.isEmpty())
        assertEquals(ready.checkpoint, assertIs<AppSyncLegacyCloudMigrationResult.Ready>(prepare(loaded, listOf(edit))).checkpoint)
    }

    @Test fun refusesIncompleteDiscoveryUnindexedSourcesAndMissingJournal() {
        val loaded = cloud()
        assertEquals(AppSyncLegacyCloudFailure.Discovery, failure(loaded.copy(authoritativeDiscovery = false)))
        assertEquals(AppSyncLegacyCloudFailure.Discovery, failure(loaded.copy(retirementDiscoveryIssues = listOf("incomplete"))))
        assertEquals(AppSyncLegacyCloudFailure.Source, failure(loaded.copy(verifiedLegacyCheckpoints = emptyList())))
        assertEquals(AppSyncLegacyCloudFailure.MissingJournal, failure(loaded.copy(indexedReplicaKeys = setOf("missing:epoch"))))
        assertEquals(AppSyncLegacyCloudFailure.Writer, failure(cloud(journals = listOf(corpus.journal,
            corpus.journal.copy(writerNonce = SyncWriterNonce("different"))))))
    }

    @Test fun pendingCannotConcealMissingPublishedCloudHistory() {
        val edit = pending()
        val journal = corpus.journal.copy(deviceId = installation.deviceId, deviceEpoch = installation.deviceEpoch,
            writerNonce = installation.writerNonce, firstSequence = 0, lastSequence = 0, operations = emptyList(),
            observed = SyncCausalContext().advance(edit.replicaKey, SyncSequence(1)), publishedThroughSequence = 1)
        assertEquals(AppSyncLegacyCloudFailure.CloudCoverage, failure(cloud(journals = listOf(journal)), listOf(edit)))
        val wrong = edit.copy(deviceId = SyncDeviceId("wrong"),
            operationId = SyncOperation.idFor(SyncDeviceId("wrong"), edit.deviceEpoch, edit.sequence))
        assertEquals(AppSyncLegacyCloudFailure.Pending, failure(cloud(), listOf(wrong)))
    }

    @Test fun multipleCheckpointCandidatesMustProduceTheSameState() {
        val first = corpus.checkpoint()
        val second = first.copy(checkpointId = "other", createdAtEpochMillis = first.createdAtEpochMillis + 1)
        val ready = assertIs<AppSyncLegacyCloudMigrationResult.Ready>(prepare(cloud(listOf(first, second))))
        assertEquals(42L, ready.source.blogId)
        val contradictory = AppSyncCheckpointEnvelopeCodec().createPayload("contradictory", account,
            first.coverage, me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile(appVersionCode = 1, createdAt = 1),
            emptyList(), emptyList(), first.createdAtEpochMillis)
        assertEquals(AppSyncLegacyCloudFailure.Source, failure(cloud(listOf(first, contradictory))))
        val original = corpus.journal.operations.first { it.domainId.value == "detail-note" }
        val changed = original.copy(fields = original.fields + ("content" to "conflict"))
        assertEquals(AppSyncLegacyCloudFailure.CloudMerge,
            failure(cloud(journals = listOf(corpus.journal.copy(operations = corpus.journal.operations.map {
                if (it.operationId == changed.operationId) changed else it
            })))))
    }
}
