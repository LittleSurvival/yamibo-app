package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog

internal sealed interface AppSyncSegmentIndexCommitResult {
    data object Verified : AppSyncSegmentIndexCommitResult
    data object FormExpired : AppSyncSegmentIndexCommitResult
    data class Retryable(val reason: String) : AppSyncSegmentIndexCommitResult
    data class Conflict(val reason: String) : AppSyncSegmentIndexCommitResult
    data class Terminal(val reason: String) : AppSyncSegmentIndexCommitResult
}

/** Commits a staged v2 Journal root by making it the verified Index reference. */
internal class AppSyncSegmentIndexCommitter(
    private val provider: AppSyncBlogProvider,
    private val remoteStore: AppSyncRemoteBlogStore,
    private val recoveryStore: SqlDelightAppSyncRecoveryStore,
    private val indexCodec: AppSyncIndexEnvelopeCodec = AppSyncIndexEnvelopeCodec(),
    private val nowMillis: () -> Long,
) {
    suspend fun commitJournalRoot(
        sessionId: String,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ): AppSyncSegmentIndexCommitResult {
        val session = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentIndexCommitResult.Terminal("Recovery session is missing")
        if (session.phase == AppSyncRecoveryPhase.ActivatingLocal && session.indexCommitted) {
            return AppSyncSegmentIndexCommitResult.Verified
        }
        if (session.phase != AppSyncRecoveryPhase.CommittingIndex) {
            return AppSyncSegmentIndexCommitResult.Terminal("Recovery root is not ready for Index commit")
        }
        val rootBlogId = session.rootBlogId?.toInt()
            ?: return AppSyncSegmentIndexCommitResult.Terminal("Verified recovery root is missing")
        val rootFingerprint = session.rootFingerprint
            ?: return AppSyncSegmentIndexCommitResult.Terminal("Verified recovery fingerprint is missing")
        val replicaKey = SyncReplicaKey(session.targetDeviceId, session.targetDeviceEpoch).stableKey
        val existing = remoteStore.load(INDEX_REMOTE_KEY)
        val currentPayload = if (existing == null) {
            AppSyncIndexPayload(session.accountBinding, updatedAtEpochMillis = nowMillis())
        } else {
            when (val loaded = loadIndex(existing.blogId)) {
                is LoadedIndex.Valid -> loaded.payload
                LoadedIndex.NotFound -> return AppSyncSegmentIndexCommitResult.Conflict(
                    "Previously verified Index is missing",
                )
                is LoadedIndex.Retryable -> return AppSyncSegmentIndexCommitResult.Retryable(loaded.reason)
                is LoadedIndex.Terminal -> return AppSyncSegmentIndexCommitResult.Terminal(loaded.reason)
            }
        }
        if (currentPayload.accountBinding != session.accountBinding) {
            return AppSyncSegmentIndexCommitResult.Terminal("Index account binding does not match")
        }
        val rootReference = AppSyncIndexJournalReference(replicaKey, rootBlogId, rootFingerprint)
        val desiredPayload = currentPayload.copy(
            journals = currentPayload.journals.filterNot { it.replicaKey == replicaKey } + rootReference,
            updatedAtEpochMillis = nowMillis(),
        )
        val encoded = indexCodec.encode(desiredPayload)
        val expected = (indexCodec.validate(encoded) as AppSyncIndexValidation.Valid).envelope
        val submitted = provider.submitBlog(
            AppSyncBlogWriteRequest(
                blogId = existing?.blogId,
                title = APP_SYNC_INDEX_TITLE,
                message = encoded,
                classSelection = classSelection,
                formHash = formHash,
            ),
        )
        val candidates = when (submitted) {
            is AppSyncCloudResult.VerifiedSuccess ->
                (listOfNotNull(existing?.blogId) + submitted.value.candidateBlogIds).distinct()
            is AppSyncCloudResult.AcknowledgedButUnverified ->
                listOfNotNull(existing?.blogId, submitted.candidateBlogId).distinct()
            is AppSyncCloudResult.FormExpired, AppSyncCloudResult.NotLoggedIn ->
                return AppSyncSegmentIndexCommitResult.FormExpired
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            is AppSyncCloudResult.HttpFailed,
            AppSyncCloudResult.Maintenance,
            -> listOfNotNull(existing?.blogId)
            else -> return AppSyncSegmentIndexCommitResult.Terminal(submitted.describeForJournal())
        }
        if (candidates.isEmpty()) {
            return AppSyncSegmentIndexCommitResult.Retryable(
                "Index outcome is ambiguous and has no authoritative candidate",
            )
        }
        candidates.forEach { candidateId ->
            when (val loaded = loadIndex(candidateId)) {
                is LoadedIndex.Valid -> if (
                    loaded.fingerprint == expected.fingerprint &&
                    loaded.payload.journals.singleOrNull { it.replicaKey == replicaKey } == rootReference
                ) {
                    remoteStore.save(
                        StoredAppSyncRemoteBlog(
                            remoteKey = INDEX_REMOTE_KEY,
                            kind = AppSyncRemoteBlogKind.Index,
                            blogId = candidateId,
                            classId = (classSelection as? AppSyncBlogClassSelection.Existing)?.classId,
                            fingerprint = loaded.fingerprint,
                            validatedAtEpochMillis = nowMillis(),
                            contentUpdatedAtEpochMillis = loaded.payload.updatedAtEpochMillis,
                        ),
                    )
                    recoveryStore.markIndexCommitted(sessionId, nowMillis())
                    return AppSyncSegmentIndexCommitResult.Verified
                }
                is LoadedIndex.Terminal -> return AppSyncSegmentIndexCommitResult.Terminal(loaded.reason)
                else -> Unit
            }
        }
        return AppSyncSegmentIndexCommitResult.Retryable(
            "Index reload did not verify the staged Journal root reference",
        )
    }

    private suspend fun loadIndex(blogId: BlogId): LoadedIndex = when (val loaded = provider.fetchBlog(blogId)) {
        is AppSyncCloudResult.VerifiedSuccess -> {
            val page = loaded.value
            if (page.blogInfo.blogId != blogId || page.blogInfo.title != APP_SYNC_INDEX_TITLE) {
                LoadedIndex.Terminal("Index reader identity does not match")
            } else when (val validation = indexCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
                is AppSyncIndexValidation.Valid -> LoadedIndex.Valid(
                    validation.envelope.payload,
                    validation.envelope.fingerprint,
                )
                is AppSyncIndexValidation.Invalid -> LoadedIndex.Terminal(validation.reason)
            }
        }
        AppSyncCloudResult.NotFound -> LoadedIndex.NotFound
        is AppSyncCloudResult.NetworkFailed,
        is AppSyncCloudResult.Timeout,
        AppSyncCloudResult.Maintenance,
        -> LoadedIndex.Retryable(loaded.describeForJournal())
        else -> LoadedIndex.Terminal(loaded.describeForJournal())
    }

    private sealed interface LoadedIndex {
        data class Valid(val payload: AppSyncIndexPayload, val fingerprint: String) : LoadedIndex
        data object NotFound : LoadedIndex
        data class Retryable(val reason: String) : LoadedIndex
        data class Terminal(val reason: String) : LoadedIndex
    }

    private companion object {
        const val INDEX_REMOTE_KEY = "index"
    }
}
