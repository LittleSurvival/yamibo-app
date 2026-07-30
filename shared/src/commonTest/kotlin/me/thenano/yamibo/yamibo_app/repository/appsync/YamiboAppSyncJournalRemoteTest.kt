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
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
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
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCloudResetResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexCheckpointReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexJournalReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexRetirementReference
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog

class YamiboAppSyncJournalRemoteTest {
    private val journalCodec = AppSyncJournalEnvelopeCodec()
    private val indexCodec = AppSyncIndexEnvelopeCodec()
    private val checkpointCodec = AppSyncCheckpointEnvelopeCodec()

    @Test
    fun clearLinkCacheRemovesBlogIdsButKeepsClassId() {
        val journal = payload()
        val store = FakeRemoteStore(
            storedJournal(journal, BlogId(10)),
        )
        val remote = remote(FakeProvider(), store)

        assertEquals(1, remote.clearLinkCache(ACCOUNT))

        assertTrue(store.loadKind(AppSyncRemoteBlogKind.Journal).isEmpty())
        assertEquals(CLASS_ID, store.loadClassId(ACCOUNT))
    }

    @Test
    fun retirementIndexIsAuthoritativelyReloaded() = runBlocking {
        val indexBlogId = BlogId(9)
        val intent = retirementIntent()
        val retirement = AppSyncIndexRetirementReference(
            intent.replicaKey,
            intent.sourceBlogId.toInt(),
            intent.fingerprint,
            intent.publishedThroughSequence,
            intent.checkpointId,
        )
        val payload = AppSyncIndexPayload(
            accountBinding = ACCOUNT,
            retirements = listOf(retirement),
            updatedAtEpochMillis = 1_000,
        )
        val provider = FakeProvider().apply {
            submitResult = success(
                AppSyncPostAcknowledgement("操作成功", listOf(indexBlogId)),
            )
            blogs[indexBlogId] = success(
                page(indexBlogId, APP_SYNC_INDEX_TITLE, indexCodec.encode(payload)),
            )
        }
        val store = FakeRemoteStore(
            StoredAppSyncRemoteBlog(
                remoteKey = "index",
                kind = AppSyncRemoteBlogKind.Index,
                blogId = indexBlogId,
                classId = CLASS_ID,
                fingerprint = "old",
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = 0,
            ),
        )

        assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.engine
                .AppSyncJournalRetirementRemoteResult.Verified
            >(remote(provider, store).publishRetirementIndex(intent, FORM_HASH))
        assertEquals(1, provider.submitCalls)
        assertEquals(2, provider.fetchBlogCalls)
    }

    @Test
    fun retirementDeleteUsesRequestResultWithoutPostDeleteFetch() = runBlocking {
        val payload = payload().copy(publishedThroughSequence = 1)
        val intent = retirementIntent(
            replicaKey = payload.replicaKey(),
            blogId = 42,
            fingerprint = journalCodec.validate(journalCodec.encode(payload))
                .let {
                    assertIs<
                        me.thenano.yamibo.yamibo_app.repository.appsync.remote
                            .AppSyncJournalValidation.Valid
                        >(it).envelope.fingerprint
                },
            sequence = 1,
        )
        val provider = FakeProvider().apply {
            blogs[BlogId(42)] = success(journalPage(BlogId(42), payload))
            deleteHandler = { request ->
                success(AppSyncPostAcknowledgement("操作成功", listOf(request.blogId)))
            }
        }

        assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.engine
                .AppSyncJournalRetirementRemoteResult.Verified
            >(remote(provider, FakeRemoteStore()).deleteRetiredJournal(intent, FORM_HASH))
        assertEquals(1, provider.fetchBlogCalls)
        assertEquals(1, provider.deleteRequests.size)
    }

