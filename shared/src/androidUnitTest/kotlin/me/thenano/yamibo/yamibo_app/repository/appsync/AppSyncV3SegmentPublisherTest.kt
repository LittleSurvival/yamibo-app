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
    private inner class Fixture(val db: Database) {
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
        suspend fun publish(publisher: AppSyncV3SegmentPublisher = publisher(), source: String = envelope) = publisher.publish(
            session.sessionId, source, kind, "checkpoint", AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
    }
    private fun fixture(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { Database.Schema.create(it); Fixture(Database(it)).block() }
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
