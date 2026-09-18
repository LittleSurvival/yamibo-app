package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.store.appsync.*
import okio.ByteString.Companion.encodeUtf8

class AppSyncV3SegmentPublisherTest {
    private val account = SyncAccountBinding("account")
    private val kind = AppSyncV3PayloadKind.Checkpoint
    private inner class Fixture(val db: Database, val driver: JdbcSqliteDriver) {
        val operations = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val pending = operations.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            1, SyncOperationKind.Put, mapOf("type" to "int", "value" to "18"), SyncCausalContext(), 1, SyncOperationOrigin.UserAction)
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = recovery.createOrResumeSegmentedCheckpoint(account, "checkpoint", "source", 2).also {
            recovery.startSegmentedJournal(it.sessionId, 3)
        }
        val codec = AppSyncV3SegmentCodec(AppSyncPayloadBudget(4096))
        val document = AppSyncCanonicalCheckpoint("checkpoint", account.value, 1,
            (1..200).associate { (it.toString().encodeUtf8().sha256().hex() + ":epoch") to 1L }, emptyList())
        val envelope = AppSyncV3DocumentCodec().encodeCheckpoint(document)
        val provider = Provider()
        fun publisher(gate: suspend () -> Boolean = { true }, discovery: suspend (String, String) -> AppSyncV3ArtifactDiscovery = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))::discover) =
            AppSyncV3SegmentPublisher(provider, SqlDelightAppSyncRecoveryStore(db), { 10 }, codec, gate, discovery)
        fun committer() = AppSyncV3IndexCommitter(provider, SqlDelightAppSyncRecoveryStore(db), publisher(), { 20 }, { true })
        suspend fun commit() = committer().commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        fun prepareJournal(): Triple<String, String, String> {
            recovery.rollbackPreCommit(session.sessionId)
            val staged = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
            val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, pending))
            val identity = "${staged.targetDeviceId.value}:${staged.targetDeviceEpoch.value}"
            val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
                staged.targetDeviceId.value, staged.targetDeviceEpoch.value, staged.targetWriterNonce.value,
                pending.sequence.value, pending.sequence.value, emptyMap(), emptyList(), 1, 3, 3, "test", pending.sequence.value)
            return Triple(staged.sessionId, AppSyncV3DocumentCodec().encodeJournal(identity, journal), identity)
        }
        suspend fun publish(publisher: AppSyncV3SegmentPublisher = publisher(), source: String = envelope) = publisher.publish(
            session.sessionId, source, kind, "checkpoint", AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
    }
    private fun fixture(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { Database.Schema.create(it); Fixture(Database(it), it).block() }
    }

    @Test fun everyArtifactIsReadBackAndRestartReusesFrozenGenerationWithoutAcknowledgement() = fixture {
        val result = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val count = provider.posts.size
        assertEquals(codec.plan(envelope, account.value, kind).drafts.size + 1, count)
        assertEquals(count, provider.reads)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(session.sessionId)?.phase)
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val changed = AppSyncV3DocumentCodec().encodeCheckpoint(document.copy(createdAtEpochMillis = 99))
        val replay = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish(publisher(), changed))
        assertEquals(result, replay)
        assertEquals(count, provider.posts.size)
        assertEquals(count * 2, provider.reads)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
        assertTrue(provider.posts.none { it.title == APP_SYNC_INDEX_TITLE })
    }

    @Test fun unknownSegmentOutcomeCannotCreateAgainUntilAuthoritativeAbsence() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        assertEquals(2, provider.posts.size)
        assertEquals(1, recovery.segmentWrites(session.sessionId).count { it.blogId != null })
        provider.timeoutAt = null
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        assertEquals(2, provider.posts.size)
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun lostSegmentAndRootResponsesAreReconciledWithoutDuplicatePosts() = fixture {
        val segments = codec.plan(envelope, account.value, kind).drafts.size
        provider.storeTimedOut = true
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        provider.timeoutAt = segments + 1
        var unknownRoot = true
        val discovery: suspend (String, String) -> AppSyncV3ArtifactDiscovery = { title, sha ->
            if (title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) && unknownRoot) AppSyncV3ArtifactDiscovery.Unknown
            else provider.discover(title, sha)
        }
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = discovery)))
        assertEquals(segments + 1, provider.posts.size)
        assertEquals(AppSyncRecoveryPhase.PublishingRoot, recovery.session(session.sessionId)?.phase)
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().rootIntentFingerprint)
        unknownRoot = false
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish(publisher(discovery = discovery)))
        assertEquals(segments + 1, provider.posts.size)
    }

    @Test fun gateAndAuthenticationStopWritesAndReadbackMismatchCannotAdvanceProgress() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.Disabled>(publish(AppSyncV3SegmentPublisher(provider, recovery, { 10 }, codec)))
        assertTrue(provider.posts.isEmpty())
        var checks = 0
        assertIs<AppSyncV3SegmentPublishResult.Disabled>(publish(publisher(gate = { ++checks == 1 })))
        assertTrue(provider.posts.isEmpty())
        provider.authOnRead = true
        assertIs<AppSyncV3SegmentPublishResult.FormExpired>(publish())
        assertTrue(recovery.segmentWrites(session.sessionId).none { it.blogId != null })
        provider.authOnRead = false
        provider.wrongRead = true
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish())
        assertTrue(recovery.segmentWrites(session.sessionId).none { it.blogId != null })
        assertEquals(1, provider.posts.size)
    }

    @Test fun changedPlanAndAmbiguousDiscoveryCannotAppendToExistingGeneration() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        val changed = AppSyncV3SegmentPublisher(provider, recovery, { 10 }, AppSyncV3SegmentCodec(AppSyncPayloadBudget(8192)), { true }, provider::discover)
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish(changed))
        assertEquals(2, provider.posts.size)
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Conflict })))
        assertEquals(2, provider.posts.size)
    }

    @Test fun discoveryCompletesPaginationBeforeClaimingFoundOrAbsent() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.pageSize = 1
        val artifact = provider.artifacts.entries.last()
        val reconciler = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))
        assertEquals(AppSyncV3ArtifactDiscovery.Found(BlogId(artifact.key)),
            reconciler.discover(artifact.value.title, artifact.value.message.encodeUtf8().sha256().hex()))
        assertEquals(provider.artifacts.size, provider.listReads)
        provider.listFailureAt = 2
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover("missing", "0".repeat(64)))
        provider.listFailureAt = null
        assertEquals(AppSyncV3ArtifactDiscovery.Absent, reconciler.discover("missing", "0".repeat(64)))
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown,
            AppSyncV3ArtifactReconciler(provider, BlogClassId(7), maxPages = 1).discover("missing", "0".repeat(64)))
        provider.repeatPage = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover("missing", "0".repeat(64)))
    }

    @Test fun nativeIndexCommitRequiresExactFrozenCanonicalReferenceAndPersistsEvidenceAtomically() = fixture {
        val root = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val reference = AppSyncIndexCheckpointReference("checkpoint", root.rootBlogId.value, root.root.metadata.canonicalFingerprint)
        val postsBefore = provider.posts.size
        val legacy = AppSyncSegmentIndexCommitter(provider, SqlDelightAppSyncRemoteBlogStore(db), recovery, nowMillis = { 20 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(legacy.commitCheckpointRoot(session.sessionId, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertEquals(postsBefore, provider.posts.size)
        assertEquals(0, provider.listReads)
        val index = AppSyncIndexPayload(account, checkpoints = listOf(reference), updatedAtEpochMillis = 20)
        fun body(value: AppSyncIndexPayload = index) = AppSyncIndexEnvelopeCodec().encode(value).replace("\n", "<br>")
        recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(AppSyncIndexEnvelopeCodec().encode(index), null, null))
        assertFailsWith<IllegalArgumentException> { recovery.markIndexCommitted(session.sessionId, 20) }
        for (bad in listOf(index.copy(accountBinding = SyncAccountBinding("other")),
            index.copy(checkpoints = emptyList()),
            index.copy(checkpoints = listOf(reference.copy(blogId = 999))),
            index.copy(checkpoints = listOf(reference.copy(fingerprint = root.root.envelopeSha256))))) {
            assertFailsWith<IllegalArgumentException> { recovery.markNativeIndexCommitted(session.sessionId, 900, body(bad), 20) }
            assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
            assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().verifiedIndexBlogId)
        }
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.markNativeIndexCommitted(session.sessionId, 900, body(), 21)
                error("abort outer transaction")
            }
        }
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(session.sessionId)?.phase)
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().verifiedIndexBlogId)
        recovery.markNativeIndexCommitted(session.sessionId, 900, body(), 22)
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        restarted.markNativeIndexCommitted(session.sessionId, 900, body(), 23)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, restarted.session(session.sessionId)?.phase)
        val evidence = db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne()
        assertEquals(900L, evidence.verifiedIndexBlogId)
        assertEquals(22L, evidence.indexVerifiedAtEpochMillis)
        assertNotNull(evidence.verifiedIndexFingerprint)
        assertFailsWith<IllegalArgumentException> { restarted.markNativeIndexCommitted(session.sessionId, 901, body(), 24) }
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
    }

    @Test fun journalIndexCommitBindsWriterReplicaAndRequiresCanonicalFingerprint() = fixture {
        recovery.rollbackPreCommit(session.sessionId)
        val journalSession = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
        recovery.startSegmentedJournal(journalSession.sessionId, 3)
        val identity = "${journalSession.targetDeviceId.value}:${journalSession.targetDeviceEpoch.value}"
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()),
            journalSession.targetDeviceId.value, journalSession.targetDeviceEpoch.value, journalSession.targetWriterNonce.value,
            0, 0, emptyMap(), emptyList(), 1, 3, 3, "test", 0)
        val frozen = AppSyncV3DocumentCodec().encodeJournal(identity, journal)
        val root = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publisher().publish(journalSession.sessionId,
            frozen, AppSyncV3PayloadKind.Journal, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        val ref = AppSyncIndexJournalReference(identity, root.rootBlogId.value, root.root.metadata.canonicalFingerprint)
        fun index(reference: AppSyncIndexJournalReference) = AppSyncIndexEnvelopeCodec().encode(
            AppSyncIndexPayload(account, journals = listOf(reference), updatedAtEpochMillis = 20))
        recovery.pinNativeIndexIntent(journalSession.sessionId, NativeRecoveryIndexIntent(index(ref), null, null))
        for (bad in listOf(ref.copy(replicaKey = "other"), ref.copy(fingerprint = null), ref.copy(fingerprint = root.root.envelopeSha256))) {
            assertFailsWith<IllegalArgumentException> { recovery.markNativeIndexCommitted(journalSession.sessionId, 900, index(bad), 20) }
        }
        assertEquals(false, recovery.session(journalSession.sessionId)?.indexCommitted)
        recovery.markNativeIndexCommitted(journalSession.sessionId, 900, index(ref), 21)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        // This synthetic journal contains no source operation: publication alone cannot acknowledge it.
        assertFailsWith<IllegalArgumentException> { recovery.activateCommittedSession(journalSession.sessionId, 22) }
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCommitCreatesIndexOnlyAfterRootAndPreservesPendingSources() = fixture {
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(APP_SYNC_INDEX_TITLE, provider.posts.last().title)
        val index = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message)).envelope.payload
        assertEquals(recovery.session(session.sessionId)?.rootBlogId, index.checkpoints.single().blogId.toLong())
        val row = db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne()
        assertNotNull(row.verifiedIndexBlogId)
        assertNotNull(row.indexIntentSha256)
        assertEquals(provider.posts.last().message, recovery.nativeIndexIntent(session.sessionId)?.body)
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
        assertFailsWith<IllegalArgumentException> { recovery.activateCommittedSession(session.sessionId, 30) }
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(session.sessionId)?.phase)
    }

    @Test fun nativeJournalActivationAcknowledgesOnlyExactPublishedSourcesAndRollsBackAtomically() = fixture {
        recovery.rollbackPreCommit(session.sessionId)
        val journalSession = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
        recovery.startSegmentedJournal(journalSession.sessionId, 3)
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, pending))
        val identity = "${journalSession.targetDeviceId.value}:${journalSession.targetDeviceEpoch.value}"
        val document = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
            journalSession.targetDeviceId.value, journalSession.targetDeviceEpoch.value, journalSession.targetWriterNonce.value,
            pending.sequence.value, pending.sequence.value, emptyMap(), emptyList(), 1, 3, 3, "test", pending.sequence.value)
        val frozen = AppSyncV3DocumentCodec().encodeJournal(identity, document)
        suspend fun commitJournal() = committer().commit(journalSession.sessionId, frozen, AppSyncV3PayloadKind.Journal, identity,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitJournal())
        val later = operations.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            1, SyncOperationKind.Put, mapOf("type" to "int", "value" to "19"), SyncCausalContext(), 30, SyncOperationOrigin.UserAction)
        val before = operations.installation()
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.activateCommittedSession(journalSession.sessionId, 31)
                error("abort activation")
            }
        }
        assertEquals(before, operations.installation())
        assertEquals(listOf(pending, later), operations.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        val laterFields = db.appSyncOperationQueries.getOutboxOperation(later.operationId.value).executeAsOne().fieldsJson
        // An unrelated damaged row must neither be decoded nor acknowledged by this session.
        driver.execute(null, "UPDATE AppSyncOutbox SET fieldsJson = ? WHERE operationId = ?", 2) {
            bindString(0, "invalid-json"); bindString(1, later.operationId.value)
        }
        SqlDelightAppSyncRecoveryStore(db).activateCommittedSession(journalSession.sessionId, 32)
        val untouched = db.appSyncOperationQueries.getOutboxOperation(later.operationId.value).executeAsOne()
        assertEquals("invalid-json", untouched.fieldsJson)
        assertEquals("PENDING_LOCAL", untouched.lifecycle)
        driver.execute(null, "UPDATE AppSyncOutbox SET fieldsJson = ? WHERE operationId = ?", 2) {
            bindString(0, laterFields); bindString(1, later.operationId.value)
        }
        assertEquals(listOf(later), operations.pendingOperations())
        assertEquals(AppSyncOperationLifecycle.Acknowledged, operations.allOutboxOperations().single { it.first == pending }.second)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(journalSession.sessionId)?.phase)
        assertEquals(before?.nextSequence, operations.installation()?.nextSequence)
        val activated = operations.installation()
        recovery.activateCommittedSession(journalSession.sessionId, 33)
        assertEquals(activated, operations.installation())
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitJournal())
        assertEquals(count, provider.posts.size)
        assertTrue(operations.verifiedCheckpoints().isEmpty())
    }

    @Test fun nativeCommitReconcilesLostIndexResponseAfterRestartWithoutSecondPost() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.timeoutAt = provider.posts.size + 1
        provider.storeTimedOut = true
        provider.failIndexRead = true
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        val count = provider.posts.size
        provider.failIndexRead = false
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(intent, recovery.nativeIndexIntent(session.sessionId))
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeIndexUpdateKeepsOtherReferencesAndRejectsConcurrentBaseChange() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val base = AppSyncIndexPayload(account, journals = listOf(AppSyncIndexJournalReference("other", 700, "other-sha")),
            checkpoints = listOf(AppSyncIndexCheckpointReference("old", 701, "old-sha")), updatedAtEpochMillis = 5)
        fun request(value: AppSyncIndexPayload) = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE,
            AppSyncIndexEnvelopeCodec().encode(value), AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.artifacts[800] = request(base)
        provider.onList = { call -> if (call == 2) provider.artifacts[800] = request(base.copy(updatedAtEpochMillis = 6)) }
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        assertEquals(800L, intent.targetBlogId)
        provider.onList = {}
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        // Restore exactly the observed base; the immutable intent is now safe to retry.
        provider.artifacts[800] = request(base)
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(BlogId(800), provider.posts.last().blogId)
        val merged = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message)).envelope.payload
        assertEquals(base.journals, merged.journals)
        assertTrue(base.checkpoints.single() in merged.checkpoints)
        assertEquals(2, merged.checkpoints.size)
    }

    @Test fun nativeIndexGateAndIncompleteDiscoveryCannotCreateOrCommit() = fixture {
        val disabled = AppSyncV3IndexCommitter(provider, recovery, publisher(), { 20 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(disabled.commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertTrue(provider.posts.isEmpty())
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val count = provider.posts.size
        provider.listFailureAt = 1
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        provider.listFailureAt = null
        var checks = 0
        val gated = AppSyncV3IndexCommitter(provider, recovery, publisher(), { 20 }, { ++checks == 1 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(gated.commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        assertEquals(count, provider.posts.size)
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
    }

    @Test fun nativeIndexMustRetainTheCheckpointUsedByTheCurrentCloudPlan() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val required = AppSyncCanonicalCheckpoint("required-base", account.value, 1, emptyMap(), emptyList())
        val reference = AppSyncIndexCheckpointReference(required.checkpointId, 700,
            AppSyncCanonicalCheckpointCodec().encode(required).sha256().hex())
        val base = AppSyncIndexPayload(account, checkpoints = listOf(reference), updatedAtEpochMillis = 5)
        val body = AppSyncIndexEnvelopeCodec().encode(base)
        val verified = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 700, body,
            AppSyncV3DocumentCodec().encodeCheckpoint(required)))
        suspend fun attempt() = committer().commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"), requiredCheckpoint = verified)
        val posts = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(attempt())
        assertEquals(posts, provider.posts.size)
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        val request = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE, body,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.artifacts[800] = request.copy(message = AppSyncIndexEnvelopeCodec().encode(base.copy(
            checkpoints = listOf(reference.copy(blogId = 701)))))
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(attempt())
        assertEquals(posts, provider.posts.size)
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        provider.artifacts[800] = request
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(attempt())
        val written = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message))
        assertTrue(reference in written.envelope.payload.checkpoints)
    }

    @Test fun nativeCoordinatorContinuesFromStagingThroughVerifiedActivationInOneRun() = fixture {
        val (id, frozen, identity) = prepareJournal()
        val coordinator = AppSyncV3CommitCoordinator(committer(), recovery, { 100 }, canRun = { true })
        suspend fun run() = coordinator.commit(id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        val result = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertEquals(setOf(pending.operationId.value), result.acknowledgedOperationIds)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertTrue(operations.pendingOperations().isEmpty())
        assertEquals(pending, operations.allOutboxOperations().single().first)
        val posts = provider.posts.size
        assertEquals(result, run())
        assertEquals(posts, provider.posts.size)
    }

    @Test fun legacyPublisherAndCoordinatorCannotAdvanceOrChargeNativeRecovery() = fixture {
        val (id, frozen, identity) = prepareJournal()
        recovery.startSegmentedJournal(id, 3)
        recovery.pinPayload(id, "Journal", identity, 3) { frozen }
        val oldPublisher = AppSyncSegmentPublisher(provider, recovery, nowMillis = { 20 })
        val oldCommitter = AppSyncSegmentIndexCommitter(provider, SqlDelightAppSyncRemoteBlogStore(db), recovery, nowMillis = { 20 })
        val oldCoordinator = AppSyncSegmentedJournalCommitCoordinator(oldPublisher, oldCommitter, recovery, { 20 })
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        val form = FormHash("test")
        val before = recovery.session(id)
        assertIs<AppSyncSegmentPublishResult.Terminal>(oldPublisher.publish(id, frozen, AppSyncSegmentPayloadKind.Journal, identity, selection, form))
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(oldCoordinator.commit(id, frozen, identity, selection, form))
        assertEquals(before, recovery.session(id))
        assertTrue(provider.posts.isEmpty())
        assertEquals(0, provider.listReads)
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(committer().commit(id, frozen, AppSyncV3PayloadKind.Journal, identity, selection, form))
        val committed = recovery.session(id)
        val count = provider.posts.size
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(oldCoordinator.commit(id, frozen, identity, selection, form))
        assertEquals(committed, recovery.session(id))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(count, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCoordinatorCannotChargeOrReplaceFrozenLegacyPayload() = fixture {
        val (id, frozen, identity) = prepareJournal()
        recovery.startSegmentedJournal(id, 3)
        recovery.pinPayload(id, "Journal", identity) { "legacy-frozen-body" }
        val before = recovery.session(id)
        val result = AppSyncV3CommitCoordinator(committer(), recovery, { 100 }, canRun = { true }).commit(
            id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(result)
        assertEquals(before, recovery.session(id))
        assertEquals(2L, recovery.payloadTransportVersion(id))
        assertEquals("legacy-frozen-body", recovery.pinPayload(id, "Journal", identity) { error("Must retain frozen source") })
        assertTrue(provider.posts.isEmpty())
        assertEquals(0, provider.listReads)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCoordinatorRespectsDurableRetryDeadlineAndStopsAtThirdFailure() = fixture {
        val (id, frozen, identity) = prepareJournal()
        var now = 100L
        suspend fun run() = AppSyncV3CommitCoordinator(committer(), SqlDelightAppSyncRecoveryStore(db), { now }, canRun = { true })
            .commit(id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.timeoutAt = 1
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(1L, recovery.session(id)?.retryCount)
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(1, provider.posts.size)
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        provider.timeoutAt = 2
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(2L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        provider.timeoutAt = 3
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(run())
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals(3L, recovery.session(id)?.retryCount)
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(run())
        assertEquals(3, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertNotNull(recovery.resumeRetryExhaustedRecovery(account, now + 1))
        provider.timeoutAt = null
        now++
        assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertTrue(operations.pendingOperations().isEmpty())
    }

    @Test fun indexAcknowledgementAloneAndDuplicateCandidatesNeverGrantCommit() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.skipStoreAt = provider.posts.size + 1
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        val indexRequest = provider.posts.last()
        provider.artifacts[800] = indexRequest
        provider.artifacts[801] = indexRequest
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(intent, recovery.nativeIndexIntent(session.sessionId))
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        provider.artifacts.remove(801)
        provider.authOnRead = true
        assertIs<AppSyncSegmentIndexCommitResult.FormExpired>(commit())
        assertEquals(count, provider.posts.size)
        provider.authOnRead = false
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
    }

    @Test fun discoveryRejectsDuplicateMatchesAndUnreadableCandidates() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val artifact = provider.artifacts.entries.last()
        val title = artifact.value.title
        val sha = artifact.value.message.encodeUtf8().sha256().hex()
        val reconciler = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))
        provider.authOnRead = true
        assertEquals(AppSyncV3ArtifactDiscovery.FormExpired, reconciler.discover(title, sha))
        provider.authOnRead = false
        provider.missingRead = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover(title, sha))
        provider.missingRead = false
        provider.artifacts[999] = artifact.value
        assertEquals(AppSyncV3ArtifactDiscovery.Conflict, reconciler.discover(title, sha))
        provider.artifacts[999] = artifact.value.copy(message = "older generation")
        assertEquals(AppSyncV3ArtifactDiscovery.Found(BlogId(artifact.key)), reconciler.discover(title, sha))
        provider.wrongClass = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover(title, sha))
    }

    private class Provider : AppSyncBlogProvider {
        val posts = mutableListOf<AppSyncBlogWriteRequest>()
        val artifacts = linkedMapOf<Int, AppSyncBlogWriteRequest>()
        var timeoutAt: Int? = null
        var storeTimedOut = false
        var skipStoreAt: Int? = null
        var authOnRead = false
        var wrongRead = false
        var reads = 0
        var pageSize = 10_000
        var listReads = 0
        var listFailureAt: Int? = null
        var repeatPage = false
        var missingRead = false
        var wrongClass = false
        var failIndexRead = false
        var onList: (Int) -> Unit = {}
        fun discover(title: String, sha: String): AppSyncV3ArtifactDiscovery {
            val ids = artifacts.filterValues { it.title == title && it.message.encodeUtf8().sha256().hex() == sha }.keys
            return when (ids.size) {
                0 -> AppSyncV3ArtifactDiscovery.Absent
                1 -> AppSyncV3ArtifactDiscovery.Found(BlogId(ids.single()))
                else -> AppSyncV3ArtifactDiscovery.Conflict
            }
        }
        override suspend fun fetchMyBlogs(blogClassId: BlogClassId?, page: Int): AppSyncCloudResult<UserSpaceBlogPage> {
            listReads++
            onList(listReads)
            if (page == listFailureAt) return AppSyncCloudResult.Timeout("list interrupted")
            val total = maxOf(1, (artifacts.size + pageSize - 1) / pageSize)
            val rows = artifacts.entries.drop((if (repeatPage) 0 else page - 1) * pageSize).take(pageSize)
            return AppSyncCloudResult.VerifiedSuccess(UserSpaceBlogPage(
                blogs = rows.map { (id, request) -> BlogSummary("[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] ${request.title}", BlogId(id),
                    "https://example.invalid/blog", "", User(UserId(1), "test", null), TimeInfo("test", epoch = 1)) },
                pageNav = PageNav(currentPage = page, totalPages = total),
                blogClasses = listOf(BlogPageClassInfo(if (wrongClass) "wrong" else AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, BlogClassId(7))),
            ))
        }
        override suspend fun submitBlog(request: AppSyncBlogWriteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            posts += request
            val id = request.blogId?.value ?: (100 + posts.size)
            if (posts.size != skipStoreAt && (posts.size != timeoutAt || storeTimedOut)) artifacts[id] = request
            return if (posts.size == timeoutAt) AppSyncCloudResult.Timeout("lost response")
                else AppSyncCloudResult.VerifiedSuccess(AppSyncPostAcknowledgement(null, listOf(BlogId(id))))
        }
        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
            reads++
            if (authOnRead) return AppSyncCloudResult.NotLoggedIn
            if (missingRead) return AppSyncCloudResult.NotFound
            val artifact = artifacts[blogId.value] ?: return AppSyncCloudResult.NotFound
            if (failIndexRead && artifact.title == APP_SYNC_INDEX_TITLE) return AppSyncCloudResult.Timeout("index read interrupted")
            return AppSyncCloudResult.VerifiedSuccess(BlogPage(BlogInfo(blogId, artifact.title),
                BlogComment(author = User(UserId(1), "test", null), contentHtml = if (wrongRead) "wrong" else artifact.message.replace("\n", "<br>"),
                    timeInfo = TimeInfo("test", epoch = 1)), emptyList()))
        }
        override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> = error("No cleanup before index commit")
    }
}