    @Test
    fun currentIndexReplacesPreviouslyCachedJournalLink() = runBlocking {
        val stale = payload(heartbeat = 10)
        val current = payload(heartbeat = 20)
        val staleBlogId = BlogId(10)
        val currentBlogId = BlogId(11)
        val indexBlogId = BlogId(12)
        val index = AppSyncIndexPayload(
            accountBinding = ACCOUNT,
            journals = listOf(
                AppSyncIndexJournalReference(
                    replicaKey = current.replicaKey(),
                    blogId = currentBlogId.value,
                    fingerprint = null,
                ),
            ),
            updatedAtEpochMillis = 1_000,
        )
        val provider = FakeProvider().apply {
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(summary(indexBlogId, APP_SYNC_INDEX_TITLE)),
                ),
            )
            blogs[staleBlogId] = success(journalPage(staleBlogId, stale))
            blogs[currentBlogId] = success(journalPage(currentBlogId, current))
            blogs[indexBlogId] = success(
                page(indexBlogId, APP_SYNC_INDEX_TITLE, indexCodec.encode(index)),
            )
        }
        val store = FakeRemoteStore(
            storedJournal(stale, staleBlogId),
        ).apply { saveClassId(ACCOUNT, CLASS_ID) }

        val result = assertIs<AppSyncJournalLoadResult.Success>(
            remote(provider, store).loadJournals(ACCOUNT, forceDiscovery = false),
        )

        assertEquals(listOf(current), result.journals.map { it.payload })
        assertEquals(1, provider.fetchBlogListCalls)
        assertEquals(2, provider.fetchBlogCalls)
        assertEquals(currentBlogId, store.load(current.replicaKey())?.blogId)
    }

    @Test
    fun validCachedIndexLoadsCheckpointWithoutDiscovery() = runBlocking {
        val journal = payload()
        val journalBlogId = BlogId(14)
        val checkpointBlogId = BlogId(15)
        val indexBlogId = BlogId(16)
        val checkpoint = checkpointCodec.createPayload(
            checkpointId = "checkpoint-1",
            accountBinding = ACCOUNT,
            coverage = SyncCausalContext(),
            snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
            tombstones = emptyList(),
            createdAtEpochMillis = 100,
        )
        val checkpointFingerprint = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation.Valid
            >(checkpointCodec.validate(checkpointCodec.encode(checkpoint))).envelope.fingerprint
        val journalFingerprint = assertIs<
            me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalValidation.Valid
            >(journalCodec.validate(journalCodec.encode(journal))).envelope.fingerprint
        val index = AppSyncIndexPayload(
            accountBinding = ACCOUNT,
            journals = listOf(
                AppSyncIndexJournalReference(
                    replicaKey = journal.replicaKey(),
                    blogId = journalBlogId.value,
                    fingerprint = journalFingerprint,
                ),
            ),
            checkpoints = listOf(
                AppSyncIndexCheckpointReference(
                    checkpointId = checkpoint.checkpointId,
                    blogId = checkpointBlogId.value,
                    fingerprint = checkpointFingerprint,
                ),
            ),
            updatedAtEpochMillis = 100,
        )
        val provider = FakeProvider().apply {
            pages[PageKey(CLASS_ID, 1)] = success(
                UserSpaceBlogPage(
                    blogs = listOf(
                        summary(indexBlogId, APP_SYNC_INDEX_TITLE, epoch = 1),
                        summary(
                            journalBlogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.journalTitle(
                                    journal.deviceId,
                                    journal.deviceEpoch,
                                ),
                            epoch = 3,
                        ),
                        summary(
                            checkpointBlogId,
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.checkpointTitle(checkpoint.checkpointId),
                            epoch = 2,
                        ),
                        summary(
                            BlogId(17),
                            "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] " +
                                AppSyncJournalDefaults.checkpointTitle("stale-deleted-checkpoint"),
                            epoch = 4,
                        ),
                    ),
                ),
            )
            blogs[indexBlogId] = success(
                page(indexBlogId, APP_SYNC_INDEX_TITLE, indexCodec.encode(index)),
            )
            blogs[journalBlogId] = success(journalPage(journalBlogId, journal))
            blogs[checkpointBlogId] = success(
                page(
                    checkpointBlogId,
                    AppSyncJournalDefaults.checkpointTitle(checkpoint.checkpointId),
                    checkpointCodec.encode(checkpoint),
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
        ).apply { saveClassId(ACCOUNT, CLASS_ID) }
        val journalRemote = remote(provider, store)

        val result = assertIs<AppSyncJournalLoadResult.Success>(
            journalRemote.loadJournals(ACCOUNT, forceDiscovery = false),
        )

        assertEquals(listOf(journal), result.journals.map { it.payload })
        assertEquals(listOf(checkpoint.checkpointId), result.checkpoints.map {
            it.envelope.payload.checkpointId
        })
        assertEquals(checkpointBlogId, store.load("checkpoint:${checkpoint.checkpointId}")?.blogId)
        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(3, provider.fetchBlogCalls)

        assertIs<AppSyncJournalLoadResult.Success>(
            journalRemote.loadJournals(ACCOUNT, forceDiscovery = false),
        )

        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(
            4,
            provider.fetchBlogCalls,
            "The second pull should reload only the index when referenced fingerprints are unchanged",
        )
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
            fields = buildMap {
                put("type", "string")
                repeat(8_000) { index ->
                    put("field-$index", stableAppSyncFingerprint("value-$index"))
                }
            },
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
    fun verifiedPostUsesSubmittedEnvelopeWithoutReaderReload() = runBlocking {
        val requested = payload()
        val blogId = BlogId(20)
        val provider = FakeProvider().apply {
            pages[PageKey(null, 1)] = success(classPage())
            submitResult = success(
                AppSyncPostAcknowledgement("操作成功", listOf(blogId)),
            )
        }
        val store = FakeRemoteStore()

        val result = remote(provider, store).publishOwnJournal(
            payload = requested,
            expectedFingerprint = null,
            formHash = FORM_HASH,
        )

        val verified = assertIs<AppSyncJournalPublishResult.Verified>(result)
        assertEquals(requested, verified.journal.payload)
        assertEquals(blogId, store.load(requested.replicaKey())?.blogId)
        assertEquals(2, provider.submitCalls)
        assertEquals(0, provider.fetchBlogCalls)
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
            .apply { saveClassId(ACCOUNT, CLASS_ID) }
        val provider = FakeProvider().apply {
            blogs[BlogId(40)] = AppSyncCloudResult.NetworkFailed("offline")
            pages[PageKey(CLASS_ID, 1)] = AppSyncCloudResult.NetworkFailed("offline")
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
                    journalCodec.encode(payload()).replace("schema=2", "schema=99"),
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

    @Test
    fun checkpointRetentionUsesVerifiedCacheAndKeepsOnlyNewestThree() = runBlocking {
        val checkpoints = (1..5).map { index ->
            val blogId = BlogId(70 + index)
            StoredAppSyncRemoteBlog(
                remoteKey = "checkpoint:checkpoint-$index",
                kind = AppSyncRemoteBlogKind.Checkpoint,
                blogId = blogId,
                classId = CLASS_ID,
                fingerprint = "fingerprint-$index",
                validatedAtEpochMillis = index * 10L,
                contentUpdatedAtEpochMillis = index * 100L,
            )
        }
        val provider = FakeProvider().apply {
            deleteHandler = { request ->
                success(AppSyncPostAcknowledgement("操作成功", listOf(request.blogId)))
            }
        }
        val store = FakeRemoteStore(*checkpoints.toTypedArray())
        val retentionRemote = remote(provider, store)
        val result = assertIs<AppSyncCheckpointRetentionResult.Verified>(
            retentionRemote.enforceCheckpointRetention(
                ACCOUNT,
                FORM_HASH,
                maximumCheckpoints = 3,
            ),
        )

        assertEquals(setOf("checkpoint-3", "checkpoint-4", "checkpoint-5"), result.retainedCheckpointIds)
        assertEquals(2, result.deletedBlogCount)
        assertEquals(
            checkpoints.take(2).map { it.blogId },
            provider.deleteRequests.map { it.blogId },
        )
        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(0, provider.fetchBlogCalls)
        assertEquals(null, store.load("checkpoint:checkpoint-1"))
        assertEquals(null, store.load("checkpoint:checkpoint-2"))

        assertIs<AppSyncCheckpointRetentionResult.NotNeeded>(
            retentionRemote.enforceCheckpointRetention(
                ACCOUNT,
                FORM_HASH,
                maximumCheckpoints = 3,
            ),
        )
        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(0, provider.fetchBlogCalls)
    }

    @Test
    fun checkpointRetentionLeavesCacheForRetryWhenDeleteFails() = runBlocking {
        val checkpoints = (1..4).map { index ->
            StoredAppSyncRemoteBlog(
                remoteKey = "checkpoint:retry-$index",
                kind = AppSyncRemoteBlogKind.Checkpoint,
                blogId = BlogId(80 + index),
                classId = CLASS_ID,
                fingerprint = "fingerprint-$index",
                validatedAtEpochMillis = index.toLong(),
                contentUpdatedAtEpochMillis = index.toLong(),
            )
        }
        val provider = FakeProvider().apply {
            deleteHandler = { AppSyncCloudResult.NetworkFailed("delete offline") }
        }
        val store = FakeRemoteStore(*checkpoints.toTypedArray())

        assertIs<AppSyncCheckpointRetentionResult.RetryableFailure>(
            remote(provider, store).enforceCheckpointRetention(
                ACCOUNT,
                FORM_HASH,
                maximumCheckpoints = 3,
            ),
        )
        assertEquals(listOf(checkpoints.first().blogId), provider.deleteRequests.map { it.blogId })
        assertEquals(0, provider.fetchBlogCalls)
        assertEquals(checkpoints.first(), store.load(checkpoints.first().remoteKey))
    }

    @Test
    fun checkpointRetentionPinsRetirementBaseAndReportsStoragePressure() = runBlocking {
        val checkpoints = (1..5).map { index ->
            StoredAppSyncRemoteBlog(
                remoteKey = "checkpoint:checkpoint-$index",
                kind = AppSyncRemoteBlogKind.Checkpoint,
                blogId = BlogId(90 + index),
                classId = CLASS_ID,
                fingerprint = "fingerprint-$index",
                validatedAtEpochMillis = index.toLong(),
                contentUpdatedAtEpochMillis = index.toLong(),
            )
        }
        val provider = FakeProvider().apply {
            deleteHandler = { request ->
                success(AppSyncPostAcknowledgement("操作成功", listOf(request.blogId)))
            }
        }
        val store = FakeRemoteStore(*checkpoints.toTypedArray())

        val result = assertIs<AppSyncCheckpointRetentionResult.StoragePressure>(
            remote(provider, store).enforceCheckpointRetention(
                ACCOUNT,
                FORM_HASH,
                maximumCheckpoints = 3,
                pinnedCheckpointIds = setOf("checkpoint-1"),
            ),
        )

        assertEquals(
            setOf("checkpoint-1", "checkpoint-3", "checkpoint-4", "checkpoint-5"),
            result.retainedCheckpointIds,
        )
        assertEquals(listOf(checkpoints[1].blogId), provider.deleteRequests.map { it.blogId })
        assertEquals(checkpoints.first(), store.load(checkpoints.first().remoteKey))
    }

    private fun remote(
        provider: FakeProvider,
        store: FakeRemoteStore,
    ) = YamiboAppSyncJournalRemote(
        provider = provider,
        store = store,
        nowMillis = { 1_000 },
    )

    private fun retirementIntent(
        replicaKey: String = "device:epoch",
        blogId: Long = 42,
        fingerprint: String = "fingerprint",
        sequence: Long = 5,
    ) = AppSyncJournalRetirementIntent(
        accountBinding = ACCOUNT,
        replicaKey = replicaKey,
        sourceBlogId = blogId,
        fingerprint = fingerprint,
        publishedThroughSequence = sequence,
        checkpointId = "checkpoint",
        checkpointFingerprint = "checkpoint-fingerprint",
        checkpointVectorHash = "vector",
        activeSetHash = "active",
        stage = AppSyncJournalRetirementStage.IntentRecorded,
        attempts = 0,
        lastResultCode = null,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
        completedAtEpochMillis = null,
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

    private fun summary(blogId: BlogId, title: String, epoch: Long = 1) = BlogSummary(
        title = title,
        bId = blogId,
        url = "home.php?do=blog&id=${blogId.value}",
        description = "",
        author = USER,
        timeInfo = TimeInfo("2026-01-01 00:00", epoch = epoch),
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
        private val classIds = mutableMapOf<SyncAccountBinding, BlogClassId>()

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

        override fun loadClassId(accountBinding: SyncAccountBinding): BlogClassId? =
            classIds[accountBinding]

        override fun saveClassId(
            accountBinding: SyncAccountBinding,
            classId: BlogClassId,
        ) {
            classIds[accountBinding] = classId
        }
    }

    private companion object {
        val ACCOUNT = SyncAccountBinding("account")
        val CLASS_ID = BlogClassId(4568)
        val FORM_HASH = FormHash("hash")
        val USER = User(UserId(1), "owner")
    }
}
