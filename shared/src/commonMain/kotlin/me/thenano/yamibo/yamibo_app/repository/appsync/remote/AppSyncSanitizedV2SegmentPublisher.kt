package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import okio.ByteString.Companion.encodeUtf8

/** Publishes the frozen v2 companion as new immutable artifacts. Requires the engine lease;
 * leaves the original native documents, index and local source lifecycle untouched.
 */
internal class AppSyncSanitizedV2SegmentPublisher(
    private val provider: AppSyncBlogProvider,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
    private val codec: AppSyncSegmentEnvelopeCodec = AppSyncSegmentEnvelopeCodec(),
    private val canWrite: suspend () -> Boolean = { false },
    private val discover: suspend (String, String) -> AppSyncV3ArtifactDiscovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown },
) {
    suspend fun publish(sessionId: String, selection: AppSyncBlogClassSelection.Existing,
        formHash: FormHash): AppSyncSegmentPublishResult = try {
        if (!canWrite()) throw Stop(AppSyncSegmentPublishResult.Terminal("Sanitized v2 publication is disabled"))
        require(recovery.hasSanitizedV2Payload(sessionId)) { "Missing frozen fallback binding" }
        val session = requireNotNull(recovery.session(sessionId))
        require(session.phase in setOf(AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot,
            AppSyncRecoveryPhase.CommittingIndex))
        val frozen = recovery.sanitizedV2Payload(sessionId)
        val native = recovery.nativePayload(sessionId)
        val identity = native.identity
        val kind = when (native.kind) {
            AppSyncV3PayloadKind.Journal -> AppSyncSegmentPayloadKind.Journal
            AppSyncV3PayloadKind.Checkpoint -> AppSyncSegmentPayloadKind.Checkpoint
        }
        val publicationCodec = recovery.nativeSegmentConfiguration(sessionId)?.let(codec::withConfiguration) ?: codec
        val drafts = publicationCodec.split(frozen, session.accountBinding.value, kind, identity, session.generationId)
        val intents = recovery.segmentWrites(sessionId).associateBy { it.segmentIndex }
        for ((index, intent) in intents) {
            require(index in drafts.indices && intent.segmentCount == drafts.size)
            val successor = intents[index + 1]
            val next = if (index == drafts.lastIndex) null else successor?.blogId?.also {
                require(it in 1..Int.MAX_VALUE.toLong() && successor.verifiedFingerprint == successor.expectedFingerprint)
            }
            val expected = publicationCodec.encodeSegment(publicationCodec.withNextBlogId(drafts[index], next?.toString()))
            require(intent.expectedFingerprint == sha(expected) && intent.nextBlogId == next)
            require((intent.blogId == null && intent.verifiedFingerprint == null) ||
                (intent.blogId != null && intent.blogId in 1..Int.MAX_VALUE.toLong() && intent.verifiedFingerprint == intent.expectedFingerprint))
        }
        if (session.phase != AppSyncRecoveryPhase.PublishingSegments)
            require(intents.size == drafts.size && intents.values.all { it.blogId != null })
        recovery.pinNativeSegmentConfiguration(sessionId, publicationCodec.configuration)
        var next: BlogId? = null
        for (index in drafts.indices.reversed()) {
            val body = publicationCodec.encodeSegment(publicationCodec.withNextBlogId(drafts[index], next?.value?.toString()))
            val fingerprint = sha(body)
            val prior = intents[index]
            recovery.saveSegmentIntent(sessionId, index, drafts.size, fingerprint, next?.value?.toLong())
            val id = obtain(AppSyncJournalDefaults.segmentTitle(kind, session.generationId, index), body,
                prior != null, prior?.blogId?.let { BlogId(it.toInt()) }, selection, formHash)
            if (prior?.blogId == null) recovery.markSegmentVerified(sessionId, index, fingerprint, id.value.toLong(), nowMillis())
            next = id
        }
        if (recovery.session(sessionId)?.phase == AppSyncRecoveryPhase.PublishingSegments)
            recovery.transition(sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, nowMillis())
        val root = publicationCodec.root(drafts, requireNotNull(next).value.toString(), frozen)
        val rootBody = publicationCodec.encodeRoot(root)
        val fingerprint = stableAppSyncFingerprint(rootBody)
        val firstIntent = recovery.pinNativeRootIntent(sessionId, fingerprint)
        val current = requireNotNull(recovery.session(sessionId))
        require(current.rootBlogId == null || current.rootFingerprint == fingerprint)
        val id = obtain(AppSyncJournalDefaults.rootTitle(kind, session.generationId), rootBody, !firstIntent,
            current.rootBlogId?.let { require(it in 1..Int.MAX_VALUE.toLong()); BlogId(it.toInt()) }, selection, formHash)
        if (current.phase == AppSyncRecoveryPhase.PublishingRoot)
            recovery.markRootVerified(sessionId, id.value.toLong(), fingerprint, nowMillis())
        AppSyncSegmentPublishResult.ReadyToCommitIndex(id.value.toLong(), fingerprint, rootBody)
    } catch (stop: Stop) { stop.result
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: IllegalArgumentException) { AppSyncSegmentPublishResult.Terminal("Fallback persisted plan or binding mismatch")
    } catch (_: IllegalStateException) { AppSyncSegmentPublishResult.Terminal("Fallback publication state is invalid")
    } catch (_: Exception) { AppSyncSegmentPublishResult.Retryable("Fallback publication was interrupted") }

    private suspend fun obtain(title: String, body: String, priorIntent: Boolean, knownId: BlogId?,
        selection: AppSyncBlogClassSelection, formHash: FormHash): BlogId {
        if (knownId != null) return verify(knownId, title, body)
        val fingerprint = sha(body)
        if (priorIntent) when (val found = discover(title, fingerprint)) {
            is AppSyncV3ArtifactDiscovery.Found -> return verify(found.blogId, title, body)
            AppSyncV3ArtifactDiscovery.Absent -> Unit
            else -> stopDiscovery(found)
        }
        if (!canWrite()) throw Stop(AppSyncSegmentPublishResult.Terminal("Sanitized v2 publication is disabled"))
        val submitted = provider.submitBlog(AppSyncBlogWriteRequest(null, title, body, selection, formHash))
        if (submitted is AppSyncCloudResult.FormExpired || submitted == AppSyncCloudResult.NotLoggedIn)
            throw Stop(AppSyncSegmentPublishResult.FormExpired)
        if (submitted is AppSyncCloudResult.NoPermission || submitted is AppSyncCloudResult.ValidationFailed || submitted is AppSyncCloudResult.Conflict)
            throw Stop(AppSyncSegmentPublishResult.Terminal("Fallback artifact submission was rejected"))
        val candidate = (submitted as? AppSyncCloudResult.VerifiedSuccess)?.value?.candidateBlogIds?.distinct()?.singleOrNull()
        if (candidate != null) return verify(candidate, title, body)
        // Never repeat POST in this call, even when immediate discovery finds nothing.
        return when (val found = discover(title, fingerprint)) {
            is AppSyncV3ArtifactDiscovery.Found -> verify(found.blogId, title, body)
            else -> stopDiscovery(found)
        }
    }
    private suspend fun verify(id: BlogId, title: String, expected: String): BlogId {
        if (id.value <= 0) throw Stop(AppSyncSegmentPublishResult.Terminal("Invalid fallback artifact identity"))
        when (val loaded = provider.fetchBlog(id)) {
            is AppSyncCloudResult.VerifiedSuccess -> if (loaded.value.blogInfo.blogId != id || loaded.value.blogInfo.title != title ||
                appSyncReaderText(loaded.value.rootBlog.contentHtml) != expected)
                throw Stop(AppSyncSegmentPublishResult.Terminal("Fallback artifact readback mismatch"))
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> throw Stop(AppSyncSegmentPublishResult.FormExpired)
            is AppSyncCloudResult.NoPermission, is AppSyncCloudResult.ValidationFailed, is AppSyncCloudResult.Conflict ->
                throw Stop(AppSyncSegmentPublishResult.Terminal("Fallback artifact readback rejected"))
            else -> throw Stop(AppSyncSegmentPublishResult.Retryable("Fallback artifact readback unavailable"))
        }
        return id
    }
    private fun stopDiscovery(result: AppSyncV3ArtifactDiscovery): Nothing = throw Stop(when (result) {
        AppSyncV3ArtifactDiscovery.FormExpired -> AppSyncSegmentPublishResult.FormExpired
        AppSyncV3ArtifactDiscovery.Conflict -> AppSyncSegmentPublishResult.Terminal("Fallback artifact discovery is ambiguous")
        else -> AppSyncSegmentPublishResult.Retryable("Fallback artifact requires authoritative reconciliation")
    })
    private fun sha(body: String) = body.encodeUtf8().sha256().hex()
    private class Stop(val result: AppSyncSegmentPublishResult) : Exception()
}
