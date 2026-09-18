package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import okio.ByteString.Companion.encodeUtf8

internal sealed interface AppSyncV3ArtifactDiscovery {
    data class Found(val blogId: BlogId) : AppSyncV3ArtifactDiscovery
    data object Absent : AppSyncV3ArtifactDiscovery
    data object Unknown : AppSyncV3ArtifactDiscovery
    data object FormExpired : AppSyncV3ArtifactDiscovery
    data object Conflict : AppSyncV3ArtifactDiscovery
}

internal sealed interface AppSyncV3SegmentPublishResult {
    data class ReadyToCommitIndex(val rootBlogId: BlogId, val rootBody: String,
        val root: AppSyncV3SegmentRoot, val frozenEnvelope: String) : AppSyncV3SegmentPublishResult
    data object Disabled : AppSyncV3SegmentPublishResult
    data object FormExpired : AppSyncV3SegmentPublishResult
    data class Retryable(val reason: String) : AppSyncV3SegmentPublishResult
    data class NeedsAttention(val reason: String) : AppSyncV3SegmentPublishResult
}

/** Immutable native staging under the caller's account/session lease. Never commits index,
 * acknowledges sources, or deletes artifacts. Absence must mean completed authoritative
 * discovery, not a timeout or empty cache; ambiguous prior intents cannot create without it.
 */
