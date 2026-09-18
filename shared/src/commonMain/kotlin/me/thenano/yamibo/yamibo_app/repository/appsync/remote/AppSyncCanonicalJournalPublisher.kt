package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCanonicalJournalPreparationResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCanonicalDocument
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*

internal sealed interface AppSyncCanonicalJournalPublishResult {
    data class Verified(val journal: LoadedAppSyncCanonicalDocument) : AppSyncCanonicalJournalPublishResult
    data class Unknown(val candidateBlogIds: List<BlogId>) : AppSyncCanonicalJournalPublishResult
    data object Disabled : AppSyncCanonicalJournalPublishResult
    data object FormExpired : AppSyncCanonicalJournalPublishResult
    data object Conflict : AppSyncCanonicalJournalPublishResult
    data object InvalidDocument : AppSyncCanonicalJournalPublishResult
    data object StoragePressure : AppSyncCanonicalJournalPublishResult
    data object TerminalFailure : AppSyncCanonicalJournalPublishResult
}

/** Inline transport only. Disabled by default; the caller must supply a freshly evaluated
 * persisted rollout/cohort gate, durable attempt identity and class/discovery selection.
 * Never edits immutable segment roots, acknowledges outbox rows or changes the index.
 */
internal class AppSyncCanonicalJournalPublisher(
    private val provider: AppSyncBlogProvider,
    private val canWrite: suspend () -> Boolean = { false },
) {
    suspend fun publish(prepared: AppSyncCanonicalJournalPreparationResult.Ready, blogId: BlogId?,
        expectedFingerprint: String?, classSelection: AppSyncBlogClassSelection,
        formHash: FormHash): AppSyncCanonicalJournalPublishResult {
        if (!canWrite()) return AppSyncCanonicalJournalPublishResult.Disabled
        val journal = prepared.journal
        val account = journal.block.accountBinding
        val replica = "${journal.deviceId}:${journal.deviceEpoch}"
        val title = AppSyncJournalDefaults.journalTitle(SyncDeviceId(journal.deviceId), SyncDeviceEpoch(journal.deviceEpoch))
        val codec = AppSyncV3DocumentCodec()
        val expected = codec.discover(prepared.envelope, account, AppSyncV3PayloadKind.Journal) as? AppSyncV3DocumentRead.Journal
            ?: return AppSyncCanonicalJournalPublishResult.InvalidDocument
        val represented = expected.document.block.operations.mapTo(hashSetOf()) {
            SyncOperation.idFor(SyncDeviceId(it.deviceId), SyncDeviceEpoch(it.deviceEpoch), SyncSequence(it.sequence)) }
        if (expected.document != journal || expected.metadata.identity != replica ||
            expected.metadata.canonicalFingerprint != prepared.fingerprint || !represented.containsAll(prepared.sourceOperationIds))
            return AppSyncCanonicalJournalPublishResult.InvalidDocument
        if (!AppSyncPayloadBudget().measure(prepared.envelope).fitsTarget) return AppSyncCanonicalJournalPublishResult.StoragePressure
        if ((blogId == null && expectedFingerprint != null) || (blogId != null && blogId.value <= 0))
            return AppSyncCanonicalJournalPublishResult.Conflict

        var readAuthenticationExpired = false
        suspend fun read(id: BlogId): AppSyncV3DocumentRead.Journal? {
            val fetched = provider.fetchBlog(id)
            readAuthenticationExpired = fetched is AppSyncCloudResult.FormExpired || fetched == AppSyncCloudResult.NotLoggedIn
            val page = (fetched as? AppSyncCloudResult.VerifiedSuccess)?.value ?: return null
            if (page.blogInfo.blogId != id || page.blogInfo.title != title) return null
            return try { codec.discover(appSyncReaderText(page.rootBlog.contentHtml), account, AppSyncV3PayloadKind.Journal)
                as? AppSyncV3DocumentRead.Journal } catch (_: Exception) { null }
        }
        fun verified(id: BlogId, read: AppSyncV3DocumentRead.Journal) =
            AppSyncCanonicalJournalPublishResult.Verified(LoadedAppSyncCanonicalDocument(id.value.toString(), read))
        if (blogId != null) {
            val current = read(blogId) ?: return if (readAuthenticationExpired) AppSyncCanonicalJournalPublishResult.FormExpired
                else AppSyncCanonicalJournalPublishResult.Unknown(listOf(blogId))
            if (current == expected) return verified(blogId, current) // ambiguous prior attempt already committed
            if (current.metadata.identity != replica || current.document.writerNonce != journal.writerNonce ||
                expectedFingerprint == null || current.metadata.canonicalFingerprint != expectedFingerprint)
                return AppSyncCanonicalJournalPublishResult.Conflict
        }
        // A rollback/cohort change while preflight was in flight must prevent the write.
        if (!canWrite()) return AppSyncCanonicalJournalPublishResult.Disabled
        val submitted = provider.submitBlog(AppSyncBlogWriteRequest(blogId, title, prepared.envelope, classSelection, formHash))
        if (submitted is AppSyncCloudResult.FormExpired || submitted == AppSyncCloudResult.NotLoggedIn)
            return AppSyncCanonicalJournalPublishResult.FormExpired
        val candidates = if (blogId != null) listOf(blogId) else when (submitted) {
            is AppSyncCloudResult.VerifiedSuccess -> submitted.value.candidateBlogIds.distinct()
            is AppSyncCloudResult.AcknowledgedButUnverified -> listOfNotNull(submitted.candidateBlogId)
            else -> emptyList()
        }
        when (submitted) {
            is AppSyncCloudResult.VerifiedSuccess, is AppSyncCloudResult.AcknowledgedButUnverified,
            is AppSyncCloudResult.NetworkFailed, is AppSyncCloudResult.Timeout, is AppSyncCloudResult.HttpFailed,
            AppSyncCloudResult.Maintenance -> Unit
            else -> return AppSyncCanonicalJournalPublishResult.TerminalFailure
        }
        val candidate = candidates.singleOrNull()?.takeIf { it.value > 0 }
            ?: return AppSyncCanonicalJournalPublishResult.Unknown(candidates)
        val actual = read(candidate)
        if (readAuthenticationExpired) return AppSyncCanonicalJournalPublishResult.FormExpired
        return if (actual == expected) verified(candidate, actual)
            else AppSyncCanonicalJournalPublishResult.Unknown(candidates)
    }
}
