package me.thenano.yamibo.yamibo_app.repository.appsync

import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.PageNav
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBlogConfig
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudNotice
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogClassSelection
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCloudEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCloudEnvelopeValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncDiscuzResponseParser
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.DefaultAppSyncCloudConfigClient
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncBlogConfigStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncBlogConfig

class AppSyncCloudConfigClientTest {
    private val codec = AppSyncCloudEnvelopeCodec()

    @Test
    fun configModelsKeepYamiboApiIdsTypeSafe() {
        val blogId = BlogId(1)
        val classId = BlogClassId(2)

        assertEquals(classId, AppSyncBlogConfig(blogId = blogId, classId = classId).classId)
        assertEquals(
            classId,
            StoredAppSyncBlogConfig("config", blogId, classId).classId,
        )
    }

    @Test
    fun cloudEnvelopeRoundTripsReaderHtmlAndRejectsTampering() {
        val encoded = codec.encode("snapshot", 1234L)
        val valid = assertIs<AppSyncCloudEnvelopeValidation.Valid>(
            codec.validateReaderHtml(encoded.replace("\n", "<br>")),
        )
        assertEquals("snapshot", valid.envelope.encodedSnapshot)
        assertEquals(1234L, valid.envelope.updatedAtEpochMillis)

        val tampered = encoded.replace(
            Regex("""fingerprint=[0-9a-f]+"""),
            "fingerprint=0000000000000000",
        )
        val invalid = assertIs<AppSyncCloudEnvelopeValidation.Invalid>(
            codec.validateReaderHtml(tampered.replace("\n", "<br>")),
        )
        assertTrue(invalid.markerPresent)
    }

