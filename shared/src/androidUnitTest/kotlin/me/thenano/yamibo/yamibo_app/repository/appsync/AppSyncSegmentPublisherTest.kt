package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.BlogComment
import io.github.littlesurvival.dto.page.BlogInfo
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogClassSelection
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPayloadBudget
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.APP_SYNC_INDEX_TITLE
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentIndexCommitResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentIndexCommitter
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentPayloadKind
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentPublisher
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentedJournalCommitCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentedJournalCommitResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentedCheckpointCommitCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncSegmentedCheckpointCommitResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog

class AppSyncSegmentPublisherTest {
    @Test
    fun publishesTailToHeadUsingDirectIdVerificationAndResumesVerifiedProgress() = runBlocking {
        val fixture = fixture()
        val codec = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096))
        val provider = FakeProvider()
        val envelope = "可攜資料".repeat(5_000)
        val expectedCount = codec.split(
            envelope, fixture.account.value, AppSyncSegmentPayloadKind.Journal,
            "identity", fixture.session.generationId,
        ).size

        provider.failSubmissionNumber = 2
        val first = publisher(provider, fixture.recovery, codec).publish(
            fixture.session.sessionId, envelope, AppSyncSegmentPayloadKind.Journal,
            "identity", CLASS_SELECTION, FORM_HASH,
        )
        assertIs<AppSyncSegmentPublishResult.Retryable>(first)
        assertEquals(1, fixture.recovery.segmentWrites(fixture.session.sessionId).count { it.blogId != null })
        assertTrue(provider.submittedTitles.none { it.contains(" Root ") })

        provider.failSubmissionNumber = null
        val restartedRecovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val resumed = publisher(provider, restartedRecovery, codec).publish(
            fixture.session.sessionId, envelope, AppSyncSegmentPayloadKind.Journal,
            "identity", CLASS_SELECTION, FORM_HASH,
        )
        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(resumed)
        assertEquals(expectedCount, fixture.recovery.segmentWrites(fixture.session.sessionId).size)
        assertEquals(expectedCount + 1, provider.blogs.size)
        assertEquals(0, provider.fetchListCalls)
        assertEquals(1, provider.submittedTitles.count { it.contains(" Root ") })
        assertTrue(fixture.recovery.session(fixture.session.sessionId)?.indexCommitted == false)
    }

    @Test
    fun ambiguousSubmissionUsesHashReconciliationWithoutCreatingDuplicate() = runBlocking {
        val fixture = fixture()
        val codec = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096))
        val provider = FakeProvider().apply { ambiguousSubmissionNumber = 1 }
        var reconciliations = 0
        val publisher = AppSyncSegmentPublisher(
            provider = provider,
            recoveryStore = fixture.recovery,
            codec = codec,
            nowMillis = { 30L },
            reconcileSegment = { _, _, _ ->
                reconciliations += 1
                provider.blogs.keys.singleOrNull()
            },
        )

        val result = publisher.publish(
            fixture.session.sessionId, "x".repeat(10_000), AppSyncSegmentPayloadKind.Journal,
            "identity", CLASS_SELECTION, FORM_HASH,
        )

        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(result)
        assertEquals(1, reconciliations)
        assertEquals(provider.blogs.size, provider.submittedTitles.size)
    }

    @Test
    fun duplicatePostCandidatesAreResolvedByAuthoritativePayloadValidation() = runBlocking {
        val fixture = fixture()
        val codec = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096))
        val provider = FakeProvider().apply { duplicateSubmissionNumber = 1 }
        var reconciliationCalls = 0
        val publisher = AppSyncSegmentPublisher(
            provider, fixture.recovery, codec, nowMillis = { 30L },
            reconcileSegment = { _, _, _ ->
                reconciliationCalls += 1
                provider.blogs.keys.singleOrNull()
            },
        )

        val result = publisher.publish(
            fixture.session.sessionId, "portable".repeat(2_000),
            AppSyncSegmentPayloadKind.Journal, "identity", CLASS_SELECTION, FORM_HASH,
        )

        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(result)
        assertEquals(1, reconciliationCalls)
    }

    @Test
    fun missingKnownIndexConflictsAndLostUpdateRemainsUncommitted() = runBlocking {
        val missingFixture = fixture()
        val missingProvider = FakeProvider()
        publishReady(missingFixture, missingProvider)
        missingFixture.remoteStore.save(
            StoredAppSyncRemoteBlog(
                "index", AppSyncRemoteBlogKind.Index, BlogId(900), BlogClassId(7),
                "old-index", 1L, 1L,
            ),
        )
        val missingCommitter = AppSyncSegmentIndexCommitter(
            missingProvider, missingFixture.remoteStore, missingFixture.recovery,
            nowMillis = { 40L },
        )
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(
            missingCommitter.commitJournalRoot(
                missingFixture.session.sessionId, CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertTrue(missingFixture.recovery.session(missingFixture.session.sessionId)?.indexCommitted == false)

        val lostFixture = fixture()
        val lostProvider = FakeProvider()
        publishReady(lostFixture, lostProvider)
        lostProvider.lostIndexPayload = AppSyncIndexEnvelopeCodec().encode(
            AppSyncIndexPayload(lostFixture.account, updatedAtEpochMillis = 41L),
        )
        val lostCommitter = AppSyncSegmentIndexCommitter(
            lostProvider, lostFixture.remoteStore, lostFixture.recovery, nowMillis = { 40L },
        )
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(
            lostCommitter.commitJournalRoot(
                lostFixture.session.sessionId, CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertTrue(lostFixture.recovery.session(lostFixture.session.sessionId)?.indexCommitted == false)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.PendingLocal,
            lostFixture.operations.allOutboxOperations().single().second,
        )
    }

    @Test
    fun verifiedIndexReferenceIsTheCommitPointAndAmbiguousCreateDoesNotCommit() = runBlocking {
        val fixture = fixture()
        val provider = FakeProvider()
        fixture.operations.updateVerifiedHeartbeat(5L, 77L)
        val installation = requireNotNull(fixture.operations.installation())
        val previousPayload = AppSyncJournalPayload(
            accountBinding = fixture.account,
            deviceId = fixture.session.sourceDeviceId,
            deviceEpoch = fixture.session.sourceDeviceEpoch,
            writerNonce = installation.writerNonce,
            firstSequence = fixture.source.sequence.value,
            lastSequence = fixture.source.sequence.value,
            operations = listOf(fixture.source),
            observed = SyncCausalContext(),
            heartbeatAtEpochMillis = 5L,
        )
        provider.storeBlog(
            BlogId(77),
            AppSyncJournalDefaults.journalTitle(
                fixture.session.sourceDeviceId,
                fixture.session.sourceDeviceEpoch,
            ),
            AppSyncJournalEnvelopeCodec().encode(previousPayload),
        )
        fixture.remoteStore.save(
            StoredAppSyncRemoteBlog(
                "previous-replica", AppSyncRemoteBlogKind.Journal, BlogId(77), BlogClassId(7),
                "previous-fingerprint", 5L, 5L,
            ),
        )
        val ready = publisher(
            provider,
            fixture.recovery,
            AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096)),
        ).publish(
            fixture.session.sessionId, "portable".repeat(2_000),
            AppSyncSegmentPayloadKind.Journal, "identity", CLASS_SELECTION, FORM_HASH,
        )
        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(ready)
        assertTrue(fixture.recovery.session(fixture.session.sessionId)?.indexCommitted == false)
        assertEquals(77L, fixture.operations.installation()?.journalBlogId)
        assertEquals(77, fixture.remoteStore.load("previous-replica")?.blogId?.value)
        val oldPage = assertIs<AppSyncCloudResult.VerifiedSuccess<BlogPage>>(
            provider.fetchBlog(BlogId(77)),
        ).value
        assertIs<AppSyncJournalValidation.Valid>(
            AppSyncJournalEnvelopeCodec().validateReaderHtml(oldPage.rootBlog.contentHtml),
        )

        val committer = AppSyncSegmentIndexCommitter(
            provider, fixture.remoteStore, fixture.recovery, nowMillis = { 40L },
        )
        provider.failSubmissionNumber = provider.submittedTitles.size + 1
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(
            committer.commitJournalRoot(
                fixture.session.sessionId, CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertTrue(fixture.recovery.session(fixture.session.sessionId)?.indexCommitted == false)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.PendingLocal,
            fixture.operations.allOutboxOperations().single().second,
        )

        provider.failSubmissionNumber = null
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(
            committer.commitJournalRoot(
                fixture.session.sessionId, CLASS_SELECTION, FORM_HASH,
            ),
        )
        val committed = requireNotNull(fixture.recovery.session(fixture.session.sessionId))
        assertTrue(committed.indexCommitted)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.ActivatingLocal,
            committed.phase,
        )
        assertEquals(77L, fixture.operations.installation()?.journalBlogId)
        assertEquals(77, fixture.remoteStore.load("previous-replica")?.blogId?.value)
    }

    @Test
    fun coordinatorAcknowledgesReplacementOnlyAfterVerifiedIndexAndActivation() = runBlocking {
        val fixture = fixture()
        val provider = FakeProvider()
        val codec = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096))
        val envelope = "portable".repeat(2_000)
        publishReady(fixture, provider)
        val coordinator = AppSyncSegmentedJournalCommitCoordinator(
            publisher(provider, fixture.recovery, codec),
            AppSyncSegmentIndexCommitter(
                provider, fixture.remoteStore, fixture.recovery, nowMillis = { 40L },
            ),
            fixture.recovery,
            nowMillis = { 50L },
        )

        provider.failSubmissionNumber = provider.submittedTitles.size + 1
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(
            coordinator.commit(
                fixture.session.sessionId, envelope, "identity", CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.PendingLocal,
            fixture.operations.allOutboxOperations().single().second,
        )

        provider.failSubmissionNumber = null
        val verified = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(
            coordinator.commit(
                fixture.session.sessionId, envelope, "identity", CLASS_SELECTION, FORM_HASH,
            ),
        )
        val lifecycles = fixture.operations.allOutboxOperations()
            .associate { it.first.operationId.value to it.second }
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.SupersededByRecovery,
            lifecycles[fixture.source.operationId.value],
        )
        verified.acknowledgedOperationIds.forEach { operationId ->
            assertEquals(
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.Acknowledged,
                lifecycles[operationId],
            )
        }
    }

    @Test
    fun productionRemoteCommitsSegmentedJournalAndAcknowledgesOriginalPendingRow() = runBlocking {
        val fixture = fixture()
        fixture.recovery.rollbackPreCommit(fixture.session.sessionId)
        val provider = FakeProvider()
        fixture.remoteStore.saveClassId(fixture.account, BlogClassId(7))
        val installation = requireNotNull(fixture.operations.installation())
        val payload = AppSyncJournalPayload(
            accountBinding = fixture.account,
            deviceId = installation.deviceId,
            deviceEpoch = installation.deviceEpoch,
            writerNonce = installation.writerNonce,
            firstSequence = fixture.source.sequence.value,
            lastSequence = fixture.source.sequence.value,
            operations = listOf(fixture.source),
            observed = SyncCausalContext(),
            heartbeatAtEpochMillis = 100,
            protocolReadVersion = 2,
            protocolWriteVersion = 1,
        )
        val remote = YamiboAppSyncJournalRemote(
            provider = provider,
            store = fixture.remoteStore,
            nowMillis = { 100L },
            recoveryStore = fixture.recovery,
        )

        val result = remote.publishOwnJournalSegmented(
            payload,
            setOf(fixture.source.operationId),
            activeJournals = emptyList(),
            formHash = FORM_HASH,
        )

        assertIs<me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult.Verified>(result)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.Acknowledged,
            fixture.operations.allOutboxOperations().single().second,
        )
        assertEquals(installation.deviceId, fixture.operations.installation()?.deviceId)
        assertTrue(fixture.operations.installation()?.journalBlogId != null)
        assertEquals(0, provider.fetchListCalls)
    }

    @Test
    fun checkpointChainBecomesVisibleOnlyAfterVerifiedIndexAndLocalActivation() = runBlocking {
        val fixture = fixture()
        fixture.recovery.rollbackPreCommit(fixture.session.sessionId)
        val provider = FakeProvider()
        val checkpointId = "cp-portable"
        val checkpointCodec = me.thenano.yamibo.yamibo_app.repository.appsync.remote
            .AppSyncCheckpointEnvelopeCodec()
        val checkpointPayload = checkpointCodec.createPayload(
            checkpointId = checkpointId,
            accountBinding = fixture.account,
            coverage = SyncCausalContext(),
            snapshot = me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile(
                appVersionCode = 1,
                createdAt = 20L,
            ),
            tombstones = emptyList(),
            createdAtEpochMillis = 20L,
        )
        val envelope = checkpointCodec.encode(checkpointPayload)
        val session = fixture.recovery.createOrResumeSegmentedCheckpoint(
            fixture.account,
            checkpointId,
            stableAppSyncFingerprint(envelope),
            20L,
        )
        fixture.recovery.startSegmentedJournal(session.sessionId, 21L)
        val coordinator = AppSyncSegmentedCheckpointCommitCoordinator(
            publisher(
                provider,
                fixture.recovery,
                AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096)),
            ),
            AppSyncSegmentIndexCommitter(
                provider, fixture.remoteStore, fixture.recovery, nowMillis = { 40L },
            ),
            fixture.recovery,
            nowMillis = { 50L },
        )

        provider.failSubmissionNumber = 2
        assertIs<AppSyncSegmentedCheckpointCommitResult.Retryable>(
            coordinator.commit(
                session.sessionId, checkpointId, envelope, CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertTrue(fixture.remoteStore.load("index") == null)
        assertTrue(fixture.recovery.session(session.sessionId)?.indexCommitted == false)

        provider.failSubmissionNumber = null
        val verified = assertIs<AppSyncSegmentedCheckpointCommitResult.Verified>(
            coordinator.commit(
                session.sessionId, checkpointId, envelope, CLASS_SELECTION, FORM_HASH,
            ),
        )
        assertTrue(verified.rootBlogId > 0)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Completed,
            fixture.recovery.session(session.sessionId)?.phase,
        )
        val indexBlog = requireNotNull(fixture.remoteStore.load("index"))
        val indexPage = assertIs<AppSyncCloudResult.VerifiedSuccess<BlogPage>>(
            provider.fetchBlog(indexBlog.blogId),
        ).value
        val index = assertIs<me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexValidation.Valid>(
            AppSyncIndexEnvelopeCodec().validateReaderHtml(indexPage.rootBlog.contentHtml),
        ).envelope.payload
        assertEquals(verified.rootBlogId.toInt(), index.checkpoints.single().blogId)
        assertEquals(checkpointId, index.checkpoints.single().checkpointId)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.PendingLocal,
            fixture.operations.allOutboxOperations().single().second,
        )
        assertEquals(
            AppSyncRemoteBlogKind.CheckpointRoot,
            fixture.remoteStore.load("checkpoint-root:${session.generationId}")?.kind,
        )
        assertTrue(fixture.remoteStore.loadKind(AppSyncRemoteBlogKind.Segment).isEmpty())

        val restartedRemote = YamiboAppSyncJournalRemote(
            provider = provider,
            store = fixture.remoteStore,
            nowMillis = { 60L },
            recoveryStore = SqlDelightAppSyncRecoveryStore(fixture.database),
        )
        val loaded = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult.Success
            >(restartedRemote.loadJournals(fixture.account, forceDiscovery = false))
        assertEquals(checkpointId, loaded.checkpoints.single().envelope.payload.checkpointId)
        assertEquals(
            AppSyncRemoteBlogKind.CheckpointRoot,
            fixture.remoteStore.load("checkpoint:$checkpointId")?.kind,
        )
        assertEquals(envelope, me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec()
            .encode(loaded.checkpoints.single().envelope.payload))
    }

    @Test
    fun largeCheckpointUsesBudgetedSegmentsAndLoadsAcrossRemoteRestart() = runBlocking {
        val fixture = fixture()
        fixture.recovery.rollbackPreCommit(fixture.session.sessionId)
        fixture.remoteStore.saveClassId(fixture.account, BlogClassId(7))
        val provider = FakeProvider()
        val installation = requireNotNull(fixture.operations.installation())
        val journalPayload = AppSyncJournalPayload(
            accountBinding = fixture.account,
            deviceId = installation.deviceId,
            deviceEpoch = installation.deviceEpoch,
            writerNonce = installation.writerNonce,
            firstSequence = fixture.source.sequence.value,
            lastSequence = fixture.source.sequence.value,
            operations = listOf(fixture.source),
            observed = SyncCausalContext(),
            heartbeatAtEpochMillis = 100L,
            protocolReadVersion = 2,
            protocolWriteVersion = 1,
        )
        val journalBody = AppSyncJournalEnvelopeCodec().encode(journalPayload)
        val journalFingerprint = assertIs<AppSyncJournalValidation.Valid>(
            AppSyncJournalEnvelopeCodec().validate(journalBody),
        ).envelope.fingerprint
        provider.storeBlog(
            BlogId(77),
            AppSyncJournalDefaults.journalTitle(installation.deviceId, installation.deviceEpoch),
            journalBody,
        )
        fixture.remoteStore.save(
            StoredAppSyncRemoteBlog(
                me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey(
                    installation.deviceId,
                    installation.deviceEpoch,
                ).stableKey,
                AppSyncRemoteBlogKind.Journal,
                BlogId(77),
                BlogClassId(7),
                journalFingerprint,
                100L,
                100L,
            ),
        )
        val indexBody = AppSyncIndexEnvelopeCodec().encode(
            AppSyncIndexPayload(
                accountBinding = fixture.account,
                journals = listOf(
                    me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexJournalReference(
                        replicaKey = me.thenano.yamibo.yamibo_app.repository.appsync.operation
                            .SyncReplicaKey(installation.deviceId, installation.deviceEpoch).stableKey,
                        blogId = 77,
                        fingerprint = journalFingerprint,
                    ),
                ),
                updatedAtEpochMillis = 100L,
            ),
        )
        val indexFingerprint = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexValidation.Valid
            >(AppSyncIndexEnvelopeCodec().validate(indexBody)).envelope.fingerprint
        provider.storeBlog(BlogId(78), APP_SYNC_INDEX_TITLE, indexBody)
        fixture.remoteStore.save(
            StoredAppSyncRemoteBlog(
                "index", AppSyncRemoteBlogKind.Index, BlogId(78), BlogClassId(7),
                indexFingerprint, 100L, 100L,
            ),
        )
        val remote = YamiboAppSyncJournalRemote(
            provider,
            fixture.remoteStore,
            nowMillis = { 200L },
            recoveryStore = fixture.recovery,
        )
        assertIs<me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult.Success>(
            remote.loadJournals(fixture.account, forceDiscovery = false),
        )
        val checkpointCodec = me.thenano.yamibo.yamibo_app.repository.appsync.remote
            .AppSyncCheckpointEnvelopeCodec()
        val snapshot = me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile(
            appVersionCode = 1,
            createdAt = 200L,
            settings = (0 until 4_000).map { index ->
                me.thenano.yamibo.yamibo_app.repository.backup.BackupSetting(
                    key = "portable-$index-${stableAppSyncFingerprint("key-$index")}",
                    type = me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.String,
                    value = stableAppSyncFingerprint("value-$index-a") +
                        stableAppSyncFingerprint("value-$index-b"),
                )
            },
        )
        val payload = checkpointCodec.createPayload(
            "cp-large",
            fixture.account,
            SyncCausalContext(),
            snapshot,
            tombstones = emptyList(),
            createdAtEpochMillis = 200L,
        )
        assertTrue(checkpointCodec.encode(payload).length > AppSyncPayloadBudget.DEFAULT_TARGET_CHARS)

        val published = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult.Verified
            >(remote.publishCheckpoint(payload, FORM_HASH))
        assertEquals("cp-large", published.checkpoint.envelope.payload.checkpointId)
        assertTrue(provider.submittedTitles.any { it.contains(" Segment ") })
        assertTrue(provider.submittedTitles.any { it.contains(" Root ") })
        assertTrue(provider.blogs.values
            .filter { it.blogInfo.title.contains(" Segment ") }
            .all { it.rootBlog.contentHtml.length <= AppSyncPayloadBudget.DEFAULT_TARGET_CHARS })

        val restarted = YamiboAppSyncJournalRemote(
            provider,
            fixture.remoteStore,
            nowMillis = { 300L },
            recoveryStore = SqlDelightAppSyncRecoveryStore(fixture.database),
        )
        val loaded = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult.Success
            >(restarted.loadJournals(fixture.account, forceDiscovery = false))
        assertEquals("cp-large", loaded.checkpoints.single().envelope.payload.checkpointId)
    }

    private fun publisher(
        provider: FakeProvider,
        recovery: SqlDelightAppSyncRecoveryStore,
        codec: AppSyncSegmentEnvelopeCodec,
    ) = AppSyncSegmentPublisher(provider, recovery, codec, nowMillis = { 30L })

    private suspend fun publishReady(fixture: Fixture, provider: FakeProvider) {
        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(
            publisher(
                provider,
                fixture.recovery,
                AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4_096)),
            ).publish(
                fixture.session.sessionId, "portable".repeat(2_000),
                AppSyncSegmentPayloadKind.Journal, "identity", CLASS_SELECTION, FORM_HASH,
            ),
        )
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val operations = SqlDelightAppSyncOperationStore(database)
        operations.initialize("generation")
        val account = SyncAccountBinding("account")
        operations.bindAccount(account, AppSyncInstallationState.Active)
        val source = operations.appendLocalOperation(
            account, SyncDomainId("settings"), SyncEntityId("legacy-cache"), 1,
            SyncOperationKind.Patch, mapOf("value" to "legacy"), SyncCausalContext(), 1,
            SyncOperationOrigin.Migration,
        )
        val recovery = SqlDelightAppSyncRecoveryStore(database)
        val session = recovery.createOrResume(account, setOf(source.operationId.value), "replacement", 10)
        val sequence = SyncSequence(session.targetFirstSequence)
        recovery.stageOperations(session.sessionId, listOf(
            SyncOperation(
                SyncOperation.idFor(session.targetDeviceId, session.targetDeviceEpoch, sequence),
                session.targetDeviceId, session.targetDeviceEpoch, sequence, account,
                SyncDomainId("settings"), SyncEntityId("portable"), 1,
                SyncOperationKind.Patch, mapOf("value" to "portable"), SyncCausalContext(),
                10, SyncOperationOrigin.Migration,
            ),
        ))
        return Fixture(
            account, recovery, session, SqlDelightAppSyncRemoteBlogStore(database), operations,
            database, source,
        )
    }

    private data class Fixture(
        val account: SyncAccountBinding,
        val recovery: SqlDelightAppSyncRecoveryStore,
        val session: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession,
        val remoteStore: SqlDelightAppSyncRemoteBlogStore,
        val operations: SqlDelightAppSyncOperationStore,
        val database: Database,
        val source: SyncOperation,
    )

    private class FakeProvider : AppSyncBlogProvider {
        val blogs = linkedMapOf<BlogId, BlogPage>()
        val submittedTitles = mutableListOf<String>()
        var failSubmissionNumber: Int? = null
        var ambiguousSubmissionNumber: Int? = null
        var duplicateSubmissionNumber: Int? = null
        var lostIndexPayload: String? = null
        var fetchListCalls = 0
        private var nextId = 100

        override suspend fun fetchMyBlogs(blogClassId: BlogClassId?, page: Int): AppSyncCloudResult<UserSpaceBlogPage> {
            fetchListCalls += 1
            return AppSyncCloudResult.NotFound
        }

        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> =
            blogs[blogId]?.let { AppSyncCloudResult.VerifiedSuccess(it) } ?: AppSyncCloudResult.NotFound

        override suspend fun submitBlog(request: AppSyncBlogWriteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            val submission = submittedTitles.size + 1
            submittedTitles += request.title
            if (submission == failSubmissionNumber) return AppSyncCloudResult.Timeout("simulated timeout")
            val id = request.blogId ?: BlogId(nextId++)
            blogs[id] = page(id, request.title, request.message)
            if (request.title == APP_SYNC_INDEX_TITLE) {
                lostIndexPayload?.let { blogs[id] = page(id, request.title, it) }
            }
            if (submission == ambiguousSubmissionNumber) {
                return AppSyncCloudResult.AcknowledgedButUnverified(null, "ambiguous", id)
            }
            val candidates = if (submission == duplicateSubmissionNumber) {
                listOf(id, BlogId(999))
            } else {
                listOf(id)
            }
            return AppSyncCloudResult.VerifiedSuccess(AppSyncPostAcknowledgement("ok", candidates))
        }

        override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest) =
            AppSyncCloudResult.VerifiedSuccess(AppSyncPostAcknowledgement("ok", listOf(request.blogId)))

        fun storeBlog(id: BlogId, title: String, body: String) {
            blogs[id] = page(id, title, body)
        }

        private fun page(id: BlogId, title: String, body: String) = BlogPage(
            blogInfo = BlogInfo(id, title),
            rootBlog = BlogComment(
                author = USER,
                contentHtml = body.replace("\n", "<br>"),
                timeInfo = TimeInfo("2026-01-01", epoch = 1),
            ),
            blogComments = emptyList(),
        )
    }

    private companion object {
        val FORM_HASH = FormHash("form")
        val CLASS_SELECTION = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        val USER = User(UserId(1), "test", null)
    }
}
