package me.thenano.yamibo.yamibo_app.repository.appsync

import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.BlogComment
import io.github.littlesurvival.dto.page.BlogInfo
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.BlogPageClassInfo
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
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.APP_SYNC_INDEX_TITLE
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCloudResetResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexJournalReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog

class YamiboAppSyncJournalRemoteTest {
    private val journalCodec = AppSyncJournalEnvelopeCodec()
    private val indexCodec = AppSyncIndexEnvelopeCodec()

    @Test
    fun validCachedJournalAvoidsDiscovery() = runBlocking {
        val payload = payload()
        val blogId = BlogId(10)
        val provider = FakeProvider().apply {
            blogs[blogId] = success(journalPage(blogId, payload))
        }
        val store = FakeRemoteStore(
            storedJournal(payload, blogId),
        )

        val result = assertIs<AppSyncJournalLoadResult.Success>(
            remote(provider, store).loadJournals(ACCOUNT, forceDiscovery = false),
        )

        assertEquals(listOf(payload), result.journals.map { it.payload })
        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(1, provider.fetchBlogCalls)
    }

    @Test
    fun missingJournalReferencedByCachedIndexFallsBackToFullDiscovery() = runBlocking {
        val staleBlogId = BlogId(11)
        val recoveredBlogId = BlogId(12)
        val indexBlogId = BlogId(13)
        val payload = payload()
        val indexPayload = AppSyncIndexPayload(
            accountBinding = ACCOUNT,
            journals = listOf(
                AppSyncIndexJournalReference(
                    replicaKey = payload.replicaKey(),
                    blogId = staleBlogId.value,
                    fingerprint = "stale",
                ),
            ),
            updatedAtEpochMillis = 100,
        )
        val provider = FakeProvider().apply {
            blogs[indexBlogId] = success(
                page(indexBlogId, APP_SYNC_INDEX_TITLE, indexCodec.encode(indexPayload)),
            )
            blogs[recoveredBlogId] = success(journalPage(recoveredBlogId, payload))
            pages[PageKey(null, 1)] = success(classPage())
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(
                            recoveredBlogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    payload.deviceId,
                                    payload.deviceEpoch,
                                ),
                        ),
                    ),
                ),
            )
        }
        val store = FakeRemoteStore(
            StoredAppSyncRemoteBlog(
                remoteKey = "index",
                kind = AppSyncRemoteBlogKind.Index,
                blogId = indexBlogId,
                classId = CLASS_ID,
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            ),
        )

        val result = assertIs<AppSyncJournalLoadResult.Success>(
            remote(provider, store).loadJournals(ACCOUNT, forceDiscovery = false),
        )

        assertEquals(listOf(payload), result.journals.map { it.payload })
        assertEquals(recoveredBlogId, store.load(payload.replicaKey())?.blogId)
        assertEquals(2, provider.fetchBlogListCalls)
    }

    @Test
    fun expiredFormHashStopsPublishBeforeVerification() = runBlocking {
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            submitResult = AppSyncCloudResult.FormExpired("formhash expired")
        }

        val result = remote(provider, FakeRemoteStore()).publishOwnJournal(
            payload = payload(),
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        assertIs<AppSyncJournalPublishResult.FormExpired>(result)
        assertEquals(0, provider.fetchBlogCalls)
    }

    @Test
    fun unknownPostResultNeverClaimsVerification() = runBlocking {
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            submitResult = AppSyncCloudResult.AcknowledgedButUnverified(
                messageText = null,
                reason = "unknown POST result",
            )
        }

        val result = remote(provider, FakeRemoteStore()).publishOwnJournal(
            payload = payload(),
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        assertIs<AppSyncJournalPublishResult.Unknown>(result)
        assertEquals(0, provider.fetchBlogCalls)
    }

    @Test
    fun oversizedJournalStopsBeforeProviderPost() = runBlocking {
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
        }
        val base = payload()
        val sequence = SyncSequence(1)
        val operation = SyncOperation(
            operationId = SyncOperation.idFor(base.deviceId, base.deviceEpoch, sequence),
            deviceId = base.deviceId,
            deviceEpoch = base.deviceEpoch,
            sequence = sequence,
            accountBinding = ACCOUNT,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("oversized"),
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to "x".repeat(50_000)),
            createdAtEpochMillis = 1,
            origin = SyncOperationOrigin.UserAction,
        )

        val result = remote(provider, FakeRemoteStore()).publishOwnJournal(
            payload = base.copy(
                firstSequence = 1,
                lastSequence = 1,
                operations = listOf(operation),
            ),
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        assertIs<AppSyncJournalPublishResult.StoragePressure>(result)
        assertEquals(0, provider.submitCalls)
    }

    @Test
    fun acknowledgedPostRequiresExactAuthoritativeReload() = runBlocking {
        val requested = payload()
        val different = payload(heartbeat = 999)
        val blogId = BlogId(20)
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(
                            blogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    requested.deviceId,
                                    requested.deviceEpoch,
                                ),
                        ),
                    ),
                ),
            )
            blogs[blogId] = success(journalPage(blogId, different))
            submitResult = success(
                AppSyncPostAcknowledgement("操作成功", listOf(blogId)),
            )
        }

        val result = remote(provider, FakeRemoteStore()).publishOwnJournal(
            payload = requested,
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        val unknown = assertIs<AppSyncJournalPublishResult.Unknown>(result)
        assertTrue(unknown.reason.contains("exact journal reload"))
        assertEquals(2, provider.fetchBlogCalls)
    }

    @Test
    fun cachedJournalWithAnotherWriterNonceFailsBeforePost() = runBlocking {
        val requested = payload(nonce = SyncWriterNonce("current-writer"))
        val remotePayload = payload(nonce = SyncWriterNonce("other-writer"))
        val blogId = BlogId(30)
        val provider = FakeProvider().apply {
            blogs[blogId] = success(journalPage(blogId, remotePayload))
        }
        val store = FakeRemoteStore(storedJournal(requested, blogId))

        val result = remote(provider, store).publishOwnJournal(
            payload = requested,
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        assertIs<AppSyncJournalPublishResult.Conflict>(result)
        assertEquals(0, provider.submitCalls)
    }

    @Test
    fun providerOutageAndRateLimitRemainRetryableWithoutCachedMutation() = runBlocking {
        val store = FakeRemoteStore(storedJournal(payload(), BlogId(40)))
        val provider = FakeProvider().apply {
            blogs[BlogId(40)] = AppSyncCloudResult.NetworkFailed("offline")
            pages[PageKey(null, 1)] = AppSyncCloudResult.NetworkFailed("offline")
        }

        assertIs<AppSyncJournalLoadResult.RetryableFailure>(
            remote(provider, store).loadJournals(ACCOUNT, forceDiscovery = false),
        )
        assertEquals(BlogId(40), store.load(payload().replicaKey())?.blogId)

        provider.pages[PageKey(null, 1)] =
            AppSyncCloudResult.HttpFailed(429, "rate limited", "redacted")
        val rateLimited = assertIs<AppSyncJournalLoadResult.RetryableFailure>(
            remote(provider, FakeRemoteStore()).loadJournals(ACCOUNT, forceDiscovery = true),
        )
        assertTrue(rateLimited.reason.contains("rate limited"))
    }

    @Test
    fun unsupportedJournalSchemaIsNeverReturnedAsValidData() = runBlocking {
        val blogId = BlogId(41)
        val title = AppSyncJournalDefaults.journalTitle(
            SyncDeviceId("device"),
            SyncDeviceEpoch("epoch"),
        )
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(
                            blogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] $title",
                        ),
                    ),
                ),
            )
            blogs[blogId] = success(
                page(
                    blogId,
                    title,
                    journalCodec.encode(payload()).replace("schema=1", "schema=99"),
                ),
            )
        }

        val result = assertIs<AppSyncJournalLoadResult.Success>(
            remote(provider, FakeRemoteStore()).loadJournals(ACCOUNT, forceDiscovery = true),
        )

        assertTrue(result.journals.isEmpty())
    }

    @Test
    fun cloudResetDeletesOnlyVerifiedSyncBlogsAndRequiresNotFoundReload() = runBlocking {
        val payload = payload()
        val blogId = BlogId(50)
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(
                            blogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    payload.deviceId,
                                    payload.deviceEpoch,
                                ),
                        ),
                        summary(BlogId(51), "user-authored blog"),
                    ),
                ),
            )
            blogs[blogId] = success(journalPage(blogId, payload))
            deleteHandler = { request ->
                blogs.remove(request.blogId)
                success(AppSyncPostAcknowledgement("操作成功", listOf(request.blogId)))
            }
        }
        val store = FakeRemoteStore(storedJournal(payload, blogId))

        val result = assertIs<AppSyncCloudResetResult.Verified>(
            remote(provider, store).deleteAllVerifiedSyncData(ACCOUNT, FORM_HASH),
        )

        assertEquals(1, result.deletedBlogCount)
        assertEquals(listOf(blogId), provider.deleteRequests.map { it.blogId })
        assertEquals(null, store.load(payload.replicaKey()))
    }

    @Test
    fun partialCloudResetFailureKeepsCacheAndReportsRetryable() = runBlocking {
        val first = payload()
        val second = first.copy(
            deviceId = SyncDeviceId("second-device"),
            deviceEpoch = SyncDeviceEpoch("second-epoch"),
        )
        val firstId = BlogId(60)
        val secondId = BlogId(61)
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(
                            firstId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    first.deviceId,
                                    first.deviceEpoch,
                                ),
                        ),
                        summary(
                            secondId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    second.deviceId,
                                    second.deviceEpoch,
                                ),
                        ),
                    ),
                ),
            )
            blogs[firstId] = success(journalPage(firstId, first))
            blogs[secondId] = success(journalPage(secondId, second))
            deleteHandler = { request ->
                if (request.blogId == firstId) {
                    blogs.remove(firstId)
                    success(AppSyncPostAcknowledgement("操作成功", listOf(firstId)))
                } else {
                    AppSyncCloudResult.NetworkFailed("offline during second delete")
                }
            }
        }
        val store = FakeRemoteStore(
            storedJournal(first, firstId),
            storedJournal(second, secondId),
        )

        assertIs<AppSyncCloudResetResult.RetryableFailure>(
            remote(provider, store).deleteAllVerifiedSyncData(ACCOUNT, FORM_HASH),
        )

        assertEquals(secondId, store.load(second.replicaKey())?.blogId)
        assertEquals(2, provider.deleteRequests.size)
    }

    private fun remote(
        provider: FakeProvider,
        store: FakeRemoteStore,
    ) = YamiboAppSyncJournalRemote(
        provider = provider,
        store = store,
        nowMillis = { 1_000 },
    )

    private fun payload(
        nonce: SyncWriterNonce = SyncWriterNonce("writer"),
        heartbeat: Long = 100,
    ) = AppSyncJournalPayload(
        accountBinding = ACCOUNT,
        deviceId = SyncDeviceId("device"),
        deviceEpoch = SyncDeviceEpoch("epoch"),
        writerNonce = nonce,
        firstSequence = 0,
        lastSequence = 0,
        operations = emptyList(),
        observed = SyncCausalContext(),
        heartbeatAtEpochMillis = heartbeat,
    )

    private fun storedJournal(
        payload: AppSyncJournalPayload,
        blogId: BlogId,
    ) = StoredAppSyncRemoteBlog(
        remoteKey = payload.replicaKey(),
        kind = AppSyncRemoteBlogKind.Journal,
        blogId = blogId,
        classId = CLASS_ID,
        fingerprint = null,
        validatedAtEpochMillis = 0,
        contentUpdatedAtEpochMillis = null,
    )

    private fun journalPage(
        blogId: BlogId,
        payload: AppSyncJournalPayload,
    ) = page(
        blogId,
        AppSyncJournalDefaults.journalTitle(payload.deviceId, payload.deviceEpoch),
        journalCodec.encode(payload),
    )

    private fun page(
        blogId: BlogId,
        title: String,
        content: String,
    ) = BlogPage(
        blogInfo = BlogInfo(blogId = blogId, title = title),
        rootBlog = BlogComment(
            author = USER,
            contentHtml = content.replace("\n", "<br>"),
            timeInfo = TimeInfo("2026-01-01 00:00", epoch = 1),
        ),
        blogComments = emptyList(),
    )

    private fun classPage() = UserSpaceBlogPage(
        blogs = emptyList(),
        blogClasses = listOf(
            BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, CLASS_ID),
        ),
    )

    private fun summary(blogId: BlogId, title: String) = BlogSummary(
        title = title,
        bId = blogId,
        url = "home.php?do=blog&id=${blogId.value}",
        description = "",
        author = USER,
        timeInfo = TimeInfo("2026-01-01 00:00", epoch = 1),
    )

    private fun AppSyncJournalPayload.replicaKey(): String =
        "${deviceId.value}:${deviceEpoch.value}"

    private fun <T> success(value: T): AppSyncCloudResult<T> =
        AppSyncCloudResult.VerifiedSuccess(value)

    private data class PageKey(
        val classId: BlogClassId?,
        val page: Int,
    )

    private class FakeProvider : AppSyncBlogProvider {
        val pages = mutableMapOf<PageKey, AppSyncCloudResult<UserSpaceBlogPage>>()
        val blogs = mutableMapOf<BlogId, AppSyncCloudResult<BlogPage>>()
        var submitResult: AppSyncCloudResult<AppSyncPostAcknowledgement> =
            AppSyncCloudResult.UnknownFailed("submit not configured")
        var deleteHandler:
            suspend (AppSyncBlogDeleteRequest) -> AppSyncCloudResult<AppSyncPostAcknowledgement> = {
                AppSyncCloudResult.UnknownFailed("delete not configured")
            }
        val deleteRequests = mutableListOf<AppSyncBlogDeleteRequest>()
        var fetchBlogCalls = 0
        var fetchBlogListCalls = 0
        var submitCalls = 0

        override suspend fun fetchMyBlogs(
            blogClassId: BlogClassId?,
            page: Int,
        ): AppSyncCloudResult<UserSpaceBlogPage> {
            fetchBlogListCalls += 1
            return pages[PageKey(blogClassId, page)] ?: AppSyncCloudResult.NotFound
        }

        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
            fetchBlogCalls += 1
            return blogs[blogId] ?: AppSyncCloudResult.NotFound
        }

        override suspend fun submitBlog(
            request: AppSyncBlogWriteRequest,
        ): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            submitCalls += 1
            return submitResult
        }

        override suspend fun deleteBlog(
            request: AppSyncBlogDeleteRequest,
        ): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            deleteRequests += request
            return deleteHandler(request)
        }
    }

    private class FakeRemoteStore(
        vararg initial: StoredAppSyncRemoteBlog,
    ) : AppSyncRemoteBlogStore {
        private val values = initial.associateByTo(linkedMapOf()) { it.remoteKey }

        override fun load(remoteKey: String): StoredAppSyncRemoteBlog? = values[remoteKey]

        override fun loadKind(kind: AppSyncRemoteBlogKind): List<StoredAppSyncRemoteBlog> =
            values.values.filter { it.kind == kind }

        override fun save(blog: StoredAppSyncRemoteBlog) {
            values[blog.remoteKey] = blog
        }

        override fun remove(remoteKey: String) {
            values.remove(remoteKey)
        }

        override fun clear() {
            values.clear()
        }
    }

    private companion object {
        val ACCOUNT = SyncAccountBinding("account")
        val CLASS_ID = BlogClassId(4568)
        val FORM_HASH = FormHash("hash")
        val USER = User(UserId(1), "owner")
    }
}