internal class AppSyncV3SegmentPublisher(private val provider: AppSyncBlogProvider,
    private val recovery: SqlDelightAppSyncRecoveryStore, private val nowMillis: () -> Long,
    private val codec: AppSyncV3SegmentCodec = AppSyncV3SegmentCodec(),
    private val canWrite: suspend () -> Boolean = { false },
    private val discover: suspend (title: String, sha256: String) -> AppSyncV3ArtifactDiscovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown }) {
    suspend fun publish(sessionId: String, envelope: String, kind: AppSyncV3PayloadKind, identity: String,
        selection: AppSyncBlogClassSelection, formHash: FormHash): AppSyncV3SegmentPublishResult = try {
        if (!canWrite()) throw Stop(AppSyncV3SegmentPublishResult.Disabled)
        val session = requireNotNull(recovery.session(sessionId)) { "Missing native recovery session" }
        require(session.phase in setOf(AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot,
            AppSyncRecoveryPhase.CommittingIndex)) { "Native recovery is not in a publishing phase" }
        val frozen = recovery.pinPayload(sessionId, kind.name, identity, 3) { envelope }
        val plan = codec.plan(frozen, session.accountBinding.value, kind)
        val intents = recovery.segmentWrites(sessionId).associateBy { it.segmentIndex }
        // Validate every persisted link before any POST, including intents not yet confirmed.
        for ((index, intent) in intents) {
            require(index in plan.drafts.indices && intent.segmentCount == plan.drafts.size)
            val successor = intents[index + 1]
            val next = if (index == plan.drafts.lastIndex) null else successor?.blogId?.let {
                require(it in 1..Int.MAX_VALUE.toLong())
                require(successor.verifiedFingerprint == successor.expectedFingerprint)
                AppSyncV3SegmentReference(it.toInt(), successor.expectedFingerprint)
            }
            val expected = codec.encodeSegment(plan.drafts[index], next)
            require(intent.expectedFingerprint == sha(expected) && intent.nextBlogId == next?.blogId?.toLong())
            require((intent.blogId == null && intent.verifiedFingerprint == null) ||
                (intent.blogId != null && intent.blogId in 1..Int.MAX_VALUE.toLong() && intent.verifiedFingerprint == intent.expectedFingerprint))
        }
        if (session.phase != AppSyncRecoveryPhase.PublishingSegments) require(intents.size == plan.drafts.size && intents.values.all { it.blogId != null })
        var next: AppSyncV3SegmentReference? = null
        for (draft in plan.drafts.reversed()) {
            val body = codec.encodeSegment(draft, next)
            val fingerprint = sha(body)
            val prior = intents[draft.index]
            recovery.saveSegmentIntent(sessionId, draft.index, draft.count, fingerprint, next?.blogId?.toLong())
            val id = obtain(AppSyncV3SegmentCodec.segmentTitle(kind, draft.generation, draft.index), body,
                prior != null, prior?.blogId?.let { BlogId(it.toInt()) }, selection, formHash)
            // Re-reading old progress must not reset the failing later artifact's retry count.
            if (prior?.blogId == null)
                recovery.markSegmentVerified(sessionId, draft.index, fingerprint, id.value.toLong(), nowMillis())
            next = AppSyncV3SegmentReference(id.value, fingerprint)
        }
        if (recovery.session(sessionId)?.phase == AppSyncRecoveryPhase.PublishingSegments)
            recovery.transition(sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, nowMillis())
        val root = codec.root(plan, requireNotNull(next))
        val rootBody = codec.encodeRoot(root)
        val fingerprint = sha(rootBody)
        val firstIntent = recovery.pinNativeRootIntent(sessionId, fingerprint)
        val current = requireNotNull(recovery.session(sessionId))
        require(current.rootBlogId == null || current.rootFingerprint == fingerprint)
        val document = AppSyncV3DocumentCodec().discover(frozen, session.accountBinding.value, kind)
        val title = when (document) {
            is AppSyncV3DocumentRead.Journal -> AppSyncJournalDefaults.journalTitle(
                SyncDeviceId(document.document.deviceId), SyncDeviceEpoch(document.document.deviceEpoch))
            is AppSyncV3DocumentRead.Checkpoint -> AppSyncJournalDefaults.checkpointTitle(document.document.checkpointId)
            else -> error("Invalid frozen native document")
        }
        val rootId = obtain(title, rootBody, !firstIntent, current.rootBlogId?.let {
            require(it in 1..Int.MAX_VALUE.toLong()); BlogId(it.toInt())
        }, selection, formHash)
        if (current.phase == AppSyncRecoveryPhase.PublishingRoot)
            recovery.markRootVerified(sessionId, rootId.value.toLong(), fingerprint, nowMillis())
        AppSyncV3SegmentPublishResult.ReadyToCommitIndex(rootId, rootBody, root, frozen)
    } catch (stop: Stop) { stop.result
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: IllegalArgumentException) { AppSyncV3SegmentPublishResult.NeedsAttention("Native persisted plan or binding mismatch")
    } catch (_: IllegalStateException) { AppSyncV3SegmentPublishResult.NeedsAttention("Native publication state is invalid")
    } catch (_: Exception) { AppSyncV3SegmentPublishResult.Retryable("Native publication was interrupted") }

    private suspend fun obtain(title: String, body: String, priorIntent: Boolean, knownId: BlogId?,
        selection: AppSyncBlogClassSelection, formHash: FormHash): BlogId {
        if (knownId != null) return verify(knownId, title, body)
        val fingerprint = sha(body)
        if (priorIntent) when (val found = discover(title, fingerprint)) {
            is AppSyncV3ArtifactDiscovery.Found -> return verify(found.blogId, title, body)
            AppSyncV3ArtifactDiscovery.Absent -> Unit
            else -> stopDiscovery(found)
        }
        if (!canWrite()) throw Stop(AppSyncV3SegmentPublishResult.Disabled)
        val submitted = provider.submitBlog(AppSyncBlogWriteRequest(null, title, body, selection, formHash))
        if (submitted is AppSyncCloudResult.FormExpired || submitted == AppSyncCloudResult.NotLoggedIn)
            throw Stop(AppSyncV3SegmentPublishResult.FormExpired)
        if (submitted is AppSyncCloudResult.NoPermission || submitted is AppSyncCloudResult.ValidationFailed || submitted is AppSyncCloudResult.Conflict)
            throw Stop(AppSyncV3SegmentPublishResult.NeedsAttention("Native artifact submission was rejected"))
        val candidate = (submitted as? AppSyncCloudResult.VerifiedSuccess)?.value?.candidateBlogIds?.distinct()?.singleOrNull()
        if (candidate != null) return verify(candidate, title, body)
        // Never repeat POST in this call, even when immediate discovery finds nothing.
        return when (val found = discover(title, fingerprint)) {
            is AppSyncV3ArtifactDiscovery.Found -> verify(found.blogId, title, body)
            else -> stopDiscovery(found)
        }
    }
    private suspend fun verify(id: BlogId, title: String, expected: String): BlogId {
        if (id.value <= 0) throw Stop(AppSyncV3SegmentPublishResult.NeedsAttention("Invalid native artifact identity"))
        when (val loaded = provider.fetchBlog(id)) {
            is AppSyncCloudResult.VerifiedSuccess -> if (loaded.value.blogInfo.blogId != id || loaded.value.blogInfo.title != title ||
                appSyncReaderText(loaded.value.rootBlog.contentHtml) != expected)
                throw Stop(AppSyncV3SegmentPublishResult.NeedsAttention("Native artifact readback mismatch"))
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> throw Stop(AppSyncV3SegmentPublishResult.FormExpired)
            is AppSyncCloudResult.NoPermission, is AppSyncCloudResult.ValidationFailed, is AppSyncCloudResult.Conflict ->
                throw Stop(AppSyncV3SegmentPublishResult.NeedsAttention("Native artifact readback rejected"))
            else -> throw Stop(AppSyncV3SegmentPublishResult.Retryable("Native artifact readback unavailable"))
        }
        return id
    }
    private fun stopDiscovery(result: AppSyncV3ArtifactDiscovery): Nothing = throw Stop(when (result) {
        AppSyncV3ArtifactDiscovery.FormExpired -> AppSyncV3SegmentPublishResult.FormExpired
        AppSyncV3ArtifactDiscovery.Conflict -> AppSyncV3SegmentPublishResult.NeedsAttention("Native artifact discovery is ambiguous")
        else -> AppSyncV3SegmentPublishResult.Retryable("Native artifact requires authoritative reconciliation")
    })
    private fun sha(body: String) = body.encodeUtf8().sha256().hex()
    private class Stop(val result: AppSyncV3SegmentPublishResult) : Exception()
}