    @Test
    fun discuzParserExtractsMessageAndBlogIdHintFromSuccessCdata() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <root><![CDATA[
              <div id="messagetext"><p>
                操作成功
                <script>succeedhandle_blog('home.php?mod=space&do=blog&id=42')</script>
              </p></div>
            ]]></root>
        """.trimIndent()

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            AppSyncDiscuzResponseParser.parse(200, body),
        )
        assertEquals("操作成功", result.value.messageText)
        assertEquals(listOf(BlogId(42)), result.value.candidateBlogIds)
    }

    @Test
    fun discuzParserCollectsRedirectIdentityHintWithoutTrustingItAsSuccessState() {
        val body = """
            <div id="messagetext"><p>
              操作成功<script>succeedhandle_blog()</script>
            </p></div>
        """.trimIndent()

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            AppSyncDiscuzResponseParser.parse(
                statusCode = 200,
                body = body,
                identityHintSources = listOf(
                    "https://bbs.yamibo.com/home.php?mod=space&do=blog&id=43",
                ),
            ),
        )

        assertEquals(listOf(BlogId(43)), result.value.candidateBlogIds)
    }

    @Test
    fun discuzParserClassifiesErrorMessageBeforeGenericHttpFailure() {
        val noPermission = """
            <root><![CDATA[
              <div id="messagetext"><p>No permission to publish this blog<script>errorhandle()</script></p></div>
            ]]></root>
        """.trimIndent()
        assertIs<AppSyncCloudResult.NoPermission>(
            AppSyncDiscuzResponseParser.parse(200, noPermission),
        )

        val staleForm = """
            <div class="jump_c"><p>formhash expired, please refresh the form</p></div>
        """.trimIndent()
        assertIs<AppSyncCloudResult.FormExpired>(
            AppSyncDiscuzResponseParser.parse(200, staleForm),
        )

        assertIs<AppSyncCloudResult.NotLoggedIn>(
            AppSyncDiscuzResponseParser.parse(
                503,
                "Illegal request: please login again",
            ),
        )
    }

    @Test
    fun discuzParserRecognizesCapturedChinesePromptMessages() {
        val success = """
            <div id="messagetext"><p>
              操作成功
              <script>setTimeout("location.href='home.php?do=blog&id=44'", 1000)</script>
            </p></div>
        """.trimIndent()
        val verified = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            AppSyncDiscuzResponseParser.parse(200, success),
        )
        assertEquals("操作成功", verified.value.messageText)
        assertEquals(listOf(BlogId(44)), verified.value.candidateBlogIds)

        val staleForm = """
            <div id="messagetext"><p>表單驗證串不符，無法提交。</p></div>
        """.trimIndent()
        assertIs<AppSyncCloudResult.FormExpired>(
            AppSyncDiscuzResponseParser.parse(200, staleForm),
        )

        val denied = """
            <div id="messagetext"><p>沒有權限進行此操作。</p></div>
        """.trimIndent()
        assertIs<AppSyncCloudResult.NoPermission>(
            AppSyncDiscuzResponseParser.parse(200, denied),
        )

        assertIs<AppSyncCloudResult.Maintenance>(
            AppSyncDiscuzResponseParser.parse(200, "<p>網站維護中</p>"),
        )
    }

    @Test
    fun diagnosticPreviewRedactsConfigPayloadFormHashAndUserId() {
        val body = """
            [${AppSyncCloudConfigDefaults.MARKER}:BEGIN]
            secret-config-payload
            [${AppSyncCloudConfigDefaults.MARKER}:END]
            formhash=secret-hash&uid=123
        """.trimIndent()

        val preview = AppSyncDiscuzResponseParser.safeBodyPreview(body).orEmpty()

        assertTrue(!preview.contains("secret-config-payload"))
        assertTrue(!preview.contains("secret-hash"))
        assertTrue(!preview.contains("uid=123"))
    }

    @Test
    fun localMetadataFastPathVerifiesReaderBeforeRefreshingStore() = runBlocking {
        val blogId = BlogId(7)
        val classId = BlogClassId(9)
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("local", 200L)))
        }
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId = blogId,
                classId = classId,
            ),
        )
        val client = client(provider, store)

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.findBlogConfig(FORM_HASH),
        )

        assertEquals(blogId, result.value.blogId)
        assertEquals(classId, result.value.classId)
        assertEquals(200L, result.value.cloudContentUpdatedAtEpochMillis)
        assertEquals(1, provider.fetchBlogCalls)
        assertEquals(0, provider.fetchBlogListCalls)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun repositoryPreflightInspectionDoesNotRefreshOrClearMetadata() = runBlocking {
        val blogId = BlogId(8)
        val original = StoredAppSyncBlogConfig(
            blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId = blogId,
            classId = BlogClassId(9),
            validatedAtEpochMillis = 10L,
            schemaVersion = 1,
            fingerprint = "old",
        )
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("local", 200L)))
        }
        val store = FakeConfigStore(original)

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client(provider, store).inspectBlogConfig(),
        )

        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun repositoryPreflightInspectionPersistsFirstDiscoveryForFutureFastPath() = runBlocking {
        val classId = BlogClassId(10)
        val blogId = BlogId(9)
        val provider = providerWithSingleCandidate(
            classId = classId,
            blogId = blogId,
            body = validBody("cloud", 200L),
        )
        val store = FakeConfigStore()
        val client = client(provider, store)

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.inspectBlogConfig(),
        )
        val discoveryListCalls = provider.fetchBlogListCalls

        assertEquals(blogId, store.value?.blogId)
        assertEquals(classId, store.value?.classId)
        assertEquals(1, store.saveCount)

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.inspectBlogConfig(),
        )

        assertEquals(discoveryListCalls, provider.fetchBlogListCalls)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun repositoryPreflightInspectionPersistsResolvedClassIdOnce() = runBlocking {
        val classId = BlogClassId(12)
        val blogId = BlogId(11)
        val provider = providerWithSingleCandidate(
            classId = classId,
            blogId = blogId,
            body = validBody("cloud", 200L),
        )
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId = blogId,
            ),
        )
        val client = client(provider, store)

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.inspectBlogConfig(),
        )
        val classResolutionListCalls = provider.fetchBlogListCalls

        assertEquals(classId, store.value?.classId)
        assertEquals(1, store.saveCount)

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.inspectBlogConfig(),
        )

        assertEquals(classResolutionListCalls, provider.fetchBlogListCalls)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun discoveryUsesClassPrefixPaginationAndSelectsNewestValidCandidate() = runBlocking {
        val classId = BlogClassId(22)
        val oldId = BlogId(10)
        val newId = BlogId(11)
        val provider = FakeBlogProvider().apply {
            pageResults[PageKey(null, 1)] = success(
                UserSpaceBlogPage(
                    blogs = emptyList(),
                    blogClasses = listOf(
                        BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                    ),
                ),
            )
            pageResults[PageKey(classId, 1)] = success(
                blogListPage(
                    blogs = listOf(summary(oldId, 100L)),
                    nextPage = 2,
                ),
            )
            pageResults[PageKey(classId, 2)] = success(
                blogListPage(blogs = listOf(summary(newId, 200L))),
            )
            blogs[oldId] = success(blogPage(oldId, validBody("old", 1_000L)))
            blogs[newId] = success(blogPage(newId, validBody("new", 2_000L)))
        }
        val store = FakeConfigStore()
        val notices = mutableListOf<AppSyncCloudNotice>()
        val client = client(provider, store, notices::add)

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client.findBlogConfig(FORM_HASH),
        )

        assertEquals(newId, result.value.blogId)
        assertEquals(classId, result.value.classId)
        assertEquals(newId, store.value?.blogId)
        val notice = assertIs<AppSyncCloudNotice.DuplicateValidBlogs>(notices.single())
        assertEquals(newId, notice.selectedBlogId)
        assertEquals(2, notice.candidateCount)
    }

    @Test
    fun requestFailureLeavesTrustedMetadataByteForByteUnchanged() = runBlocking {
        val original = StoredAppSyncBlogConfig(
            blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId = BlogId(31),
            classId = BlogClassId(32),
            validatedAtEpochMillis = 33L,
            schemaVersion = 1,
            fingerprint = "trusted",
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogs[original.blogId] = AppSyncCloudResult.NetworkFailed("offline")
        }

        val result = client(provider, store).findBlogConfig(FORM_HASH)

        assertIs<AppSyncCloudResult.NetworkFailed>(result)
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
        assertTrue(provider.deleteRequests.isEmpty())
    }

    @Test
    fun staleLocalIdIsNotClearedWhenFollowUpDiscoveryFails() = runBlocking {
        val original = StoredAppSyncBlogConfig(
            blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId = BlogId(33),
            classId = BlogClassId(34),
            validatedAtEpochMillis = 35L,
            schemaVersion = 1,
            fingerprint = "trusted",
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogs[original.blogId] = AppSyncCloudResult.NotFound
            pageResults[PageKey(null, 1)] = AppSyncCloudResult.Timeout("discovery timeout")
        }

        assertIs<AppSyncCloudResult.Timeout>(
            client(provider, store).findBlogConfig(FORM_HASH),
        )
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
        assertTrue(provider.deleteRequests.isEmpty())
    }

    @Test
    fun everyNonAuthoritativeReaderFailurePreservesTrustedMetadata() = runBlocking {
        val original = StoredAppSyncBlogConfig(
            blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId = BlogId(35),
            classId = BlogClassId(36),
            validatedAtEpochMillis = 37L,
            schemaVersion = 1,
            fingerprint = "trusted",
        )
        val failures: List<AppSyncCloudResult<BlogPage>> = listOf(
            AppSyncCloudResult.AcknowledgedButUnverified(null, "unverified"),
            AppSyncCloudResult.NotLoggedIn,
            AppSyncCloudResult.NoPermission("denied"),
            AppSyncCloudResult.Maintenance,
            AppSyncCloudResult.FormExpired("expired"),
            AppSyncCloudResult.Conflict("changed"),
            AppSyncCloudResult.HttpFailed(500, "failed", "preview"),
            AppSyncCloudResult.NetworkFailed("offline"),
            AppSyncCloudResult.Timeout("timeout"),
            AppSyncCloudResult.ParseFailed("layout changed"),
            AppSyncCloudResult.UnknownFailed("unknown"),
        )

        failures.forEach { remoteFailure ->
            val store = FakeConfigStore(original)
            val provider = FakeBlogProvider().apply {
                blogs[original.blogId] = remoteFailure
            }

            client(provider, store).findBlogConfig(FORM_HASH)

            assertEquals(original, store.value, remoteFailure.toString())
            assertEquals(0, store.saveCount, remoteFailure.toString())
            assertEquals(0, store.clearCount, remoteFailure.toString())
            assertTrue(provider.deleteRequests.isEmpty(), remoteFailure.toString())
        }
    }

    @Test
    fun sameTitleWithoutMarkerIsNeverDeleted() = runBlocking {
        val classId = BlogClassId(40)
        val blogId = BlogId(41)
        val provider = providerWithSingleCandidate(classId, blogId, "ordinary user content")
        val store = FakeConfigStore()

        val result = client(provider, store).findBlogConfig(FORM_HASH)

        val failure = assertIs<AppSyncCloudResult.ValidationFailed>(result)
        assertEquals(false, failure.markerPresent)
        assertTrue(provider.deleteRequests.isEmpty())
        assertNull(store.value)
    }

    @Test
    fun sameTitleCollisionDoesNotClearExistingMetadata() = runBlocking {
        val classId = BlogClassId(40)
        val collisionId = BlogId(41)
        val original = StoredAppSyncBlogConfig(
            blogName = AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId = BlogId(42),
            classId = classId,
            validatedAtEpochMillis = 10L,
            schemaVersion = 1,
            fingerprint = "trusted",
        )
        val provider = providerWithSingleCandidate(
            classId,
            collisionId,
            "ordinary user content",
        ).apply {
            blogs[original.blogId] = AppSyncCloudResult.NotFound
        }
        val store = FakeConfigStore(original)

        assertIs<AppSyncCloudResult.ValidationFailed>(
            client(provider, store).findBlogConfig(FORM_HASH),
        )

        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
        assertTrue(provider.deleteRequests.isEmpty())
    }

    @Test
    fun markerProvenDamagedBlogIsDeletedAndUserNoticeIsEmitted() = runBlocking {
        val classId = BlogClassId(50)
        val blogId = BlogId(51)
        val damaged = validBody("broken", 100L).replace(
            Regex("""fingerprint=[0-9a-f]+"""),
            "fingerprint=ffffffffffffffff",
        )
        val provider = providerWithSingleCandidate(classId, blogId, damaged).apply {
            deleteHandler = { request ->
                blogs[request.blogId] = AppSyncCloudResult.NotFound
                success(AppSyncPostAcknowledgement("操作成功", emptyList()))
            }
        }
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId,
                classId,
            ),
        )
        val notices = mutableListOf<AppSyncCloudNotice>()

        val result = client(provider, store, notices::add).findBlogConfig(FORM_HASH)

        assertIs<AppSyncCloudResult.NotFound>(result)
        assertEquals(listOf(blogId), provider.deleteRequests.map { it.blogId })
        assertNull(store.value)
        assertEquals(1, store.clearCount)
        assertIs<AppSyncCloudNotice.DamagedBlogDeleted>(notices.single())
        Unit
    }

    @Test
    fun nonMutatingPreflightDoesNotDeleteMarkerProvenDamagedBlog() = runBlocking {
        val classId = BlogClassId(55)
        val blogId = BlogId(56)
        val damaged = validBody("broken", 100L).replace(
            Regex("""fingerprint=[0-9a-f]+"""),
            "fingerprint=ffffffffffffffff",
        )
        val provider = providerWithSingleCandidate(classId, blogId, damaged)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId,
            classId,
            fingerprint = "old",
        )
        val store = FakeConfigStore(original)

        assertIs<AppSyncCloudResult.NotFound>(
            client(provider, store).inspectBlogConfig(),
        )

        assertTrue(provider.deleteRequests.isEmpty())
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun createUsesNewClassThenPersistsOnlyAfterAuthoritativeDiscovery() = runBlocking {
        val classId = BlogClassId(61)
        val blogId = BlogId(62)
        val provider = FakeBlogProvider().apply {
            pageResults[PageKey(null, 1)] = success(UserSpaceBlogPage(emptyList()))
            submitHandler = { request ->
                val selection = assertIs<AppSyncBlogClassSelection.Create>(request.classSelection)
                assertEquals(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, selection.className)
                pageResults[PageKey(null, 1)] = success(
                    UserSpaceBlogPage(
                        blogs = emptyList(),
                        blogClasses = listOf(
                            BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                        ),
                    ),
                )
                pageResults[PageKey(classId, 1)] = success(
                    blogListPage(listOf(summary(blogId, 500L))),
                )
                blogs[blogId] = success(blogPage(blogId, request.message))
                success(AppSyncPostAcknowledgement("操作成功", listOf(blogId)))
            }
        }
        val store = FakeConfigStore()

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client(provider, store, now = 900L).createBlogConfig("snapshot", FORM_HASH),
        )

        assertEquals(blogId, result.value.blogId)
        assertEquals(classId, result.value.classId)
        assertEquals(blogId, store.value?.blogId)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun acknowledgedCreateWithReloadFailureDoesNotChangeMetadata() = runBlocking {
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            BlogId(70),
            BlogClassId(71),
            fingerprint = "old",
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            pageResults[PageKey(null, 1)] = success(UserSpaceBlogPage(emptyList()))
            submitHandler = {
                pageResults[PageKey(null, 1)] = AppSyncCloudResult.Timeout("reload timeout")
                success(AppSyncPostAcknowledgement("操作成功", listOf(BlogId(72))))
            }
        }

        val result = client(provider, store).createBlogConfig("new", FORM_HASH)

        assertIs<AppSyncCloudResult.AcknowledgedButUnverified>(result)
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun updateRequiresReloadedExpectedFingerprintBeforeSaving() = runBlocking {
        val classId = BlogClassId(80)
        val blogId = BlogId(81)
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId,
                classId,
            ),
        )
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("old", 1L)))
            submitHandler = { request ->
                blogs[blogId] = success(blogPage(blogId, request.message))
                success(AppSyncPostAcknowledgement("操作成功", listOf(blogId)))
            }
        }

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client(provider, store, now = 2_000L).updateBlogConfig(
                blogId,
                "new",
                FORM_HASH,
            ),
        )

        assertEquals(blogId, result.value.blogId)
        assertEquals(codec.fingerprintFor("new", 2_000L), store.value?.fingerprint)
    }

    @Test
    fun verifiedRemoteUpdateWithAtomicStoreExceptionKeepsPriorMetadataRecoverable() = runBlocking {
        val classId = BlogClassId(82)
        val blogId = BlogId(83)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId,
            classId,
            fingerprint = "old",
        )
        val store = FakeConfigStore(original).apply {
            saveFailure = IllegalStateException("injected store failure")
        }
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("old", 1L)))
            submitHandler = { request ->
                blogs[blogId] = success(blogPage(blogId, request.message))
                success(AppSyncPostAcknowledgement("操作成功", listOf(blogId)))
            }
        }

        assertIs<AppSyncCloudResult.UnknownFailed>(
            client(provider, store, now = 2_000L).updateBlogConfig(
                blogId,
                "new",
                FORM_HASH,
            ),
        )
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun updateReturnsConflictWhenRemoteFingerprintChangesBeforePost() = runBlocking {
        val classId = BlogClassId(86)
        val blogId = BlogId(89)
        val oldFingerprint = codec.fingerprintFor("old", 1L)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId,
            classId,
            fingerprint = oldFingerprint,
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogResultSequences[blogId] = mutableListOf(
                success(blogPage(blogId, validBody("old", 1L))),
                success(blogPage(blogId, validBody("other-device", 2L))),
            )
        }

        assertIs<AppSyncCloudResult.Conflict>(
            client(provider, store).updateBlogConfig(
                blogId = blogId,
                encodedText = "local",
                formHash = FORM_HASH,
                expectedRemoteFingerprint = oldFingerprint,
            ),
        )
        assertTrue(provider.submitRequests.isEmpty())
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun updateResolvesMissingClassIdFromAuthoritativeClassList() = runBlocking {
        val classId = BlogClassId(84)
        val blogId = BlogId(85)
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId,
                classId = null,
            ),
        )
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("old", 1L)))
            pageResults[PageKey(null, 1)] = success(
                UserSpaceBlogPage(
                    blogs = emptyList(),
                    blogClasses = listOf(
                        BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                    ),
                ),
            )
            pageResults[PageKey(classId, 1)] = success(
                blogListPage(listOf(summary(blogId, 2L))),
            )
            submitHandler = { request ->
                assertEquals(
                    classId,
                    assertIs<AppSyncBlogClassSelection.Existing>(request.classSelection).classId,
                )
                blogs[blogId] = success(blogPage(blogId, request.message))
                success(AppSyncPostAcknowledgement("操作成功", listOf(blogId)))
            }
        }

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig>>(
            client(provider, store, now = 3_000L).updateBlogConfig(
                blogId,
                "new",
                FORM_HASH,
            ),
        )

        assertEquals(classId, result.value.classId)
        assertEquals(classId, store.value?.classId)
    }

    @Test
    fun directLoadResolvesAndPersistsMissingClassIdBeforeSuccess() = runBlocking {
        val classId = BlogClassId(87)
        val blogId = BlogId(88)
        val store = FakeConfigStore()
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("cloud", 4L)))
            pageResults[PageKey(null, 1)] = success(
                UserSpaceBlogPage(
                    blogs = emptyList(),
                    blogClasses = listOf(
                        BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                    ),
                ),
            )
            pageResults[PageKey(classId, 1)] = success(
                blogListPage(listOf(summary(blogId, 4L))),
            )
        }

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<String>>(
            client(provider, store).loadBlogConfig(blogId),
        )

        assertEquals("cloud", result.value)
        assertEquals(classId, store.value?.classId)
        assertEquals(blogId, store.value?.blogId)
    }

    @Test
    fun readOnlyLoadResolvesClassIdWithoutChangingMetadata() = runBlocking {
        val classId = BlogClassId(188)
        val blogId = BlogId(189)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            BlogId(190),
            BlogClassId(191),
            fingerprint = "original",
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("cloud", 4L)))
            pageResults[PageKey(null, 1)] = success(
                UserSpaceBlogPage(
                    blogs = emptyList(),
                    blogClasses = listOf(
                        BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                    ),
                ),
            )
            pageResults[PageKey(classId, 1)] = success(
                blogListPage(listOf(summary(blogId, 4L))),
            )
        }

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<*>>(
            client(provider, store).loadBlogConfigReadOnly(blogId),
        )
        val staged = assertIs<me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncStagedCloudConfig>(
            result.value,
        )

        assertEquals("cloud", staged.encodedSnapshot)
        assertEquals(classId, staged.config.classId)
        assertEquals(original, store.value)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun deleteAcknowledgementClearsMetadataWithoutImmediateAbsenceCheck() = runBlocking {
        val classId = BlogClassId(90)
        val blogId = BlogId(91)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId,
            classId,
            fingerprint = "trusted",
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("current", 10L)))
            deleteHandler = {
                success(AppSyncPostAcknowledgement("操作成功", emptyList()))
            }
        }

        val result = client(provider, store).deleteBlogConfig(blogId, FORM_HASH)

        assertIs<AppSyncCloudResult.VerifiedSuccess<Unit>>(result)
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun deleteClearsMatchingMetadataAfterVerifiedPostResult() = runBlocking {
        val blogId = BlogId(101)
        val store = FakeConfigStore(
            StoredAppSyncBlogConfig(
                AppSyncCloudConfigDefaults.BLOG_NAME,
                blogId,
                BlogClassId(102),
            ),
        )
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("current", 10L)))
            deleteHandler = { request ->
                blogs[request.blogId] = AppSyncCloudResult.NotFound
                success(AppSyncPostAcknowledgement("操作成功", emptyList()))
            }
        }

        val result = client(provider, store).deleteBlogConfig(blogId, FORM_HASH)

        assertIs<AppSyncCloudResult.VerifiedSuccess<Unit>>(result)
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun deleteDoesNotDependOnStaleReaderOrBlogListAfterPostSuccess() = runBlocking {
        val blogId = BlogId(111)
        val original = StoredAppSyncBlogConfig(
            AppSyncCloudConfigDefaults.BLOG_NAME,
            blogId,
            BlogClassId(112),
        )
        val store = FakeConfigStore(original)
        val provider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("current", 10L)))
            pageResults[PageKey(null, 1)] = success(UserSpaceBlogPage(emptyList()))
            deleteHandler = { request ->
                blogs[request.blogId] = AppSyncCloudResult.ParseFailed("deleted prompt layout")
                success(AppSyncPostAcknowledgement("操作成功", emptyList()))
            }
        }

        assertIs<AppSyncCloudResult.VerifiedSuccess<Unit>>(
            client(provider, store).deleteBlogConfig(blogId, FORM_HASH),
        )
        assertNull(store.value)

        val failedStore = FakeConfigStore(original)
        val failedProvider = FakeBlogProvider().apply {
            blogs[blogId] = success(blogPage(blogId, validBody("current", 10L)))
            pageResults[PageKey(null, 1)] = AppSyncCloudResult.NetworkFailed("list offline")
            deleteHandler = { request ->
                blogs[request.blogId] = AppSyncCloudResult.ParseFailed("deleted prompt layout")
                success(AppSyncPostAcknowledgement("操作成功", emptyList()))
            }
        }

        assertIs<AppSyncCloudResult.VerifiedSuccess<Unit>>(
            client(failedProvider, failedStore).deleteBlogConfig(blogId, FORM_HASH),
        )
        assertNull(failedStore.value)
        assertEquals(1, failedStore.clearCount)
    }

    private fun client(
        provider: FakeBlogProvider,
        store: FakeConfigStore,
        notice: (AppSyncCloudNotice) -> Unit = {},
        now: Long = 1_000L,
    ): DefaultAppSyncCloudConfigClient = DefaultAppSyncCloudConfigClient(
        provider = provider,
        store = store,
        noticeSink = notice,
        envelopeCodec = codec,
        nowMillis = { now },
    )

    private fun providerWithSingleCandidate(
        classId: BlogClassId,
        blogId: BlogId,
        body: String,
    ): FakeBlogProvider = FakeBlogProvider().apply {
        pageResults[PageKey(null, 1)] = success(
            UserSpaceBlogPage(
                blogs = emptyList(),
                blogClasses = listOf(
                    BlogPageClassInfo(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, classId),
                ),
            ),
        )
        pageResults[PageKey(classId, 1)] = success(
            blogListPage(listOf(summary(blogId, 100L))),
        )
        blogs[blogId] = success(blogPage(blogId, body))
    }

    private fun validBody(snapshot: String, updatedAt: Long): String =
        codec.encode(snapshot, updatedAt).replace("\n", "<br>")

    private fun blogPage(
        blogId: BlogId,
        contentHtml: String,
        title: String = AppSyncCloudConfigDefaults.BLOG_NAME,
    ): BlogPage = BlogPage(
        blogInfo = BlogInfo(blogId = blogId, title = title),
        rootBlog = BlogComment(
            author = TEST_USER,
            contentHtml = contentHtml,
            timeInfo = TimeInfo("2026-01-01 00:00", epoch = 1L),
        ),
        blogComments = emptyList(),
    )

    private fun summary(blogId: BlogId, epoch: Long): BlogSummary = BlogSummary(
        title = "[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] ${AppSyncCloudConfigDefaults.BLOG_NAME}",
        bId = blogId,
        url = "home.php?do=blog&id=${blogId.value}",
        description = "",
        author = TEST_USER,
        timeInfo = TimeInfo("2026-01-01 00:00", epoch = epoch),
    )

    private fun blogListPage(
        blogs: List<BlogSummary>,
        nextPage: Int? = null,
    ): UserSpaceBlogPage = UserSpaceBlogPage(
        blogs = blogs,
        pageNav = nextPage?.let {
            PageNav(
                nextUrl = "home.php?page=$it",
                nextPageIndex = it,
                currentPage = it - 1,
                totalPages = it,
            )
        },
    )

    private fun <T> success(value: T): AppSyncCloudResult<T> =
        AppSyncCloudResult.VerifiedSuccess(value)

    private data class PageKey(
        val classId: BlogClassId?,
        val page: Int,
    )

    private class FakeConfigStore(
        initial: StoredAppSyncBlogConfig? = null,
    ) : AppSyncBlogConfigStore {
        var value: StoredAppSyncBlogConfig? = initial
        var saveCount: Int = 0
        var clearCount: Int = 0
        var saveFailure: Throwable? = null

        override fun load(): StoredAppSyncBlogConfig? = value

        override fun save(config: StoredAppSyncBlogConfig) {
            saveFailure?.let { throw it }
            value = config
            saveCount += 1
        }

        override fun clear() {
            value = null
            clearCount += 1
        }
    }

    private class FakeBlogProvider : AppSyncBlogProvider {
        val pageResults = mutableMapOf<PageKey, AppSyncCloudResult<UserSpaceBlogPage>>()
        val blogs = mutableMapOf<BlogId, AppSyncCloudResult<BlogPage>>()
        val blogResultSequences =
            mutableMapOf<BlogId, MutableList<AppSyncCloudResult<BlogPage>>>()
        val submitRequests = mutableListOf<AppSyncBlogWriteRequest>()
        val deleteRequests = mutableListOf<AppSyncBlogDeleteRequest>()
        var submitHandler:
            suspend (AppSyncBlogWriteRequest) -> AppSyncCloudResult<AppSyncPostAcknowledgement> = {
                AppSyncCloudResult.ParseFailed("No submit result configured")
            }
        var deleteHandler:
            suspend (AppSyncBlogDeleteRequest) -> AppSyncCloudResult<AppSyncPostAcknowledgement> = {
                AppSyncCloudResult.ParseFailed("No delete result configured")
            }
        var fetchBlogCalls: Int = 0
        var fetchBlogListCalls: Int = 0

        override suspend fun fetchMyBlogs(
            blogClassId: BlogClassId?,
            page: Int,
        ): AppSyncCloudResult<UserSpaceBlogPage> {
            fetchBlogListCalls += 1
            return pageResults[PageKey(blogClassId, page)]
                ?: AppSyncCloudResult.NotFound
        }

        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
            fetchBlogCalls += 1
            blogResultSequences[blogId]
                ?.takeIf { it.isNotEmpty() }
                ?.removeAt(0)
                ?.let { return it }
            return blogs[blogId] ?: AppSyncCloudResult.NotFound
        }

        override suspend fun submitBlog(
            request: AppSyncBlogWriteRequest,
        ): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            submitRequests += request
            return submitHandler(request)
        }

        override suspend fun deleteBlog(
            request: AppSyncBlogDeleteRequest,
        ): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            deleteRequests += request
            return deleteHandler(request)
        }
    }

    companion object {
        private val TEST_USER = User(UserId(1), "owner")
        private val FORM_HASH = FormHash("hash")
    }
}
