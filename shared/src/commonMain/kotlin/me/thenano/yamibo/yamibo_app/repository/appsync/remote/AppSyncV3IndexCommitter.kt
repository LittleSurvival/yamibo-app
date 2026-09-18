package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.NativeRecoveryIndexIntent
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import okio.ByteString.Companion.encodeUtf8

/** Native index publication under the caller's account/session lease. The provider has no
 * compare-and-swap; recheck the base before POST and require complete authoritative readback.
 * Never acknowledges outbox sources, activates local projections, or cleans up artifacts.
 */
internal class AppSyncV3IndexCommitter(
    private val provider: AppSyncBlogProvider,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val publisher: AppSyncV3SegmentPublisher,
    private val nowMillis: () -> Long,
    private val canWrite: suspend () -> Boolean = { false },
    private val budget: AppSyncPayloadBudget = AppSyncPayloadBudget(),
) {
    private val codec = AppSyncIndexEnvelopeCodec()

    suspend fun commit(sessionId: String, envelope: String, kind: AppSyncV3PayloadKind, identity: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        requiredCheckpoint: AppSyncVerifiedCanonicalCheckpoint? = null): AppSyncSegmentIndexCommitResult = try {
        commitVerified(sessionId, envelope, kind, identity, selection, formHash, requiredCheckpoint)
    } catch (stop: Stop) { stop.result
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: IllegalArgumentException) { AppSyncSegmentIndexCommitResult.Conflict("Native index binding or persisted intent changed")
    } catch (_: IllegalStateException) { AppSyncSegmentIndexCommitResult.Terminal("Native index recovery state is invalid")
    } catch (_: Exception) { AppSyncSegmentIndexCommitResult.Retryable("Native index publication interrupted") }

    private suspend fun commitVerified(sessionId: String, envelope: String, kind: AppSyncV3PayloadKind, identity: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        requiredCheckpoint: AppSyncVerifiedCanonicalCheckpoint?): AppSyncSegmentIndexCommitResult {
        if (!canWrite()) return AppSyncSegmentIndexCommitResult.Terminal("Native index publication is disabled")
        val session = requireNotNull(recovery.session(sessionId))
        if (session.indexCommitted && session.phase in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Completed) && recovery.usesNativeTransport(sessionId))
            return AppSyncSegmentIndexCommitResult.Verified
        val scanner = AppSyncV3ArtifactReconciler(provider, selection.classId)
        val account = session.accountBinding.value
        val migrationSource = if (recovery.usesNativeTransport(sessionId)) recovery.legacyMigrationSource(sessionId) else null
        if (migrationSource != null) requireMigrationBase(sessionId, migrationSource, observe(scanner, account))
        val publication = when (val result = publisher.publish(sessionId, envelope, kind, identity, selection, formHash)) {
            is AppSyncV3SegmentPublishResult.ReadyToCommitIndex -> result
            AppSyncV3SegmentPublishResult.FormExpired -> return AppSyncSegmentIndexCommitResult.FormExpired
            AppSyncV3SegmentPublishResult.Disabled -> return AppSyncSegmentIndexCommitResult.Terminal("Native publication is disabled")
            is AppSyncV3SegmentPublishResult.Retryable -> return AppSyncSegmentIndexCommitResult.Retryable(result.reason)
            is AppSyncV3SegmentPublishResult.NeedsAttention -> return AppSyncSegmentIndexCommitResult.Conflict(result.reason)
        }
        var current = observe(scanner, account)
        if (migrationSource != null) requireMigrationBase(sessionId, migrationSource, current)
        requiredCheckpoint?.let { checkpoint ->
            require(checkpoint.document.accountBinding == account)
            require(current?.envelope?.payload?.checkpoints?.any {
                it.checkpointId == checkpoint.document.checkpointId && it.blogId.toLong() == checkpoint.blogId &&
                    it.fingerprint == checkpoint.fingerprint
            } == true) { "Required canonical checkpoint is no longer indexed" }
        }
        val intent = recovery.nativeIndexIntent(sessionId) ?: run {
            val base = current?.envelope?.payload ?: AppSyncIndexPayload(session.accountBinding, updatedAtEpochMillis = nowMillis())
            val fp = publication.root.metadata.canonicalFingerprint
            val updated = when (kind) {
                AppSyncV3PayloadKind.Checkpoint -> base.copy(checkpoints = base.checkpoints.filterNot { it.checkpointId == identity } +
                    AppSyncIndexCheckpointReference(identity, publication.rootBlogId.value, fp), updatedAtEpochMillis = nowMillis())
                AppSyncV3PayloadKind.Journal -> base.copy(journals = base.journals.filterNot { it.replicaKey == identity } +
                    AppSyncIndexJournalReference(identity, publication.rootBlogId.value, fp), updatedAtEpochMillis = nowMillis())
            }
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
        if (!canWrite()) return AppSyncSegmentIndexCommitResult.Terminal("Native index publication is disabled")
        val result = provider.submitBlog(AppSyncBlogWriteRequest(intent.targetBlogId?.let { BlogId(it.toInt()) },
            APP_SYNC_INDEX_TITLE, intent.body, selection, formHash))
        when (result) {
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> return AppSyncSegmentIndexCommitResult.FormExpired
            is AppSyncCloudResult.NoPermission, is AppSyncCloudResult.ValidationFailed, is AppSyncCloudResult.Conflict ->
                return AppSyncSegmentIndexCommitResult.Conflict("Native index submission rejected")
            else -> Unit // Acknowledgements, timeouts and errors all require authoritative readback.
        }
        val after = observe(scanner, account)
        if (after?.sha == expectedSha) return confirm(sessionId, intent, after)
        requireBase(intent, after)
        return AppSyncSegmentIndexCommitResult.Retryable("Native index write is not yet visible")
    }

    private fun requireMigrationBase(sessionId: String,
        source: me.thenano.yamibo.yamibo_app.store.appsync.NativeLegacyMigrationSource, current: Read?) {
        requireNotNull(current) { "Legacy migration index is missing" }
        require(current.envelope.payload.checkpoints.any {
            it.blogId.toLong() == source.blogId && it.checkpointId == source.checkpointId && it.fingerprint == source.fingerprint
        }) { "Legacy migration source is no longer indexed" }
        val intent = recovery.nativeIndexIntent(sessionId)
        // A lost POST response may already have replaced the old index with our exact frozen
        // intent. Otherwise the entire source index must still match, including journal refs.
        val ownWriteVisible = intent != null && intent.targetBlogId == current.id.value.toLong() && sha(intent.body) == current.sha
        require(ownWriteVisible || current.envelope.fingerprint == source.indexFingerprint) {
            "Legacy migration index changed before publication"
        }
    }

    private fun confirm(sessionId: String, intent: NativeRecoveryIndexIntent, read: Read): AppSyncSegmentIndexCommitResult {
        require(intent.targetBlogId == null || intent.targetBlogId == read.id.value.toLong())
        recovery.markNativeIndexCommitted(sessionId, read.id.value.toLong(), read.html, nowMillis())
        return AppSyncSegmentIndexCommitResult.Verified
    }

    private fun requireBase(intent: NativeRecoveryIndexIntent, current: Read?) {
        require(if (intent.targetBlogId == null) current == null else
            current?.id?.value?.toLong() == intent.targetBlogId && current.sha == intent.baseSha256) {
            "Remote index changed after native intent"
        }
    }

    private suspend fun observe(scanner: AppSyncV3ArtifactReconciler, account: String): Read? {
        val ids = when (val scan = scanner.findCandidates(APP_SYNC_INDEX_TITLE)) {
            is AppSyncV3CandidateScan.Complete -> scan.blogIds
            AppSyncV3CandidateScan.FormExpired -> throw Stop(AppSyncSegmentIndexCommitResult.FormExpired)
            AppSyncV3CandidateScan.Unknown -> throw Stop(AppSyncSegmentIndexCommitResult.Retryable("Native index discovery incomplete"))
        }
        if (ids.isEmpty()) return null
        if (ids.size != 1) throw Stop(AppSyncSegmentIndexCommitResult.Conflict("Multiple native index candidates"))
        val id = ids.single()
        val blog = when (val fetched = provider.fetchBlog(id)) {
            is AppSyncCloudResult.VerifiedSuccess -> fetched.value
            AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> throw Stop(AppSyncSegmentIndexCommitResult.FormExpired)
            else -> throw Stop(AppSyncSegmentIndexCommitResult.Retryable("Native index readback unavailable"))
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
