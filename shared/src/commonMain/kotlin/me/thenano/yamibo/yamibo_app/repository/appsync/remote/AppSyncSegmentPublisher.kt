package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

internal sealed interface AppSyncSegmentPublishResult {
    data class ReadyToCommitIndex(
        val rootBlogId: Long,
        val rootFingerprint: String,
        val rootBody: String,
    ) : AppSyncSegmentPublishResult

    data object FormExpired : AppSyncSegmentPublishResult
    data class Retryable(val reason: String) : AppSyncSegmentPublishResult
    data class Terminal(val reason: String) : AppSyncSegmentPublishResult
}

/** Publishes immutable staging artifacts. Index commit remains the caller's explicit commit point. */
internal class AppSyncSegmentPublisher(
    private val provider: AppSyncBlogProvider,
    private val recoveryStore: SqlDelightAppSyncRecoveryStore,
    private val codec: AppSyncSegmentEnvelopeCodec = AppSyncSegmentEnvelopeCodec(),
    private val nowMillis: () -> Long,
    private val reconcileSegment: suspend (
        generationId: String,
        segmentIndex: Int,
        expectedFingerprint: String,
    ) -> BlogId? = { _, _, _ -> null },
    private val reconcileRoot: suspend (
        generationId: String,
        expectedFingerprint: String,
    ) -> BlogId? = { _, _ -> null },
) {
    suspend fun publish(
        sessionId: String,
        canonicalEnvelope: String,
        kind: AppSyncSegmentPayloadKind,
        identity: String,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ): AppSyncSegmentPublishResult {
        val session = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentPublishResult.Terminal("Recovery session is missing")
        if (session.phase !in setOf(
                AppSyncRecoveryPhase.PublishingSegments,
                AppSyncRecoveryPhase.PublishingRoot,
                AppSyncRecoveryPhase.CommittingIndex,
            )
        ) return AppSyncSegmentPublishResult.Terminal("Recovery phase cannot publish segments")

        val drafts = codec.split(
            canonicalEnvelope = canonicalEnvelope,
            accountBinding = session.accountBinding.value,
            kind = kind,
            identity = identity,
            generationId = session.generationId,
        )
        var nextBlogId: BlogId? = null
        for (index in drafts.indices.reversed()) {
            val draft = drafts[index]
            val body = codec.encodeSegment(
                codec.withNextBlogId(draft, nextBlogId?.value?.toString()),
            )
            val fingerprint = stableAppSyncFingerprint(body)
            val existing = recoveryStore.segmentWrites(sessionId)
                .singleOrNull { it.segmentIndex == index }
            if (existing?.blogId != null && existing.verifiedFingerprint == fingerprint) {
                nextBlogId = BlogId(existing.blogId.toInt())
                continue
            }
            recoveryStore.saveSegmentIntent(
                sessionId = sessionId,
                segmentIndex = index,
                segmentCount = drafts.size,
                expectedFingerprint = fingerprint,
                nextBlogId = nextBlogId?.value?.toLong(),
            )
            val submitted = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = null,
                    title = AppSyncJournalDefaults.segmentTitle(kind, session.generationId, index),
                    message = body,
                    classSelection = classSelection,
                    formHash = formHash,
                ),
            )
            val candidate = when (submitted) {
                is AppSyncCloudResult.VerifiedSuccess ->
                    submitted.value.candidateBlogIds.distinct().singleOrNull()
                        ?: reconcileSegment(session.generationId, index, fingerprint)
                is AppSyncCloudResult.AcknowledgedButUnverified,
                is AppSyncCloudResult.NetworkFailed,
                is AppSyncCloudResult.Timeout,
                -> reconcileSegment(session.generationId, index, fingerprint)
                is AppSyncCloudResult.FormExpired, AppSyncCloudResult.NotLoggedIn ->
                    return AppSyncSegmentPublishResult.FormExpired
                else -> return AppSyncSegmentPublishResult.Retryable(submitted.describeForJournal())
            } ?: return AppSyncSegmentPublishResult.Retryable(
                "Segment outcome is ambiguous; authoritative reconciliation is required",
            )
            when (val verified = verifySegment(candidate, body, kind, session.generationId, index)) {
                is Verification.Valid -> {
                    recoveryStore.markSegmentVerified(
                        sessionId, index, fingerprint, candidate.value.toLong(), nowMillis(),
                    )
                    nextBlogId = candidate
                }
                is Verification.Retryable -> return AppSyncSegmentPublishResult.Retryable(verified.reason)
                is Verification.Terminal -> return AppSyncSegmentPublishResult.Terminal(verified.reason)
            }
        }

        if (recoveryStore.session(sessionId)?.phase == AppSyncRecoveryPhase.PublishingSegments) {
            recoveryStore.transition(
                sessionId,
                AppSyncRecoveryPhase.PublishingSegments,
                AppSyncRecoveryPhase.PublishingRoot,
                nowMillis(),
            )
        }
        val headId = requireNotNull(nextBlogId)
        val root = codec.root(drafts, headId.value.toString(), canonicalEnvelope)
        val rootBody = codec.encodeRoot(root)
        val rootFingerprint = stableAppSyncFingerprint(rootBody)
        val current = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentPublishResult.Terminal("Recovery session disappeared")
        if (current.rootBlogId != null && current.rootFingerprint == rootFingerprint) {
            return AppSyncSegmentPublishResult.ReadyToCommitIndex(
                current.rootBlogId, rootFingerprint, rootBody,
            )
        }
        val submittedRoot = provider.submitBlog(
            AppSyncBlogWriteRequest(
                blogId = null,
                title = AppSyncJournalDefaults.rootTitle(kind, session.generationId),
                message = rootBody,
                classSelection = classSelection,
                formHash = formHash,
            ),
        )
        val rootId = when (submittedRoot) {
            is AppSyncCloudResult.VerifiedSuccess ->
                submittedRoot.value.candidateBlogIds.distinct().singleOrNull()
                    ?: reconcileRoot(session.generationId, rootFingerprint)
            is AppSyncCloudResult.AcknowledgedButUnverified,
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            -> reconcileRoot(session.generationId, rootFingerprint)
            is AppSyncCloudResult.FormExpired, AppSyncCloudResult.NotLoggedIn ->
                return AppSyncSegmentPublishResult.FormExpired
            else -> return AppSyncSegmentPublishResult.Retryable(submittedRoot.describeForJournal())
        } ?: return AppSyncSegmentPublishResult.Retryable(
            "Root outcome is ambiguous; authoritative reconciliation is required",
        )
        when (val verified = verifyRoot(rootId, rootBody, kind, session.generationId)) {
            is Verification.Valid -> Unit
            is Verification.Retryable -> return AppSyncSegmentPublishResult.Retryable(verified.reason)
            is Verification.Terminal -> return AppSyncSegmentPublishResult.Terminal(verified.reason)
        }
        recoveryStore.markRootVerified(sessionId, rootId.value.toLong(), rootFingerprint, nowMillis())
        return AppSyncSegmentPublishResult.ReadyToCommitIndex(
            rootId.value.toLong(), rootFingerprint, rootBody,
        )
    }

    private suspend fun verifySegment(
        blogId: BlogId,
        expectedBody: String,
        kind: AppSyncSegmentPayloadKind,
        generationId: String,
        index: Int,
    ): Verification {
        val expected = codec.decodeSegment(expectedBody).getOrElse {
            return Verification.Terminal("Expected segment payload is invalid")
        }
        return verify(blogId) { title, body ->
            title == AppSyncJournalDefaults.segmentTitle(kind, generationId, index) &&
                codec.decodeSegment(body).getOrNull() == expected
        }
    }

    private suspend fun verifyRoot(
        blogId: BlogId,
        expectedBody: String,
        kind: AppSyncSegmentPayloadKind,
        generationId: String,
    ): Verification {
        val expected = codec.decodeRoot(expectedBody).getOrElse {
            return Verification.Terminal("Expected root payload is invalid")
        }
        return verify(blogId) { title, body ->
            title == AppSyncJournalDefaults.rootTitle(kind, generationId) &&
                codec.decodeRoot(body).getOrNull() == expected
        }
    }

    private suspend fun verify(
        blogId: BlogId,
        validate: (String, String) -> Boolean,
    ): Verification = when (val loaded = provider.fetchBlog(blogId)) {
        is AppSyncCloudResult.VerifiedSuccess -> {
            val page = loaded.value
            val body = try {
                Ksoup.parseBodyFragment(page.rootBlog.contentHtml).body().text()
            } catch (_: Throwable) {
                page.rootBlog.contentHtml
            }
            if (page.blogInfo.blogId == blogId && validate(page.blogInfo.title, body)) {
                Verification.Valid
            } else {
                Verification.Terminal("Authoritative Blog does not match the staged payload")
            }
        }
        AppSyncCloudResult.NotFound -> Verification.Retryable("Staged Blog is not visible yet")
        is AppSyncCloudResult.NetworkFailed,
        is AppSyncCloudResult.Timeout,
        AppSyncCloudResult.Maintenance,
        -> Verification.Retryable(loaded.describeForJournal())
        else -> Verification.Terminal(loaded.describeForJournal())
    }

    private sealed interface Verification {
        data object Valid : Verification
        data class Retryable(val reason: String) : Verification
        data class Terminal(val reason: String) : Verification
    }
}
