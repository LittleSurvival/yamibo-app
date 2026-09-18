package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.NativeRecoveryIndexIntent
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import okio.ByteString.Companion.encodeUtf8

/** Sanitized v2 index publication under the caller's account/session lease. The provider has no
 * compare-and-swap; recheck the base before POST and require complete authoritative readback.
 * Never acknowledges outbox sources, activates local projections, or cleans up artifacts.
 */
internal class AppSyncSanitizedV2IndexCommitter(
    private val provider: AppSyncBlogProvider,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val publisher: AppSyncSanitizedV2SegmentPublisher,
    private val nowMillis: () -> Long,
    private val canWrite: suspend () -> Boolean = { false },
    private val budget: AppSyncPayloadBudget = AppSyncPayloadBudget(),
) {
    private val codec = AppSyncIndexEnvelopeCodec()

    suspend fun commit(sessionId: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        requiredCheckpoint: AppSyncVerifiedCanonicalCheckpoint): AppSyncSegmentIndexCommitResult = try {
        commitVerified(sessionId, selection, formHash, requiredCheckpoint)
    } catch (stop: Stop) { stop.result
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: IllegalArgumentException) { AppSyncSegmentIndexCommitResult.Conflict("Fallback index binding or persisted intent changed")
    } catch (_: IllegalStateException) { AppSyncSegmentIndexCommitResult.Terminal("Fallback index recovery state is invalid")
    } catch (_: Exception) { AppSyncSegmentIndexCommitResult.Retryable("Fallback index publication interrupted") }

    private suspend fun commitVerified(sessionId: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        requiredCheckpoint: AppSyncVerifiedCanonicalCheckpoint): AppSyncSegmentIndexCommitResult {
        if (!canWrite()) return AppSyncSegmentIndexCommitResult.Terminal("Fallback index publication is disabled")
        if (!recovery.hasSanitizedV2Payload(sessionId))
            return AppSyncSegmentIndexCommitResult.Terminal("Frozen sanitized v2 binding is required")
        val session = requireNotNull(recovery.session(sessionId))
        if (session.indexCommitted && session.phase in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Completed) && recovery.usesNativeTransport(sessionId))
            return AppSyncSegmentIndexCommitResult.Verified
        val scanner = AppSyncV3ArtifactReconciler(provider, selection.classId)
        val account = session.accountBinding.value
        val frozen = recovery.nativePayload(sessionId)
        val identity = frozen.identity
        val journal = (AppSyncV3DocumentCodec().discover(frozen.body, account, AppSyncV3PayloadKind.Journal)
            as? AppSyncV3DocumentRead.Journal)?.document ?: error("Invalid canonical journal")
        require(journal.acknowledgements.any { it.checkpointId == requiredCheckpoint.document.checkpointId &&
            it.coverage == requiredCheckpoint.document.coverage }) { "Checkpoint is not acknowledged by the frozen journal" }
        requireCheckpoint(requiredCheckpoint, account, observe(scanner, account))
        val publication = when (val result = publisher.publish(sessionId, selection, formHash)) {
            is AppSyncSegmentPublishResult.ReadyToCommitIndex -> result
            AppSyncSegmentPublishResult.FormExpired -> return AppSyncSegmentIndexCommitResult.FormExpired
            is AppSyncSegmentPublishResult.Retryable -> return AppSyncSegmentIndexCommitResult.Retryable(result.reason)
            is AppSyncSegmentPublishResult.Terminal -> return AppSyncSegmentIndexCommitResult.Terminal(result.reason)
        }
        var current = observe(scanner, account)
        requireCheckpoint(requiredCheckpoint, account, current)
        val intent = recovery.nativeIndexIntent(sessionId) ?: run {
            val base = current?.envelope?.payload ?: AppSyncIndexPayload(session.accountBinding, updatedAtEpochMillis = nowMillis())
            val updated = base.copy(journals = base.journals.filterNot { it.replicaKey == identity } +
                AppSyncIndexJournalReference(identity, publication.rootBlogId.toInt(), publication.rootFingerprint),
                updatedAtEpochMillis = nowMillis())
            val body = codec.encode(updated)
            checkBudget(body)
            recovery.pinNativeIndexIntent(sessionId, NativeRecoveryIndexIntent(body, current?.id?.value?.toLong(), current?.sha))
        }
        checkBudget(intent.body)
        val expectedSha = sha(intent.body)
        if (current?.sha == expectedSha) return confirm(sessionId, intent, current)
        requireBase(intent, current)
        // The first scan may have preceded local persistence. Recheck immediately before POST.
        current = observe(scanner, account)
        if (current?.sha == expectedSha) return confirm(sessionId, intent, current)
        requireBase(intent, current)
        if (!canWrite()) return AppSyncSegmentIndexCommitResult.Terminal("Fallback index publication is disabled")
        val result = provider.submitBlog(AppSyncBlogWriteRequest(intent.targetBlogId?.let { BlogId(it.toInt()) },
            APP_SYNC_INDEX_TITLE, intent.body, selection, formHash))
        when (result) {
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> return AppSyncSegmentIndexCommitResult.FormExpired
            is AppSyncCloudResult.NoPermission, is AppSyncCloudResult.ValidationFailed, is AppSyncCloudResult.Conflict ->
                return AppSyncSegmentIndexCommitResult.Conflict("Fallback index submission rejected")
            else -> Unit // Acknowledgements, timeouts and errors all require authoritative readback.
        }
        val after = observe(scanner, account)
        if (after?.sha == expectedSha) return confirm(sessionId, intent, after)
        requireBase(intent, after)
        return AppSyncSegmentIndexCommitResult.Retryable("Fallback index write is not yet visible")
    }

    private fun requireCheckpoint(checkpoint: AppSyncVerifiedCanonicalCheckpoint, account: String, current: Read?) {
        require(checkpoint.document.accountBinding == account)
        require(current?.envelope?.payload?.checkpoints?.any {
            it.checkpointId == checkpoint.document.checkpointId && it.blogId.toLong() == checkpoint.blogId &&
                it.fingerprint == checkpoint.fingerprint
        } == true) { "Acknowledged canonical checkpoint is no longer indexed" }
    }

    private fun confirm(sessionId: String, intent: NativeRecoveryIndexIntent, read: Read): AppSyncSegmentIndexCommitResult {
        require(intent.targetBlogId == null || intent.targetBlogId == read.id.value.toLong())
        recovery.markSanitizedV2IndexCommitted(sessionId, read.id.value.toLong(), read.html, nowMillis())
        return AppSyncSegmentIndexCommitResult.Verified
    }

    private fun requireBase(intent: NativeRecoveryIndexIntent, current: Read?) {
        require(if (intent.targetBlogId == null) current == null else
            current?.id?.value?.toLong() == intent.targetBlogId && current.sha == intent.baseSha256) {
            "Remote index changed after fallback intent"
        }
    }

    private suspend fun observe(scanner: AppSyncV3ArtifactReconciler, account: String): Read? {
        val ids = when (val scan = scanner.findCandidates(APP_SYNC_INDEX_TITLE)) {
            is AppSyncV3CandidateScan.Complete -> scan.blogIds
            AppSyncV3CandidateScan.FormExpired -> throw Stop(AppSyncSegmentIndexCommitResult.FormExpired)
            AppSyncV3CandidateScan.Unknown -> throw Stop(AppSyncSegmentIndexCommitResult.Retryable("Fallback index discovery incomplete"))
        }
        if (ids.isEmpty()) return null
        if (ids.size != 1) throw Stop(AppSyncSegmentIndexCommitResult.Conflict("Multiple fallback index candidates"))
        val id = ids.single()
        val blog = when (val fetched = provider.fetchBlog(id)) {
            is AppSyncCloudResult.VerifiedSuccess -> fetched.value
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> throw Stop(AppSyncSegmentIndexCommitResult.FormExpired)
            else -> throw Stop(AppSyncSegmentIndexCommitResult.Retryable("Fallback index readback unavailable"))
        }
        require(blog.blogInfo.blogId == id && blog.blogInfo.title == APP_SYNC_INDEX_TITLE)
        val parsed = (codec.validateReaderHtml(blog.rootBlog.contentHtml) as? AppSyncIndexValidation.Valid)?.envelope
        requireNotNull(parsed)
        require(parsed.payload.accountBinding.value == account)
        return Read(id, blog.rootBlog.contentHtml, parsed, sha(codec.encode(parsed.payload)))
    }

    private fun checkBudget(body: String) {
        budget.requireWithinTarget(body)
        require(body.encodeUtf8().size <= budget.targetChars)
    }
    private fun sha(body: String) = body.encodeUtf8().sha256().hex()
    private data class Read(val id: BlogId, val html: String, val envelope: ParsedAppSyncIndexEnvelope, val sha: String)
    private class Stop(val result: AppSyncSegmentIndexCommitResult) : Exception()
}
