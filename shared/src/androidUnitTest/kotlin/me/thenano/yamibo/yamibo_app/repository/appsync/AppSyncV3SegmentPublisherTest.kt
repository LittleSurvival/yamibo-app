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
        var authOnRead = false
        var wrongRead = false
        var reads = 0
        var pageSize = 10_000
        var listReads = 0
        var listFailureAt: Int? = null
        var repeatPage = false
        var missingRead = false
        var wrongClass = false
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
            val id = 100 + posts.size
            if (posts.size != timeoutAt || storeTimedOut) artifacts[id] = request
            return if (posts.size == timeoutAt) AppSyncCloudResult.Timeout("lost response")
                else AppSyncCloudResult.VerifiedSuccess(AppSyncPostAcknowledgement(null, listOf(BlogId(id))))
        }
        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
            reads++
            if (authOnRead) return AppSyncCloudResult.NotLoggedIn
            if (missingRead) return AppSyncCloudResult.NotFound
            val artifact = artifacts[blogId.value] ?: return AppSyncCloudResult.NotFound
            return AppSyncCloudResult.VerifiedSuccess(BlogPage(BlogInfo(blogId, artifact.title),
                BlogComment(author = User(UserId(1), "test", null), contentHtml = if (wrongRead) "wrong" else artifact.message.replace("\n", "<br>"),
                    timeInfo = TimeInfo("test", epoch = 1)), emptyList()))
        }
        override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> = error("No cleanup before index commit")
    }
}
