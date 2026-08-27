package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyOperationClassification
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryOperationStager
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncSegmentedJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementRemoteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupDeleteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupObservationStore
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupReachability
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupReachabilityAnalyzer
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupRunResult
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncSegmentGenerationCandidate
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal sealed interface AppSyncCloudResetResult {
    data class Verified(val deletedBlogCount: Int) : AppSyncCloudResetResult
    data object FormExpired : AppSyncCloudResetResult
    data class RetryableFailure(val reason: String) : AppSyncCloudResetResult
    data class TerminalFailure(val reason: String) : AppSyncCloudResetResult
}

internal class YamiboAppSyncJournalRemote(
    private val provider: AppSyncBlogProvider,
    private val store: AppSyncRemoteBlogStore,
    private val journalCodec: AppSyncJournalEnvelopeCodec = AppSyncJournalEnvelopeCodec(),
    private val indexCodec: AppSyncIndexEnvelopeCodec = AppSyncIndexEnvelopeCodec(),
    private val checkpointCodec: AppSyncCheckpointEnvelopeCodec = AppSyncCheckpointEnvelopeCodec(),
    private val segmentCodec: AppSyncSegmentEnvelopeCodec = AppSyncSegmentEnvelopeCodec(),
    private val nowMillis: () -> Long = ::currentTimeMillis,
    private val retirementIntents:
        (SyncAccountBinding) -> List<AppSyncJournalRetirementIntent> = { emptyList() },
    private val recoveryStore: SqlDelightAppSyncRecoveryStore? = null,
    private val cleanupObservationStore: AppSyncCleanupObservationStore? = null,
    private val capacityFlags: AppSyncCapacityFeatureFlags = AppSyncCapacityFeatureFlags(),
) : AppSyncSegmentedJournalRemote, AppSyncLegacyRecoveryRemote {
    private val verifiedJournalCache = mutableMapOf<String, LoadedAppSyncJournal>()
    private val verifiedCheckpointCache = mutableMapOf<String, LoadedAppSyncCheckpoint>()
    private var verifiedIndexCache: VerifiedIndex? = null

    suspend fun deleteAllVerifiedSyncData(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
    ): AppSyncCloudResetResult {
        val first = when (val result = provider.fetchMyBlogs()) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.toCloudResetFailure()
        }
        val syncClass = first.blogClasses.firstOrNull {
            it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME
        } ?: return AppSyncCloudResetResult.Verified(0)
        store.saveClassId(accountBinding, syncClass.id)
        val pages = when (val result = fetchAllPages(syncClass.id, firstPage = null)) {
            is BlogPagesResult.Success -> result.pages
            is BlogPagesResult.Failure -> return when (val failure = result.result) {
                AppSyncJournalLoadResult.NotLoggedIn -> AppSyncCloudResetResult.FormExpired
                is AppSyncJournalLoadResult.RetryableFailure ->
                    AppSyncCloudResetResult.RetryableFailure(failure.reason)
                is AppSyncJournalLoadResult.TerminalFailure ->
                    AppSyncCloudResetResult.TerminalFailure(failure.reason)
                is AppSyncJournalLoadResult.Success ->
                    AppSyncCloudResetResult.TerminalFailure("Unexpected discovery result")
            }
        }
        val verifiedIds = linkedSetOf<BlogId>()
        for ((title1, bId) in pages.flatMap { it.blogs }) {
            val title = normalizeListTitle(
                title1,
                AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
            )
            val candidate = StoredAppSyncRemoteBlog(
                remoteKey = "delete-candidate:${bId.value}",
                kind = AppSyncRemoteBlogKind.Journal,
                blogId = bId,
                classId = syncClass.id,
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            )
            when {
                title.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) -> {
                    when (val result = loadJournal(candidate, accountBinding)) {
                        is JournalCandidateResult.Valid -> verifiedIds += bId
                        is JournalCandidateResult.Retryable ->
                            return AppSyncCloudResetResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
                title == APP_SYNC_INDEX_TITLE -> {
                    when (val result = loadIndex(candidate, accountBinding)) {
                        is IndexCandidateResult.Valid -> verifiedIds += bId
                        is IndexCandidateResult.Retryable ->
                            return AppSyncCloudResetResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
                title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) -> {
                    when (val result = loadCheckpoint(candidate, accountBinding)) {
                        is CheckpointCandidateResult.Valid -> verifiedIds += bId
                        is CheckpointCandidateResult.Retryable ->
                            return AppSyncCloudResetResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
            }
        }

        var deleted = 0
        for (blogId in verifiedIds) {
            when (
                val result = provider.deleteBlog(
                    AppSyncBlogDeleteRequest(blogId = blogId, formHash = formHash),
                )
            ) {
                is AppSyncCloudResult.VerifiedSuccess,
                AppSyncCloudResult.NotFound,
                -> deleted += 1
                is AppSyncCloudResult.FormExpired,
                AppSyncCloudResult.NotLoggedIn,
                -> return AppSyncCloudResetResult.FormExpired
                is AppSyncCloudResult.NetworkFailed,
                is AppSyncCloudResult.Timeout,
                is AppSyncCloudResult.HttpFailed,
                AppSyncCloudResult.Maintenance,
                is AppSyncCloudResult.AcknowledgedButUnverified,
                -> return AppSyncCloudResetResult.RetryableFailure(result.describeForJournal())
                else -> return AppSyncCloudResetResult.TerminalFailure(result.describeForJournal())
            }
        }
        store.clear()
        verifiedJournalCache.clear()
        verifiedCheckpointCache.clear()
        verifiedIndexCache = null
        return AppSyncCloudResetResult.Verified(deleted)
    }

    override suspend fun loadJournals(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncJournalLoadResult = if (forceDiscovery) {
        discoverAll(accountBinding)
    } else {
        discoverCurrentLinks(accountBinding)
    }

    fun clearLinkCache(accountBinding: SyncAccountBinding): Int {
        val links = AppSyncRemoteBlogKind.entries.sumOf { store.loadKind(it).size }
        store.loadKind(AppSyncRemoteBlogKind.Index)
            .firstNotNullOfOrNull { it.classId }
            ?.let { store.saveClassId(accountBinding, it) }
            ?: store.loadKind(AppSyncRemoteBlogKind.Journal)
                .firstNotNullOfOrNull { it.classId }
                ?.let { store.saveClassId(accountBinding, it) }
            ?: store.loadKind(AppSyncRemoteBlogKind.Checkpoint)
                .firstNotNullOfOrNull { it.classId }
                ?.let { store.saveClassId(accountBinding, it) }
        store.clear()
        verifiedJournalCache.clear()
        verifiedCheckpointCache.clear()
        verifiedIndexCache = null
        return links
    }

    override suspend fun publishRetirementIndex(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult {
        val existing = store.load(INDEX_REMOTE_KEY)
            ?: return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                "尚未取得可驗證的同步 index",
            )
        val current = when (val result = loadIndex(existing, intent.accountBinding)) {
            is IndexCandidateResult.Valid -> result
            IndexCandidateResult.NotFound ->
                return AppSyncJournalRetirementRemoteResult.RetryableFailure("同步 index 不存在")
            is IndexCandidateResult.Retryable ->
                return AppSyncJournalRetirementRemoteResult.RetryableFailure(result.reason)
            is IndexCandidateResult.Terminal ->
                return AppSyncJournalRetirementRemoteResult.TerminalFailure(result.reason)
        }
        val retirement = AppSyncIndexRetirementReference(
            replicaKey = intent.replicaKey,
            blogId = intent.sourceBlogId.toInt(),
            fingerprint = intent.fingerprint,
            publishedThroughSequence = intent.publishedThroughSequence,
            checkpointId = intent.checkpointId,
        )
        val payload = current.payload.copy(
            journals = current.payload.journals.filterNot {
                it.replicaKey == intent.replicaKey && it.blogId == intent.sourceBlogId.toInt()
            },
            retirements = current.payload.retirements
                .filterNot { it.replicaKey == intent.replicaKey } + retirement,
            updatedAtEpochMillis = nowMillis(),
        )
        val classSelection = existing.classId?.let(AppSyncBlogClassSelection::Existing)
            ?: return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                "同步 index 缺少 class identity",
            )
        val acknowledgement = when (
            val result = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = existing.blogId,
                    title = APP_SYNC_INDEX_TITLE,
                    message = indexCodec.encode(payload),
                    classSelection = classSelection,
                    formHash = formHash,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            is AppSyncCloudResult.FormExpired,
            AppSyncCloudResult.NotLoggedIn,
            -> return AppSyncJournalRetirementRemoteResult.FormExpired
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            is AppSyncCloudResult.HttpFailed,
            AppSyncCloudResult.Maintenance,
            is AppSyncCloudResult.AcknowledgedButUnverified,
            -> return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                result.describeForJournal(),
            )
            else -> return AppSyncJournalRetirementRemoteResult.TerminalFailure(
                result.describeForJournal(),
            )
        }
        val candidateIds = (listOf(existing.blogId) + acknowledgement.candidateBlogIds).distinct()
        for (blogId in candidateIds) {
            val candidate = existing.copy(blogId = blogId)
            val verified = loadIndex(candidate, intent.accountBinding)
            if (verified is IndexCandidateResult.Valid &&
                verified.payload.retirements.any { it == retirement } &&
                verified.payload.journals.none {
                    it.replicaKey == intent.replicaKey &&
                        it.blogId == intent.sourceBlogId.toInt()
                }
            ) {
                store.save(
                    candidate.copy(
                        fingerprint = verified.fingerprint,
                        validatedAtEpochMillis = nowMillis(),
                    ),
                )
                return AppSyncJournalRetirementRemoteResult.Verified
            }
        }
        return AppSyncJournalRetirementRemoteResult.RetryableFailure(
            "index retirement metadata reload verification failed",
        )
    }

    override suspend fun deleteRetiredJournal(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult {
        val candidate = StoredAppSyncRemoteBlog(
            remoteKey = intent.replicaKey,
            kind = AppSyncRemoteBlogKind.Journal,
            blogId = BlogId(intent.sourceBlogId.toInt()),
            classId = store.loadClassId(intent.accountBinding),
            fingerprint = intent.fingerprint,
            validatedAtEpochMillis = 0,
            contentUpdatedAtEpochMillis = null,
        )
        when (val loaded = loadJournal(candidate, intent.accountBinding)) {
            JournalCandidateResult.NotFound ->
                return AppSyncJournalRetirementRemoteResult.Verified
            is JournalCandidateResult.Retryable ->
                return AppSyncJournalRetirementRemoteResult.RetryableFailure(loaded.reason)
            is JournalCandidateResult.Terminal ->
                return AppSyncJournalRetirementRemoteResult.TerminalFailure(loaded.reason)
            is JournalCandidateResult.Valid -> {
                if (
                    loaded.journal.fingerprint != intent.fingerprint ||
                    loaded.journal.payload.resolvedPublishedThroughSequence() !=
                    intent.publishedThroughSequence
                ) {
                    return AppSyncJournalRetirementRemoteResult.TerminalFailure(
                        "Journal changed after retirement proof",
                    )
                }
            }
        }
        return when (
            val result = provider.deleteBlog(
                AppSyncBlogDeleteRequest(candidate.blogId, formHash),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess,
            AppSyncCloudResult.NotFound,
            -> {
                store.remove(intent.replicaKey)
                AppSyncJournalRetirementRemoteResult.Verified
            }
            is AppSyncCloudResult.FormExpired,
            AppSyncCloudResult.NotLoggedIn,
            -> AppSyncJournalRetirementRemoteResult.FormExpired
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            is AppSyncCloudResult.HttpFailed,
            AppSyncCloudResult.Maintenance,
            is AppSyncCloudResult.AcknowledgedButUnverified,
            -> AppSyncJournalRetirementRemoteResult.RetryableFailure(
                result.describeForJournal(),
            )
            else -> AppSyncJournalRetirementRemoteResult.TerminalFailure(
                result.describeForJournal(),
            )
        }
    }

    override suspend fun publishOwnJournal(
        payload: AppSyncJournalPayload,
        expectedFingerprint: String?,
        formHash: FormHash,
    ): AppSyncJournalPublishResult {
        val remoteKey = payload.replicaKey()
        val cached = store.load(remoteKey)
        if (cached != null) {
            var verifiedCached = verifiedJournalCache[remoteKey]?.takeIf {
                it.remoteId == cached.blogId.value.toString() &&
                    it.fingerprint == cached.fingerprint
            }
            if (verifiedCached == null) {
                when (val loaded = loadJournal(cached, payload.accountBinding)) {
                    is JournalCandidateResult.Valid -> {
                        saveJournal(cached, loaded.journal)
                        verifiedCached = loaded.journal
                    }
                    JournalCandidateResult.NotFound ->
                        return AppSyncJournalPublishResult.Conflict(
                            "Previously verified journal is missing",
                        )
                    is JournalCandidateResult.Retryable ->
                        return AppSyncJournalPublishResult.Unknown(loaded.reason)
                    is JournalCandidateResult.Terminal ->
                        return AppSyncJournalPublishResult.TerminalFailure(loaded.reason)
                }
            }
            if (verifiedCached.payload.writerNonce != payload.writerNonce) {
                return AppSyncJournalPublishResult.Conflict(
                    "Journal writer nonce belongs to another installation",
                )
            }
            if (expectedFingerprint != null && cached.fingerprint != expectedFingerprint) {
                return AppSyncJournalPublishResult.Conflict(
                    "Journal changed after the caller's verified load",
                )
            }
        }

        val classSelection = when (val resolved = resolveClassSelection(payload.accountBinding)) {
            is ClassSelectionResult.Success -> resolved.selection
            is ClassSelectionResult.Retryable ->
                return AppSyncJournalPublishResult.Unknown(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncJournalPublishResult.TerminalFailure(resolved.reason)
        }
        val encoded = journalCodec.encode(payload)
        val measurement = AppSyncPayloadBudget().measure(encoded)
        if (!measurement.fitsTarget) {
            return AppSyncJournalPublishResult.StoragePressure(
                encoded.length,
                measurement.targetChars,
            )
        }
        val expectedEnvelope = journalCodec.validate(encoded) as AppSyncJournalValidation.Valid
        val acknowledgement = when (
            val result = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = cached?.blogId,
                    title = AppSyncJournalDefaults.journalTitle(payload.deviceId, payload.deviceEpoch),
                    message = encoded,
                    classSelection = classSelection,
                    formHash = formHash,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            is AppSyncCloudResult.FormExpired -> return AppSyncJournalPublishResult.FormExpired
            is AppSyncCloudResult.NotLoggedIn -> return AppSyncJournalPublishResult.FormExpired
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            AppSyncCloudResult.Maintenance,
            is AppSyncCloudResult.AcknowledgedButUnverified,
            -> return AppSyncJournalPublishResult.Unknown(result.describeForJournal())
            else -> return AppSyncJournalPublishResult.TerminalFailure(result.describeForJournal())
        }

        val blogId = cached?.blogId
            ?: acknowledgement.candidateBlogIds.distinct().singleOrNull()
            ?: return AppSyncJournalPublishResult.Unknown(
                "Journal create succeeded but the response did not identify one blog id",
            )
        val candidate = StoredAppSyncRemoteBlog(
            remoteKey = remoteKey,
            kind = AppSyncRemoteBlogKind.Journal,
            blogId = blogId,
            classId = cached?.classId ?: classSelection.existingClassId(),
            fingerprint = expectedEnvelope.envelope.fingerprint,
            validatedAtEpochMillis = nowMillis(),
            contentUpdatedAtEpochMillis = payload.heartbeatAtEpochMillis,
        )
        val journal = LoadedAppSyncJournal(
            remoteId = blogId.value.toString(),
            fingerprint = expectedEnvelope.envelope.fingerprint,
            payload = payload,
        )
        saveJournal(candidate, journal)
        updateIndexBestEffort(payload.accountBinding, classSelection, formHash)
        return AppSyncJournalPublishResult.Verified(journal)
    }

    override suspend fun publishOwnJournalSegmented(
        payload: AppSyncJournalPayload,
        acknowledgementOperationIds: Set<SyncOperationId>,
        activeJournals: List<LoadedAppSyncJournal>,
        formHash: FormHash,
    ): AppSyncJournalPublishResult {
        if (!capacityFlags.v2WritesEnabled) {
            return AppSyncJournalPublishResult.StoragePressure(
                journalCodec.encode(payload).length,
                AppSyncPayloadBudget.DEFAULT_TARGET_CHARS,
            )
        }
        val durableStore = recoveryStore
            ?: return AppSyncJournalPublishResult.TerminalFailure(
                "Durable segmented Journal storage is unavailable",
            )
        if (!AppSyncProtocolCapabilities.canWriteV2(activeJournals.map { it.payload } + payload)) {
            return AppSyncJournalPublishResult.Conflict(
                "An active device cannot read segmented AppSync Journals",
            )
        }
        if (acknowledgementOperationIds.isEmpty()) {
            return AppSyncJournalPublishResult.TerminalFailure(
                "Segmented Journal publication requires pending operation identities",
            )
        }
        val classSelection = when (val resolved = resolveClassSelection(payload.accountBinding)) {
            is ClassSelectionResult.Success -> resolved.selection
            is ClassSelectionResult.Retryable ->
                return AppSyncJournalPublishResult.Unknown(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncJournalPublishResult.TerminalFailure(resolved.reason)
        }
        val sessionFingerprint = payload.segmentedSessionFingerprint(journalCodec)
        val session = durableStore.createOrResumeSegmentedJournal(
            payload.accountBinding,
            acknowledgementOperationIds.mapTo(linkedSetOf()) { it.value },
            sessionFingerprint,
            nowMillis(),
        )
        val stablePayload = payload.forSegmentedSession(session.createdAtEpochMillis)
        val canonicalEnvelope = journalCodec.encode(stablePayload)
        val envelope = journalCodec.validate(canonicalEnvelope) as AppSyncJournalValidation.Valid
        durableStore.recordPayloadMeasurement(
            session.sessionId,
            canonicalEnvelope.length,
            AppSyncPayloadBudget.DEFAULT_TARGET_CHARS,
            nowMillis(),
        )
        if (session.phase in setOf(
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Classifying,
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Staging,
            )
        ) {
            durableStore.startSegmentedJournal(session.sessionId, nowMillis())
        }
        val coordinator = AppSyncSegmentedJournalCommitCoordinator(
            publisher = AppSyncSegmentPublisher(provider, durableStore, nowMillis = nowMillis),
            indexCommitter = AppSyncSegmentIndexCommitter(
                provider, store, durableStore, nowMillis = nowMillis,
            ),
            recoveryStore = durableStore,
            nowMillis = nowMillis,
        )
        return when (
            val result = coordinator.commit(
                session.sessionId,
                canonicalEnvelope,
                SyncReplicaKey(payload.deviceId, payload.deviceEpoch).stableKey,
                classSelection,
                formHash,
            )
        ) {
            is AppSyncSegmentedJournalCommitResult.Verified -> {
                store.save(
                    StoredAppSyncRemoteBlog(
                        remoteKey = payload.replicaKey(),
                        kind = AppSyncRemoteBlogKind.JournalRoot,
                        blogId = BlogId(result.rootBlogId.toInt()),
                        classId = classSelection.existingClassId(),
                        fingerprint = envelope.envelope.fingerprint,
                        validatedAtEpochMillis = nowMillis(),
                        contentUpdatedAtEpochMillis = stablePayload.heartbeatAtEpochMillis,
                    ),
                )
                AppSyncJournalPublishResult.Verified(
                    LoadedAppSyncJournal(
                        remoteId = result.rootBlogId.toString(),
                        fingerprint = envelope.envelope.fingerprint,
                        payload = stablePayload,
                    ),
                )
            }
            AppSyncSegmentedJournalCommitResult.FormExpired -> AppSyncJournalPublishResult.FormExpired
            is AppSyncSegmentedJournalCommitResult.Retryable ->
                AppSyncJournalPublishResult.Unknown(result.reason)
            is AppSyncSegmentedJournalCommitResult.Conflict ->
                AppSyncJournalPublishResult.Conflict(result.reason)
            is AppSyncSegmentedJournalCommitResult.Terminal ->
                AppSyncJournalPublishResult.TerminalFailure(result.reason)
        }
    }

    override suspend fun recoverLegacyOperations(
        classifications: List<AppSyncLegacyOperationClassification>,
        observed: SyncCausalContext,
        checkpointAcknowledgements: List<AppSyncCheckpointAcknowledgement>,
        activeJournals: List<LoadedAppSyncJournal>,
        formHash: FormHash,
    ): AppSyncLegacyRecoveryResult {
        if (!capacityFlags.automaticLegacyRecoveryEnabled &&
            classifications.any { it.requiresRecovery }
        ) {
            return AppSyncLegacyRecoveryResult.Retryable(
                "Automatic legacy AppSync recovery is disabled by rollout policy",
            )
        }
        val durableStore = recoveryStore
            ?: return AppSyncLegacyRecoveryResult.NeedsAttention(
                "Durable legacy recovery storage is unavailable",
            )
        if (classifications.isEmpty() || classifications.none { it.requiresRecovery }) {
            return AppSyncLegacyRecoveryResult.Verified(0, 0, 0, 0)
        }
        val plan = AppSyncLegacyRecoveryPlanner().plan(classifications)
        if (plan.unknownOperationIds.isNotEmpty()) {
            return AppSyncLegacyRecoveryResult.Retryable(
                "Authoritative evidence is missing for ${plan.unknownOperationIds.size} operations",
            )
        }
        val accountBinding = classifications.first().operation.accountBinding
        if (classifications.any { it.operation.accountBinding != accountBinding }) {
            return AppSyncLegacyRecoveryResult.NeedsAttention(
                "Legacy recovery cannot span multiple accounts",
            )
        }
        val existing = durableStore.recoverySession(accountBinding)
        if (existing?.phase ==
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.NeedsAttention
        ) {
            return AppSyncLegacyRecoveryResult.NeedsAttention(
                "Legacy recovery requires attention for ${existing.blockingDomain ?: "unknown domain"}",
            )
        }
        val sourceIds = classifications.mapTo(linkedSetOf()) { it.operation.operationId.value }
        var session = durableStore.createOrResume(
            accountBinding = accountBinding,
            sourceOperationIds = sourceIds,
            replacementFingerprint = legacyRecoveryFingerprint(classifications),
            nowEpochMillis = nowMillis(),
            acknowledgedSourceOperationIds = plan.verifiedPresentSourceIds
                .mapTo(linkedSetOf()) { it.value },
        )
        plan.needsAttention.firstOrNull()?.let { blocker ->
            if (session.phase ==
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Classifying
            ) {
                durableStore.transition(
                    session.sessionId,
                    session.phase,
                    me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.NeedsAttention,
                    nowMillis(),
                    lastErrorCategory = "ENTITY_SIZE_POLICY",
                    blockingDomain = blocker.domain,
                    redactedBlockingEntity = blocker.redactedEntityId,
                )
            }
            return AppSyncLegacyRecoveryResult.NeedsAttention(
                "${blocker.domain} entity exceeds ${blocker.limitBytes} encoded bytes",
            )
        }
        if (plan.replacements.isEmpty()) {
            if (session.phase !=
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Completed
            ) {
                durableStore.completeVerifiedRecoveryWithoutPublication(session.sessionId, nowMillis())
            }
            return AppSyncLegacyRecoveryResult.Verified(
                sourceOperationCount = sourceIds.size,
                acknowledgedSourceCount = plan.verifiedPresentSourceIds.size,
                replacementOperationCount = 0,
                scrubbedLegacyPayloadCount = classifications.count { it.requiresRecovery },
            )
        }
        if (session.phase in setOf(
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Classifying,
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Staging,
            )
        ) {
            AppSyncRecoveryOperationStager(durableStore).stage(session.sessionId, plan)
            session = requireNotNull(durableStore.session(session.sessionId))
        }
        val operations = durableStore.shadowOperations(session.sessionId)
        if (operations.isEmpty()) {
            return AppSyncLegacyRecoveryResult.NeedsAttention(
                "Legacy recovery staging contains no replacement operations",
            )
        }
        val targetReplica = SyncReplicaKey(session.targetDeviceId, session.targetDeviceEpoch)
        val lastSequence = operations.last().sequence.value
        val payload = AppSyncJournalPayload(
            accountBinding = accountBinding,
            deviceId = session.targetDeviceId,
            deviceEpoch = session.targetDeviceEpoch,
            writerNonce = session.targetWriterNonce,
            firstSequence = operations.first().sequence.value,
            lastSequence = lastSequence,
            operations = operations,
            observed = observed.advance(targetReplica, SyncSequence(lastSequence)),
            checkpointAcknowledgements = checkpointAcknowledgements,
            heartbeatAtEpochMillis = session.createdAtEpochMillis,
            protocolReadVersion = AppSyncProtocolCapabilities.READER_VERSION,
            protocolWriteVersion = AppSyncProtocolCapabilities.READER_FIRST_WRITE_VERSION,
            publishedThroughSequence = lastSequence,
        )
        if (!AppSyncProtocolCapabilities.canWriteV2(activeJournals.map { it.payload } + payload)) {
            return AppSyncLegacyRecoveryResult.Conflict(
                "An active device cannot read segmented AppSync Journals",
            )
        }
        val classSelection = when (val resolved = resolveClassSelection(accountBinding)) {
            is ClassSelectionResult.Success -> resolved.selection
            is ClassSelectionResult.Retryable ->
                return AppSyncLegacyRecoveryResult.Retryable(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncLegacyRecoveryResult.NeedsAttention(resolved.reason)
        }
        val canonicalEnvelope = journalCodec.encode(payload)
        val validated = journalCodec.validate(canonicalEnvelope) as AppSyncJournalValidation.Valid
        durableStore.recordPayloadMeasurement(
            session.sessionId,
            canonicalEnvelope.length,
            AppSyncPayloadBudget.DEFAULT_TARGET_CHARS,
            nowMillis(),
        )
        val coordinator = AppSyncSegmentedJournalCommitCoordinator(
            publisher = AppSyncSegmentPublisher(provider, durableStore, nowMillis = nowMillis),
            indexCommitter = AppSyncSegmentIndexCommitter(
                provider, store, durableStore, nowMillis = nowMillis,
            ),
            recoveryStore = durableStore,
            nowMillis = nowMillis,
        )
        return when (
            val committed = coordinator.commit(
                session.sessionId,
                canonicalEnvelope,
                targetReplica.stableKey,
                classSelection,
                formHash,
            )
        ) {
            is AppSyncSegmentedJournalCommitResult.Verified -> {
                store.save(
                    StoredAppSyncRemoteBlog(
                        remoteKey = payload.replicaKey(),
                        kind = AppSyncRemoteBlogKind.JournalRoot,
                        blogId = BlogId(committed.rootBlogId.toInt()),
                        classId = classSelection.existingClassId(),
                        fingerprint = validated.envelope.fingerprint,
                        validatedAtEpochMillis = nowMillis(),
                        contentUpdatedAtEpochMillis = payload.heartbeatAtEpochMillis,
                    ),
                )
                AppSyncLegacyRecoveryResult.Verified(
                    sourceOperationCount = sourceIds.size,
                    acknowledgedSourceCount = plan.verifiedPresentSourceIds.size,
                    replacementOperationCount = operations.size,
                    scrubbedLegacyPayloadCount = classifications.count { it.requiresRecovery },
                )
            }
            AppSyncSegmentedJournalCommitResult.FormExpired -> AppSyncLegacyRecoveryResult.FormExpired
            is AppSyncSegmentedJournalCommitResult.Retryable ->
                AppSyncLegacyRecoveryResult.Retryable(committed.reason)
            is AppSyncSegmentedJournalCommitResult.Conflict ->
                AppSyncLegacyRecoveryResult.Conflict(committed.reason)
            is AppSyncSegmentedJournalCommitResult.Terminal ->
                AppSyncLegacyRecoveryResult.NeedsAttention(committed.reason)
        }
    }

    private fun legacyRecoveryFingerprint(
        classifications: List<AppSyncLegacyOperationClassification>,
    ): String = stableAppSyncFingerprint(
        classifications.sortedBy { it.operation.operationId.value }.joinToString("|") { classified ->
            val operation = classified.operation
            val portableFields = when (val result = classified.portability) {
                is me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPortableEntityResult.Portable ->
                    result.fields.entries.sortedBy { it.key }
                        .joinToString(",") { (key, value) -> "$key=$value" }
                is me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPortableEntityResult.NeedsAttention ->
                    "blocked:${result.domain}:${result.redactedEntityId}:${result.encodedBytes}"
            }
            "${operation.operationId.value}:${classified.evidence}:${operation.kind}:$portableFields"
        },
    )

    override suspend fun publishCheckpoint(
        payload: AppSyncCheckpointPayload,
        formHash: FormHash,
    ): AppSyncCheckpointPublishResult {
        val classSelection = when (val resolved = resolveClassSelection(payload.accountBinding)) {
            is ClassSelectionResult.Success -> resolved.selection
            is ClassSelectionResult.Retryable ->
                return AppSyncCheckpointPublishResult.Unknown(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncCheckpointPublishResult.TerminalFailure(resolved.reason)
        }
        val encoded = checkpointCodec.encode(payload)
        val expected = checkpointCodec.validate(encoded) as AppSyncCheckpointValidation.Valid
        val measurement = AppSyncPayloadBudget().measure(encoded)
        if (!measurement.fitsTarget) {
            if (!capacityFlags.v2WritesEnabled) {
                return AppSyncCheckpointPublishResult.StoragePressure(
                    encoded.length,
                    measurement.targetChars,
                )
            }
            if (!AppSyncProtocolCapabilities.canWriteV2(
                    verifiedJournalCache.values.map { it.payload },
                )
            ) {
                return AppSyncCheckpointPublishResult.TerminalFailure(
                    "An active device cannot read segmented AppSync Checkpoints",
                )
            }
            return publishCheckpointSegmented(
                payload = payload,
                classSelection = classSelection,
                formHash = formHash,
            )
        }
        val acknowledgement = when (
            val result = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = null,
                    title = AppSyncJournalDefaults.checkpointTitle(payload.checkpointId),
                    message = encoded,
                    classSelection = classSelection,
                    formHash = formHash,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            is AppSyncCloudResult.FormExpired,
            AppSyncCloudResult.NotLoggedIn,
            -> return AppSyncCheckpointPublishResult.FormExpired
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            AppSyncCloudResult.Maintenance,
            is AppSyncCloudResult.AcknowledgedButUnverified,
            -> return AppSyncCheckpointPublishResult.Unknown(result.describeForJournal())
            else -> return AppSyncCheckpointPublishResult.TerminalFailure(result.describeForJournal())
        }
        val blogId = acknowledgement.candidateBlogIds.distinct().singleOrNull()
            ?: return AppSyncCheckpointPublishResult.Unknown(
                "Checkpoint create succeeded but the response did not identify one blog id",
            )
        val candidate = StoredAppSyncRemoteBlog(
            remoteKey = checkpointRemoteKey(payload.checkpointId),
            kind = AppSyncRemoteBlogKind.Checkpoint,
            blogId = blogId,
            classId = classSelection.existingClassId(),
            fingerprint = expected.envelope.fingerprint,
            validatedAtEpochMillis = nowMillis(),
            contentUpdatedAtEpochMillis = payload.createdAtEpochMillis,
        )
        val checkpoint = LoadedAppSyncCheckpoint(
            remoteId = blogId.value.toString(),
            envelope = expected.envelope,
        )
        saveCheckpoint(candidate, checkpoint)
        updateIndexBestEffort(payload.accountBinding, classSelection, formHash)
        return AppSyncCheckpointPublishResult.Verified(checkpoint)
    }

    private suspend fun publishCheckpointSegmented(
        payload: AppSyncCheckpointPayload,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ): AppSyncCheckpointPublishResult {
        val durableStore = recoveryStore
            ?: return AppSyncCheckpointPublishResult.StoragePressure(
                checkpointCodec.encode(payload).length,
                AppSyncPayloadBudget.DEFAULT_TARGET_CHARS,
            )
        val session = durableStore.createOrResumeSegmentedCheckpoint(
            accountBinding = payload.accountBinding,
            checkpointId = payload.checkpointId,
            payloadFingerprint = stableAppSyncFingerprint(payload.checkpointId),
            nowEpochMillis = nowMillis(),
        )
        val stablePayload = payload.forSegmentedSession(session.createdAtEpochMillis)
        val stableCanonicalEnvelope = checkpointCodec.encode(stablePayload)
        val stableExpectedEnvelope = (
            checkpointCodec.validate(stableCanonicalEnvelope) as AppSyncCheckpointValidation.Valid
        ).envelope
        durableStore.recordPayloadMeasurement(
            session.sessionId,
            stableCanonicalEnvelope.length,
            AppSyncPayloadBudget.DEFAULT_TARGET_CHARS,
            nowMillis(),
        )
        if (session.phase in setOf(
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Classifying,
                me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Staging,
            )
        ) {
            durableStore.startSegmentedJournal(session.sessionId, nowMillis())
        }
        val coordinator = AppSyncSegmentedCheckpointCommitCoordinator(
            publisher = AppSyncSegmentPublisher(provider, durableStore, nowMillis = nowMillis),
            indexCommitter = AppSyncSegmentIndexCommitter(
                provider, store, durableStore, nowMillis = nowMillis,
            ),
            recoveryStore = durableStore,
            nowMillis = nowMillis,
        )
        return when (val committed = coordinator.commit(
            sessionId = session.sessionId,
            checkpointId = payload.checkpointId,
            canonicalEnvelope = stableCanonicalEnvelope,
            classSelection = classSelection,
            formHash = formHash,
        )) {
            is AppSyncSegmentedCheckpointCommitResult.Verified -> {
                val candidate = StoredAppSyncRemoteBlog(
                    remoteKey = checkpointRemoteKey(payload.checkpointId),
                    kind = AppSyncRemoteBlogKind.CheckpointRoot,
                    blogId = BlogId(committed.rootBlogId.toInt()),
                    classId = classSelection.existingClassId(),
                    fingerprint = stableExpectedEnvelope.fingerprint,
                    validatedAtEpochMillis = nowMillis(),
                    contentUpdatedAtEpochMillis = stablePayload.createdAtEpochMillis,
                )
                val checkpoint = LoadedAppSyncCheckpoint(
                    remoteId = committed.rootBlogId.toString(),
                    envelope = stableExpectedEnvelope,
                )
                saveCheckpoint(candidate, checkpoint)
                AppSyncCheckpointPublishResult.Verified(checkpoint)
            }
            AppSyncSegmentedCheckpointCommitResult.FormExpired ->
                AppSyncCheckpointPublishResult.FormExpired
            is AppSyncSegmentedCheckpointCommitResult.Retryable ->
                AppSyncCheckpointPublishResult.Unknown(committed.reason)
            is AppSyncSegmentedCheckpointCommitResult.Conflict ->
                AppSyncCheckpointPublishResult.Unknown(committed.reason)
            is AppSyncSegmentedCheckpointCommitResult.Terminal ->
                AppSyncCheckpointPublishResult.TerminalFailure(committed.reason)
        }
    }

    override suspend fun enforceCheckpointRetention(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        maximumCheckpoints: Int,
        pinnedCheckpointIds: Set<String>,
    ): AppSyncCheckpointRetentionResult {
        if (maximumCheckpoints <= 0) {
            return AppSyncCheckpointRetentionResult.TerminalFailure(
                "Checkpoint retention limit must be positive",
            )
        }
        cleanupSegmentGenerations(accountBinding, formHash, pinnedCheckpointIds)?.let { cleanup ->
            cleanup.retryableFailure?.let {
                return AppSyncCheckpointRetentionResult.RetryableFailure(it)
            }
            cleanup.terminalFailure?.let {
                return AppSyncCheckpointRetentionResult.TerminalFailure(it)
            }
        }
        val cached = (
            store.loadKind(AppSyncRemoteBlogKind.Checkpoint) +
                store.loadKind(AppSyncRemoteBlogKind.CheckpointRoot)
            )
            .filter {
                it.remoteKey.startsWith(CHECKPOINT_REMOTE_KEY_PREFIX) &&
                    it.fingerprint != null &&
                    it.contentUpdatedAtEpochMillis != null
            }
        if (cached.size <= maximumCheckpoints && pinnedCheckpointIds.isEmpty()) {
            return AppSyncCheckpointRetentionResult.NotNeeded
        }
        val sorted = cached
            .sortedWith(
                compareByDescending<StoredAppSyncRemoteBlog> {
                    it.contentUpdatedAtEpochMillis
                }.thenByDescending {
                    it.validatedAtEpochMillis
                }.thenByDescending {
                    it.blogId.value
                },
            )
        val pinned = sorted.filter { checkpointId(it.remoteKey) in pinnedCheckpointIds }
        val retained = (
            pinned + sorted.filter { it.kind == AppSyncRemoteBlogKind.CheckpointRoot } + sorted
                .filterNot { checkpointId(it.remoteKey) in pinnedCheckpointIds }
                .filter { it.kind == AppSyncRemoteBlogKind.Checkpoint }
                .take(maximumCheckpoints)
            ).distinctBy { it.remoteKey }
        val retainedRemoteKeys = retained.mapTo(hashSetOf()) { it.remoteKey }
        val toDelete = cached.filterNot { it.remoteKey in retainedRemoteKeys }

        var deleted = 0
        for ((remoteKey, _, blogId) in toDelete) {
            when (
                val result = provider.deleteBlog(
                    AppSyncBlogDeleteRequest(
                        blogId = blogId,
                        formHash = formHash,
                    ),
                )
            ) {
                is AppSyncCloudResult.VerifiedSuccess,
                AppSyncCloudResult.NotFound,
                -> {
                    deleted += 1
                    store.remove(remoteKey)
                }
                else -> return result.toCheckpointRetentionFailure()
            }
        }

        retained.firstNotNullOfOrNull { it.classId }?.let { classId ->
            updateIndexBestEffort(
                accountBinding = accountBinding,
                classSelection = AppSyncBlogClassSelection.Existing(classId),
                formHash = formHash,
            )
        }
        val retainedIds = retained.mapTo(linkedSetOf()) {
            it.remoteKey.removePrefix(CHECKPOINT_REMOTE_KEY_PREFIX)
        }
        return if (retained.size > maximumCheckpoints) {
            AppSyncCheckpointRetentionResult.StoragePressure(
                reason = "退休復原基準已固定；暫時保留 ${retained.size} 個 checkpoint",
                retainedCheckpointIds = retainedIds,
                deletedBlogCount = deleted,
            )
        } else {
            AppSyncCheckpointRetentionResult.Verified(
                retainedCheckpointIds = retainedIds,
                deletedBlogCount = deleted,
            )
        }
    }

    private suspend fun cleanupSegmentGenerations(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        pinnedCheckpointIds: Set<String>,
    ): AppSyncCleanupRunResult? {
        // This is the only opportunistic full-list v2 scan. Index-committed v2 roots remain
        // readable through loadCachedState even when this rollout switch is disabled.
        if (!capacityFlags.mayReadV2(committedIndexReference = false)) return null
        val observationStore = cleanupObservationStore ?: return null
        val durableStore = recoveryStore ?: return null
        val indexLink = store.load(INDEX_REMOTE_KEY) ?: return null
        val index = when (val loaded = loadIndex(indexLink, accountBinding)) {
            is IndexCandidateResult.Valid -> loaded
            else -> return null
        }
        val classId = indexLink.classId ?: store.loadClassId(accountBinding) ?: return null
        val pages = when (val loaded = fetchAllPages(classId, firstPage = null)) {
            is BlogPagesResult.Success -> loaded.pages
            is BlogPagesResult.Failure -> return AppSyncCleanupRunResult(
                orphanGenerationCount = 0,
                eligibleGenerationCount = 0,
                deletedBlogCount = 0,
                dryRunBlogCount = 0,
                retryableFailure = (loaded.result as? AppSyncJournalLoadResult.RetryableFailure)?.reason,
                terminalFailure = (loaded.result as? AppSyncJournalLoadResult.TerminalFailure)?.reason,
            )
        }
        val candidates = mutableListOf<AppSyncSegmentGenerationCandidate>()
        for (summary in pages.flatMap { it.blogs }) {
            val title = normalizeListTitle(summary.title, AppSyncCloudConfigDefaults.BLOG_CLASS_NAME)
            if (!title.startsWith(AppSyncJournalDefaults.ROOT_TITLE_PREFIX)) continue
            val page = when (val fetched = provider.fetchBlog(summary.bId)) {
                is AppSyncCloudResult.VerifiedSuccess -> fetched.value
                else -> continue
            }
            val rootBody = readerText(page.rootBlog.contentHtml)
            val root = segmentCodec.decodeRoot(rootBody).getOrNull() ?: continue
            if (root.accountBinding != accountBinding.value) continue
            val kind = when (root.kind) {
                AppSyncSegmentPayloadKind.Journal.name.lowercase() -> AppSyncSegmentPayloadKind.Journal
                AppSyncSegmentPayloadKind.Checkpoint.name.lowercase() -> AppSyncSegmentPayloadKind.Checkpoint
                else -> continue
            }
            val loaded = loadCanonicalEnvelope(rootBody, kind, accountBinding)
                as? CanonicalEnvelopeResult.Valid ?: continue
            val validPayload = when (kind) {
                AppSyncSegmentPayloadKind.Journal ->
                    journalCodec.validate(loaded.body) is AppSyncJournalValidation.Valid
                AppSyncSegmentPayloadKind.Checkpoint ->
                    checkpointCodec.validate(loaded.body) is AppSyncCheckpointValidation.Valid
            }
            candidates += AppSyncSegmentGenerationCandidate(
                generationId = root.generationId,
                rootBlogId = summary.bId.value.toLong(),
                rootFingerprint = stableAppSyncFingerprint(rootBody),
                segmentBlogIds = loaded.segmentBlogIds,
                payloadVerified = validPayload,
            )
        }
        val indexedRoots = index.payload.journals.mapTo(linkedSetOf()) { it.blogId.toLong() } +
            index.payload.checkpoints.mapTo(linkedSetOf()) { it.blogId.toLong() }
        val pinnedRoots = index.payload.checkpoints
            .filter { it.checkpointId in pinnedCheckpointIds }
            .mapTo(linkedSetOf()) { it.blogId.toLong() }
        val retirementRoots = retirementIntents(accountBinding)
            .mapTo(linkedSetOf()) { it.sourceBlogId }
        val reachability = AppSyncCleanupReachability(
            indexedRootBlogIds = indexedRoots,
            activeRecoveryRootBlogIds = AppSyncCleanupReachabilityAnalyzer.activeRecoveryRootIds(
                durableStore.activeSessions(accountBinding),
            ),
            pinnedCheckpointRootBlogIds = pinnedRoots,
            retirementRootBlogIds = retirementRoots,
        )
        return AppSyncCleanupCoordinator(
            store = observationStore,
            deleteBlog = { blogId ->
                when (val deleted = provider.deleteBlog(
                    AppSyncBlogDeleteRequest(BlogId(blogId.toInt()), formHash),
                )) {
                    is AppSyncCloudResult.VerifiedSuccess,
                    AppSyncCloudResult.NotFound,
                    -> AppSyncCleanupDeleteResult.Verified
                    is AppSyncCloudResult.NetworkFailed,
                    is AppSyncCloudResult.Timeout,
                    AppSyncCloudResult.Maintenance,
                    -> AppSyncCleanupDeleteResult.Retryable(deleted.describeForJournal())
                    else -> AppSyncCleanupDeleteResult.Terminal(deleted.describeForJournal())
                }
            },
            nowMillis = nowMillis,
        ).observeAndClean(
            accountBinding = accountBinding.value,
            candidates = candidates,
            reachability = reachability,
            authoritativeIndexFingerprint = index.fingerprint,
            dryRun = capacityFlags.cleanupDryRun,
            deletionEnabled = capacityFlags.mayDeleteCleanupCandidates(),
        )
    }

    private fun checkpointRemoteKey(checkpointId: String): String =
        "$CHECKPOINT_REMOTE_KEY_PREFIX$checkpointId"

    private fun checkpointId(remoteKey: String): String =
        remoteKey.removePrefix(CHECKPOINT_REMOTE_KEY_PREFIX)

    private suspend fun loadCachedState(
        accountBinding: SyncAccountBinding,
        preloadedIndex: IndexCandidateResult.Valid? = null,
    ): AppSyncJournalLoadResult.Success? {
        val cachedJournals = linkedMapOf<String, StoredAppSyncRemoteBlog>()
        val cachedCheckpoints = linkedMapOf<String, StoredAppSyncRemoteBlog>()
        store.loadKind(AppSyncRemoteBlogKind.Journal).forEach {
            cachedJournals[it.remoteKey] = it
        }
        store.loadKind(AppSyncRemoteBlogKind.JournalRoot).forEach {
            cachedJournals[it.remoteKey] = it
        }
        store.loadKind(AppSyncRemoteBlogKind.Checkpoint).forEach {
            cachedCheckpoints[it.remoteKey] = it
        }
        store.loadKind(AppSyncRemoteBlogKind.CheckpointRoot).forEach {
            cachedCheckpoints[it.remoteKey] = it
        }
        var indexedReplicaKeys = emptySet<String>()
        val index = store.load(INDEX_REMOTE_KEY)
        if (index != null) {
            when (val loadedIndex = preloadedIndex ?: loadIndex(index, accountBinding)) {
                is IndexCandidateResult.Valid -> {
                    indexedReplicaKeys = loadedIndex.payload.journals
                        .mapTo(linkedSetOf()) { it.replicaKey }
                    loadedIndex.payload.journals.forEach { reference ->
                        cachedJournals[reference.replicaKey] = StoredAppSyncRemoteBlog(
                            remoteKey = reference.replicaKey,
                            kind = AppSyncRemoteBlogKind.Journal,
                            blogId = BlogId(reference.blogId),
                            classId = index.classId,
                            fingerprint = reference.fingerprint,
                            validatedAtEpochMillis = 0,
                            contentUpdatedAtEpochMillis = null,
                        )
                    }
                    loadedIndex.payload.checkpoints.forEach { reference ->
                        val remoteKey = checkpointRemoteKey(reference.checkpointId)
                        cachedCheckpoints[remoteKey] = StoredAppSyncRemoteBlog(
                            remoteKey = remoteKey,
                            kind = AppSyncRemoteBlogKind.Checkpoint,
                            blogId = BlogId(reference.blogId),
                            classId = index.classId,
                            fingerprint = reference.fingerprint,
                            validatedAtEpochMillis = 0,
                            contentUpdatedAtEpochMillis = null,
                        )
                    }
                }
                IndexCandidateResult.NotFound -> store.remove(INDEX_REMOTE_KEY)
                is IndexCandidateResult.Retryable -> return null
                is IndexCandidateResult.Terminal -> Unit
            }
        }
        if (cachedJournals.isEmpty() && cachedCheckpoints.isEmpty()) return null

        val loadedJournals = mutableListOf<LoadedAppSyncJournal>()
        for (candidate in cachedJournals.values) {
            val cachedPayload = verifiedJournalCache[candidate.remoteKey]
                ?.takeIf {
                    candidate.fingerprint != null &&
                        it.remoteId == candidate.blogId.value.toString() &&
                        it.fingerprint == candidate.fingerprint
                }
            if (cachedPayload != null) {
                loadedJournals += cachedPayload
                continue
            }
            when (val result = loadJournal(candidate, accountBinding)) {
                is JournalCandidateResult.Valid -> {
                    saveJournal(candidate.copy(kind = result.kind), result.journal)
                    loadedJournals += result.journal
                }
                JournalCandidateResult.NotFound -> {
                    store.remove(candidate.remoteKey)
                    return null
                }
                is JournalCandidateResult.Retryable -> return null
                is JournalCandidateResult.Terminal -> {
                    // Corruption of one journal does not block valid cached journals.
                }
            }
        }
        val loadedCheckpoints = mutableListOf<LoadedAppSyncCheckpoint>()
        for (candidate in cachedCheckpoints.values) {
            val cachedPayload = verifiedCheckpointCache[candidate.remoteKey]
                ?.takeIf {
                    candidate.fingerprint != null &&
                        it.remoteId == candidate.blogId.value.toString() &&
                        it.envelope.fingerprint == candidate.fingerprint
                }
            if (cachedPayload != null) {
                loadedCheckpoints += cachedPayload
                continue
            }
            when (val result = loadCheckpoint(candidate, accountBinding)) {
                is CheckpointCandidateResult.Valid -> {
                    saveCheckpoint(candidate.copy(kind = result.kind), result.checkpoint)
                    loadedCheckpoints += result.checkpoint
                }
                CheckpointCandidateResult.NotFound -> {
                    store.remove(candidate.remoteKey)
                    return null
                }
                is CheckpointCandidateResult.Retryable -> return null
                is CheckpointCandidateResult.Terminal -> {
                    // Corruption of one checkpoint does not block valid journals.
                }
            }
        }
        if (loadedJournals.isEmpty() && loadedCheckpoints.isEmpty()) return null
        return AppSyncJournalLoadResult.Success(
            journals = loadedJournals
                .filterNot { journal ->
                    completedRetirements(accountBinding).any { it.matches(journal) }
                }
                .distinctBy { it.payload.replicaKey() },
            checkpoints = loadedCheckpoints.distinctBy { it.envelope.payload.checkpointId },
            indexedReplicaKeys = indexedReplicaKeys,
        )
    }

    private suspend fun discoverCurrentLinks(
        accountBinding: SyncAccountBinding,
    ): AppSyncJournalLoadResult {
        store.load(INDEX_REMOTE_KEY)?.let { cachedIndex ->
            when (val loadedIndex = loadIndex(cachedIndex, accountBinding)) {
                is IndexCandidateResult.Valid ->
                    loadCachedState(accountBinding, loadedIndex)?.let { return it }
                IndexCandidateResult.NotFound -> store.remove(INDEX_REMOTE_KEY)
                is IndexCandidateResult.Retryable ->
                    return AppSyncJournalLoadResult.RetryableFailure(loadedIndex.reason)
                is IndexCandidateResult.Terminal -> store.remove(INDEX_REMOTE_KEY)
            }
        }
        val classSelection = when (val resolved = resolveClassSelection(accountBinding)) {
            is ClassSelectionResult.Success -> resolved.selection
            is ClassSelectionResult.Retryable ->
                return AppSyncJournalLoadResult.RetryableFailure(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncJournalLoadResult.TerminalFailure(resolved.reason)
        }
        val classId = when (classSelection) {
            is AppSyncBlogClassSelection.Existing -> classSelection.classId
            is AppSyncBlogClassSelection.Create ->
                return AppSyncJournalLoadResult.Success(emptyList())
        }
        val firstPage = when (val result = provider.fetchMyBlogs(classId, page = 1)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.toJournalLoadFailure()
        }
        val latestIndexSummary = firstPage.blogs
            .filter {
                normalizeListTitle(
                    it.title,
                    AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
                ) == APP_SYNC_INDEX_TITLE
            }
            .maxWithOrNull(compareBy({ it.timeInfo.epoch }, { it.bId.value }))
        if (latestIndexSummary != null) {
            val index = StoredAppSyncRemoteBlog(
                remoteKey = INDEX_REMOTE_KEY,
                kind = AppSyncRemoteBlogKind.Index,
                blogId = latestIndexSummary.bId,
                classId = classId,
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = latestIndexSummary.timeInfo.epoch * 1_000L,
            )
            when (val loadedIndex = loadIndex(index, accountBinding)) {
                is IndexCandidateResult.Valid -> {
                    store.save(
                        index.copy(
                            fingerprint = loadedIndex.fingerprint,
                            validatedAtEpochMillis = nowMillis(),
                        ),
                    )
                    loadCachedState(accountBinding, loadedIndex)?.let { return it }
                    return discoverAll(
                        accountBinding = accountBinding,
                        knownClassId = classId,
                        firstClassPage = firstPage,
                        preloadedIndex = loadedIndex,
                    )
                }
                is IndexCandidateResult.Retryable ->
                    return AppSyncJournalLoadResult.RetryableFailure(loadedIndex.reason)
                else -> Unit
            }
        }
        return discoverAll(accountBinding, classId, firstPage)
    }

    private suspend fun discoverAll(
        accountBinding: SyncAccountBinding,
        knownClassId: BlogClassId? = null,
        firstClassPage: UserSpaceBlogPage? = null,
        preloadedIndex: IndexCandidateResult.Valid? = null,
    ): AppSyncJournalLoadResult {
        val classId = knownClassId ?: when (val resolved = resolveClassSelection(accountBinding)) {
            is ClassSelectionResult.Success ->
                (resolved.selection as? AppSyncBlogClassSelection.Existing)?.classId
                    ?: return AppSyncJournalLoadResult.Success(emptyList())
            is ClassSelectionResult.Retryable ->
                return AppSyncJournalLoadResult.RetryableFailure(resolved.reason)
            is ClassSelectionResult.Terminal ->
                return AppSyncJournalLoadResult.TerminalFailure(resolved.reason)
        }
        store.saveClassId(accountBinding, classId)
        val pages = when (val result = fetchAllPages(classId, firstPage = firstClassPage)) {
            is BlogPagesResult.Success -> result.pages
            is BlogPagesResult.Failure -> return result.result
        }
        val loaded = mutableListOf<LoadedAppSyncJournal>()
        val checkpoints = mutableListOf<LoadedAppSyncCheckpoint>()
        val indexedReplicaKeys = linkedSetOf<String>()
        val retirementDiscoveryIssues = mutableListOf<String>()
        val summaries = pages.flatMap { it.blogs }
        val latestIndexBlogId = summaries
            .filter {
                normalizeListTitle(
                    it.title,
                    AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
                ) == APP_SYNC_INDEX_TITLE
            }
            .maxWithOrNull(compareBy({ it.timeInfo.epoch }, { it.bId.value }))
            ?.bId
        for ((title, bId, _, _, _, timeInfo) in summaries) {
            val normalizedTitle = normalizeListTitle(
                title,
                AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
            )
            when {
                normalizedTitle.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) -> {
                    val candidate = StoredAppSyncRemoteBlog(
                        remoteKey = "candidate:${bId.value}",
                        kind = AppSyncRemoteBlogKind.Journal,
                        blogId = bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = timeInfo.epoch * 1_000L,
                    )
                    when (val result = loadJournal(candidate, accountBinding)) {
                        is JournalCandidateResult.Valid -> {
                            val remoteKey = result.journal.payload.replicaKey()
                            saveJournal(
                                candidate.copy(remoteKey = remoteKey, kind = result.kind),
                                result.journal,
                            )
                            loaded += result.journal
                        }
                        is JournalCandidateResult.Retryable ->
                            return AppSyncJournalLoadResult.RetryableFailure(result.reason)
                        JournalCandidateResult.NotFound ->
                            retirementDiscoveryIssues += "Journal disappeared during discovery"
                        is JournalCandidateResult.Terminal ->
                            retirementDiscoveryIssues += "Journal validation failed"
                    }
                }
                normalizedTitle == APP_SYNC_INDEX_TITLE -> {
                    if (bId != latestIndexBlogId) continue
                    val index = StoredAppSyncRemoteBlog(
                        remoteKey = INDEX_REMOTE_KEY,
                        kind = AppSyncRemoteBlogKind.Index,
                        blogId = bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = timeInfo.epoch * 1_000L,
                    )
                    when (val result = preloadedIndex ?: loadIndex(index, accountBinding)) {
                        is IndexCandidateResult.Valid -> {
                            indexedReplicaKeys += result.payload.journals.map {
                                it.replicaKey
                            }
                            store.save(
                                index.copy(
                                    fingerprint = result.fingerprint,
                                    validatedAtEpochMillis = nowMillis(),
                                ),
                            )
                        }
                        IndexCandidateResult.NotFound ->
                            retirementDiscoveryIssues += "Index disappeared during discovery"
                        is IndexCandidateResult.Retryable ->
                            return AppSyncJournalLoadResult.RetryableFailure(result.reason)
                        is IndexCandidateResult.Terminal ->
                            retirementDiscoveryIssues += "Index validation failed"
                    }
                }
                normalizedTitle.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) -> {
                    val candidate = StoredAppSyncRemoteBlog(
                        remoteKey = "checkpoint-candidate:${bId.value}",
                        kind = AppSyncRemoteBlogKind.Checkpoint,
                        blogId = bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = timeInfo.epoch * 1_000L,
                    )
                    when (val result = loadCheckpoint(candidate, accountBinding)) {
                        is CheckpointCandidateResult.Valid -> {
                            saveCheckpoint(candidate.copy(kind = result.kind), result.checkpoint)
                            checkpoints += result.checkpoint
                        }
                        is CheckpointCandidateResult.Retryable ->
                            return AppSyncJournalLoadResult.RetryableFailure(result.reason)
                        CheckpointCandidateResult.NotFound ->
                            retirementDiscoveryIssues += "Checkpoint disappeared during discovery"
                        is CheckpointCandidateResult.Terminal ->
                            retirementDiscoveryIssues += "Checkpoint validation failed"
                    }
                }
            }
        }
        return AppSyncJournalLoadResult.Success(
            loaded
                .filterNot { journal ->
                    completedRetirements(accountBinding).any { it.matches(journal) }
                }
                .distinctBy { it.payload.replicaKey() },
            checkpoints.distinctBy { it.envelope.payload.checkpointId },
            indexedReplicaKeys = indexedReplicaKeys,
            retirementDiscoveryIssues = retirementDiscoveryIssues.distinct(),
        )
    }

    private suspend fun loadCheckpoint(
        candidate: StoredAppSyncRemoteBlog,
        accountBinding: SyncAccountBinding,
    ): CheckpointCandidateResult {
        val page = when (val result = provider.fetchBlog(candidate.blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            AppSyncCloudResult.NotFound -> return CheckpointCandidateResult.NotFound
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            AppSyncCloudResult.Maintenance,
            -> return CheckpointCandidateResult.Retryable(result.describeForJournal())
            else -> return CheckpointCandidateResult.Terminal(result.describeForJournal())
        }
        if (page.blogInfo.blogId != candidate.blogId ||
            (!page.blogInfo.title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) &&
                !page.blogInfo.title.startsWith(AppSyncJournalDefaults.ROOT_TITLE_PREFIX))
        ) {
            return CheckpointCandidateResult.Terminal("Checkpoint reader identity does not match")
        }
        val rootBody = readerText(page.rootBlog.contentHtml)
        val canonical = when (val loaded = loadCanonicalEnvelope(
            rootBody,
            AppSyncSegmentPayloadKind.Checkpoint,
            accountBinding,
        )) {
            is CanonicalEnvelopeResult.Valid -> loaded.body
            is CanonicalEnvelopeResult.Retryable ->
                return CheckpointCandidateResult.Retryable(loaded.reason)
            is CanonicalEnvelopeResult.Terminal ->
                return CheckpointCandidateResult.Terminal(loaded.reason)
        }
        val kind = if (rootBody.contains(AppSyncSegmentEnvelopeCodec.ROOT_MARKER)) {
            AppSyncRemoteBlogKind.CheckpointRoot
        } else {
            AppSyncRemoteBlogKind.Checkpoint
        }
        return when (val validation = checkpointCodec.validate(canonical)) {
            is AppSyncCheckpointValidation.Valid -> {
                if (validation.envelope.payload.accountBinding != accountBinding) {
                    CheckpointCandidateResult.Terminal("Checkpoint account binding does not match")
                } else {
                    CheckpointCandidateResult.Valid(
                        LoadedAppSyncCheckpoint(
                            remoteId = candidate.blogId.value.toString(),
                            envelope = validation.envelope,
                        ),
                        kind,
                    )
                }
            }
            is AppSyncCheckpointValidation.Invalid ->
                CheckpointCandidateResult.Terminal(validation.reason)
        }
    }

    private suspend fun loadJournal(
        candidate: StoredAppSyncRemoteBlog,
        accountBinding: SyncAccountBinding,
    ): JournalCandidateResult {
        val page = when (val result = provider.fetchBlog(candidate.blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            AppSyncCloudResult.NotFound -> return JournalCandidateResult.NotFound
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            AppSyncCloudResult.Maintenance,
            -> return JournalCandidateResult.Retryable(result.describeForJournal())
            else -> return JournalCandidateResult.Terminal(result.describeForJournal())
        }
        if (page.blogInfo.blogId != candidate.blogId ||
            (!page.blogInfo.title.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) &&
                !page.blogInfo.title.startsWith(AppSyncJournalDefaults.ROOT_TITLE_PREFIX))
        ) {
            return JournalCandidateResult.Terminal("Journal reader identity does not match")
        }
        val rootBody = readerText(page.rootBlog.contentHtml)
        val canonical = when (val loaded = loadCanonicalEnvelope(
            rootBody,
            AppSyncSegmentPayloadKind.Journal,
            accountBinding,
        )) {
            is CanonicalEnvelopeResult.Valid -> loaded.body
            is CanonicalEnvelopeResult.Retryable ->
                return JournalCandidateResult.Retryable(loaded.reason)
            is CanonicalEnvelopeResult.Terminal ->
                return JournalCandidateResult.Terminal(loaded.reason)
        }
        val kind = if (rootBody.contains(AppSyncSegmentEnvelopeCodec.ROOT_MARKER)) {
            AppSyncRemoteBlogKind.JournalRoot
        } else {
            AppSyncRemoteBlogKind.Journal
        }
        return when (val validation = journalCodec.validate(canonical)) {
            is AppSyncJournalValidation.Valid -> {
                if (validation.envelope.payload.accountBinding != accountBinding) {
                    JournalCandidateResult.Terminal("Journal account binding does not match")
                } else {
                    JournalCandidateResult.Valid(
                        LoadedAppSyncJournal(
                            remoteId = candidate.blogId.value.toString(),
                            fingerprint = validation.envelope.fingerprint,
                            payload = validation.envelope.payload,
                        ),
                        kind,
                    )
                }
            }
            is AppSyncJournalValidation.Invalid ->
                JournalCandidateResult.Terminal(validation.reason)
        }
    }

    private suspend fun loadIndex(
        candidate: StoredAppSyncRemoteBlog,
        accountBinding: SyncAccountBinding,
    ): IndexCandidateResult {
        val page = when (val result = provider.fetchBlog(candidate.blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            AppSyncCloudResult.NotFound -> return IndexCandidateResult.NotFound
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            AppSyncCloudResult.Maintenance,
            -> return IndexCandidateResult.Retryable(result.describeForJournal())
            else -> return IndexCandidateResult.Terminal(result.describeForJournal())
        }
        if (page.blogInfo.title != APP_SYNC_INDEX_TITLE) {
            return IndexCandidateResult.Terminal("Index reader title does not match")
        }
        return when (val validation = indexCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
            is AppSyncIndexValidation.Valid -> {
                if (validation.envelope.payload.accountBinding != accountBinding) {
                    IndexCandidateResult.Terminal("Index account binding does not match")
                } else {
                    verifiedIndexCache = VerifiedIndex(
                        blogId = candidate.blogId,
                        payload = validation.envelope.payload,
                        fingerprint = validation.envelope.fingerprint,
                    )
                    IndexCandidateResult.Valid(
                        validation.envelope.payload,
                        validation.envelope.fingerprint,
                    )
                }
            }
            is AppSyncIndexValidation.Invalid -> IndexCandidateResult.Terminal(validation.reason)
        }
    }

    private suspend fun loadCanonicalEnvelope(
        rootBody: String,
        expectedKind: AppSyncSegmentPayloadKind,
        accountBinding: SyncAccountBinding,
    ): CanonicalEnvelopeResult {
        if (!rootBody.contains(AppSyncSegmentEnvelopeCodec.ROOT_MARKER)) {
            return CanonicalEnvelopeResult.Valid(rootBody)
        }
        val root = segmentCodec.decodeRoot(rootBody).getOrElse {
            return CanonicalEnvelopeResult.Terminal("Segmented root is invalid")
        }
        if (root.accountBinding != accountBinding.value ||
            root.kind != expectedKind.name.lowercase()
        ) {
            return CanonicalEnvelopeResult.Terminal("Segmented root binding does not match")
        }
        val segmentBodies = linkedMapOf<String, String>()
        val segmentBlogIds = mutableListOf<Long>()
        var nextId: String? = root.headBlogId
        repeat(root.segmentCount) {
            val id = nextId ?: return CanonicalEnvelopeResult.Terminal("Segment chain ended early")
            val blogId = id.toIntOrNull()?.let(::BlogId)
                ?: return CanonicalEnvelopeResult.Terminal("Segment Blog id is invalid")
            val page = when (val fetched = provider.fetchBlog(blogId)) {
                is AppSyncCloudResult.VerifiedSuccess -> fetched.value
                AppSyncCloudResult.NotFound ->
                    return CanonicalEnvelopeResult.Retryable("Committed segment is not visible yet")
                is AppSyncCloudResult.NetworkFailed,
                is AppSyncCloudResult.Timeout,
                AppSyncCloudResult.Maintenance,
                -> return CanonicalEnvelopeResult.Retryable(fetched.describeForJournal())
                else -> return CanonicalEnvelopeResult.Terminal(fetched.describeForJournal())
            }
            if (page.blogInfo.blogId != blogId ||
                !page.blogInfo.title.startsWith(AppSyncJournalDefaults.SEGMENT_TITLE_PREFIX)
            ) {
                return CanonicalEnvelopeResult.Terminal("Segment reader identity does not match")
            }
            val body = readerText(page.rootBlog.contentHtml)
            val segment = segmentCodec.decodeSegment(body).getOrElse {
                return CanonicalEnvelopeResult.Terminal("Segment payload is invalid")
            }
            segmentBodies[id] = body
            segmentBlogIds += blogId.value.toLong()
            nextId = segment.nextBlogId
        }
        return when (val reconstructed = segmentCodec.reconstruct(root, segmentBodies::get)) {
            is AppSyncSegmentReconstruction.Valid ->
                CanonicalEnvelopeResult.Valid(
                    reconstructed.canonicalEnvelope,
                    segmentBlogIds,
                )
            is AppSyncSegmentReconstruction.Invalid ->
                CanonicalEnvelopeResult.Terminal(reconstructed.reason)
        }
    }

    private fun readerText(html: String): String = try {
        Ksoup.parseBodyFragment(html).body().text()
    } catch (_: Throwable) {
        html
    }

    private suspend fun updateIndexBestEffort(
        accountBinding: SyncAccountBinding,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ) {
        val journals = store.loadKind(AppSyncRemoteBlogKind.Journal) +
            store.loadKind(AppSyncRemoteBlogKind.JournalRoot)
        if (journals.isEmpty()) return
        val existing = store.load(INDEX_REMOTE_KEY)
        val existingPayload = existing?.let { stored ->
            verifiedIndexCache
                ?.takeIf {
                    it.blogId == stored.blogId &&
                        it.fingerprint == stored.fingerprint &&
                        it.payload.accountBinding == accountBinding
                }
                ?.payload
                ?: (loadIndex(stored, accountBinding) as? IndexCandidateResult.Valid)?.payload
        }
        if (existing != null && existingPayload == null) return
        val payload = AppSyncIndexPayload(
            accountBinding = accountBinding,
            journals = journals.map {
                AppSyncIndexJournalReference(
                    replicaKey = it.remoteKey,
                    blogId = it.blogId.value,
                    fingerprint = it.fingerprint,
                )
            },
            checkpoints = (
                store.loadKind(AppSyncRemoteBlogKind.Checkpoint) +
                    store.loadKind(AppSyncRemoteBlogKind.CheckpointRoot)
                ).mapNotNull {
                val checkpointId = checkpointId(it.remoteKey)
                val fingerprint = it.fingerprint ?: return@mapNotNull null
                AppSyncIndexCheckpointReference(
                    checkpointId = checkpointId,
                    blogId = it.blogId.value,
                    fingerprint = fingerprint,
                )
            },
            retirements = existingPayload?.retirements.orEmpty(),
            updatedAtEpochMillis = nowMillis(),
        )
        val encoded = indexCodec.encode(payload)
        val expected = indexCodec.validate(encoded) as AppSyncIndexValidation.Valid
        val result = provider.submitBlog(
            AppSyncBlogWriteRequest(
                blogId = existing?.blogId,
                title = APP_SYNC_INDEX_TITLE,
                message = encoded,
                classSelection = classSelection,
                formHash = formHash,
            ),
        )
        val acknowledgement = (result as? AppSyncCloudResult.VerifiedSuccess)?.value ?: return
        val blogId = existing?.blogId
            ?: acknowledgement.candidateBlogIds.distinct().singleOrNull()
            ?: return
        val fingerprint = expected.envelope.fingerprint
        verifiedIndexCache = VerifiedIndex(blogId, expected.envelope.payload, fingerprint)
        store.save(
            StoredAppSyncRemoteBlog(
                remoteKey = INDEX_REMOTE_KEY,
                kind = AppSyncRemoteBlogKind.Index,
                blogId = blogId,
                classId = classSelection.existingClassId(),
                fingerprint = fingerprint,
                validatedAtEpochMillis = nowMillis(),
                contentUpdatedAtEpochMillis = payload.updatedAtEpochMillis,
            ),
        )
    }

    private suspend fun resolveClassSelection(
        accountBinding: SyncAccountBinding,
    ): ClassSelectionResult {
        store.loadClassId(accountBinding)?.let {
            return ClassSelectionResult.Success(AppSyncBlogClassSelection.Existing(it))
        }
        return when (val result = provider.fetchMyBlogs()) {
            is AppSyncCloudResult.VerifiedSuccess -> {
                val existing = result.value.blogClasses.firstOrNull {
                    it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME
                }
                existing?.let { store.saveClassId(accountBinding, it.id) }
                ClassSelectionResult.Success(
                    existing?.let { AppSyncBlogClassSelection.Existing(it.id) }
                        ?: AppSyncBlogClassSelection.Create(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME),
                )
            }
            is AppSyncCloudResult.NetworkFailed,
            is AppSyncCloudResult.Timeout,
            is AppSyncCloudResult.HttpFailed,
            AppSyncCloudResult.Maintenance,
            -> ClassSelectionResult.Retryable(result.describeForJournal())
            else -> ClassSelectionResult.Terminal(result.describeForJournal())
        }
    }

    private suspend fun fetchAllPages(
        classId: BlogClassId,
        firstPage: UserSpaceBlogPage?,
    ): BlogPagesResult {
        val pages = mutableListOf<UserSpaceBlogPage>()
        var pageIndex = 1
        var current = firstPage
        while (pageIndex <= MAX_DISCOVERY_PAGES) {
            val page = current ?: when (
                val result = provider.fetchMyBlogs(classId, pageIndex)
            ) {
                is AppSyncCloudResult.VerifiedSuccess -> result.value
                else -> return BlogPagesResult.Failure(result.toJournalLoadFailure())
            }
            pages += page
            val next = page.pageNav?.nextPageIndex
                ?: page.pageNav?.totalPages?.takeIf { pageIndex < it }?.let { pageIndex + 1 }
                ?: break
            if (next <= pageIndex) break
            pageIndex = next
            current = null
        }
        if (pageIndex > MAX_DISCOVERY_PAGES) {
            return BlogPagesResult.Failure(
                AppSyncJournalLoadResult.TerminalFailure("Journal discovery exceeded page limit"),
            )
        }
        return BlogPagesResult.Success(pages)
    }

    private fun saveJournal(
        candidate: StoredAppSyncRemoteBlog,
        journal: LoadedAppSyncJournal,
    ) {
        verifiedJournalCache[journal.payload.replicaKey()] = journal
        store.save(
            candidate.copy(
                remoteKey = journal.payload.replicaKey(),
                fingerprint = journal.fingerprint,
                validatedAtEpochMillis = nowMillis(),
                contentUpdatedAtEpochMillis = journal.payload.heartbeatAtEpochMillis,
            ),
        )
    }

    private fun saveCheckpoint(
        candidate: StoredAppSyncRemoteBlog,
        checkpoint: LoadedAppSyncCheckpoint,
    ) {
        verifiedCheckpointCache[checkpointRemoteKey(checkpoint.envelope.payload.checkpointId)] =
            checkpoint
        store.save(
            candidate.copy(
                remoteKey = checkpointRemoteKey(checkpoint.envelope.payload.checkpointId),
                fingerprint = checkpoint.envelope.fingerprint,
                validatedAtEpochMillis = nowMillis(),
                contentUpdatedAtEpochMillis = checkpoint.envelope.payload.createdAtEpochMillis,
            ),
        )
    }

    private fun AppSyncJournalPayload.replicaKey(): String =
        SyncReplicaKey(deviceId, deviceEpoch).stableKey

    private data class VerifiedIndex(
        val blogId: BlogId,
        val payload: AppSyncIndexPayload,
        val fingerprint: String,
    )

    private fun normalizeListTitle(title: String, className: String): String =
        title.removePrefix("[$className] ").trim()

    private fun AppSyncBlogClassSelection.existingClassId(): BlogClassId? =
        (this as? AppSyncBlogClassSelection.Existing)?.classId

    private fun completedRetirements(
        accountBinding: SyncAccountBinding,
    ): List<AppSyncJournalRetirementIntent> =
        retirementIntents(accountBinding).filter {
            it.stage == AppSyncJournalRetirementStage.Completed ||
                it.stage == AppSyncJournalRetirementStage.Absorbed
        }

    private fun AppSyncJournalRetirementIntent.matches(
        journal: LoadedAppSyncJournal,
    ): Boolean =
        replicaKey == journal.payload.replicaKey() &&
            sourceBlogId.toString() == journal.remoteId &&
            fingerprint == journal.fingerprint &&
            publishedThroughSequence ==
            journal.payload.resolvedPublishedThroughSequence()

    private sealed interface JournalCandidateResult {
        data class Valid(
            val journal: LoadedAppSyncJournal,
            val kind: AppSyncRemoteBlogKind = AppSyncRemoteBlogKind.Journal,
        ) : JournalCandidateResult
        data object NotFound : JournalCandidateResult
        data class Retryable(val reason: String) : JournalCandidateResult
        data class Terminal(val reason: String) : JournalCandidateResult
    }

    private sealed interface IndexCandidateResult {
        data class Valid(
            val payload: AppSyncIndexPayload,
            val fingerprint: String,
        ) : IndexCandidateResult
        data object NotFound : IndexCandidateResult
        data class Retryable(val reason: String) : IndexCandidateResult
        data class Terminal(val reason: String) : IndexCandidateResult
    }

    private sealed interface CheckpointCandidateResult {
        data class Valid(
            val checkpoint: LoadedAppSyncCheckpoint,
            val kind: AppSyncRemoteBlogKind = AppSyncRemoteBlogKind.Checkpoint,
        ) : CheckpointCandidateResult
        data object NotFound : CheckpointCandidateResult
        data class Retryable(val reason: String) : CheckpointCandidateResult
        data class Terminal(val reason: String) : CheckpointCandidateResult
    }

    private sealed interface CanonicalEnvelopeResult {
        data class Valid(
            val body: String,
            val segmentBlogIds: List<Long> = emptyList(),
        ) : CanonicalEnvelopeResult
        data class Retryable(val reason: String) : CanonicalEnvelopeResult
        data class Terminal(val reason: String) : CanonicalEnvelopeResult
    }

    private sealed interface ClassSelectionResult {
        data class Success(val selection: AppSyncBlogClassSelection) : ClassSelectionResult
        data class Retryable(val reason: String) : ClassSelectionResult
        data class Terminal(val reason: String) : ClassSelectionResult
    }

    private sealed interface BlogPagesResult {
        data class Success(val pages: List<UserSpaceBlogPage>) : BlogPagesResult
        data class Failure(val result: AppSyncJournalLoadResult) : BlogPagesResult
    }

    private companion object {
        const val INDEX_REMOTE_KEY = "index"
        const val CHECKPOINT_REMOTE_KEY_PREFIX = "checkpoint:"
        const val MAX_DISCOVERY_PAGES = 100
    }
}

private fun AppSyncCloudResult<*>.toCloudResetFailure(): AppSyncCloudResetResult = when (this) {
    AppSyncCloudResult.NotLoggedIn,
    is AppSyncCloudResult.FormExpired,
    -> AppSyncCloudResetResult.FormExpired
    AppSyncCloudResult.Maintenance,
    is AppSyncCloudResult.NetworkFailed,
    is AppSyncCloudResult.Timeout,
    is AppSyncCloudResult.HttpFailed,
    -> AppSyncCloudResetResult.RetryableFailure(describeForJournal())
    else -> AppSyncCloudResetResult.TerminalFailure(describeForJournal())
}

private fun AppSyncCloudResult<*>.toCheckpointRetentionFailure():
    AppSyncCheckpointRetentionResult = when (this) {
    AppSyncCloudResult.NotLoggedIn,
    is AppSyncCloudResult.FormExpired,
    -> AppSyncCheckpointRetentionResult.FormExpired
    AppSyncCloudResult.Maintenance,
    is AppSyncCloudResult.NetworkFailed,
    is AppSyncCloudResult.Timeout,
    is AppSyncCloudResult.HttpFailed,
    is AppSyncCloudResult.AcknowledgedButUnverified,
    -> AppSyncCheckpointRetentionResult.RetryableFailure(describeForJournal())
    else -> AppSyncCheckpointRetentionResult.TerminalFailure(describeForJournal())
    }

private fun AppSyncCloudResult<*>.toJournalLoadFailure(): AppSyncJournalLoadResult = when (this) {
    AppSyncCloudResult.NotLoggedIn -> AppSyncJournalLoadResult.NotLoggedIn
    AppSyncCloudResult.Maintenance,
    is AppSyncCloudResult.NetworkFailed,
    is AppSyncCloudResult.Timeout,
    is AppSyncCloudResult.HttpFailed,
    -> AppSyncJournalLoadResult.RetryableFailure(describeForJournal())
    else -> AppSyncJournalLoadResult.TerminalFailure(describeForJournal())
}

internal fun AppSyncCloudResult<*>.describeForJournal(): String = when (this) {
    is AppSyncCloudResult.VerifiedSuccess -> "verified"
    is AppSyncCloudResult.AcknowledgedButUnverified -> reason
    AppSyncCloudResult.NotFound -> "not found"
    AppSyncCloudResult.NotLoggedIn -> "not logged in"
    is AppSyncCloudResult.NoPermission -> reason
    AppSyncCloudResult.Maintenance -> "maintenance"
    is AppSyncCloudResult.FormExpired -> messageText ?: "form expired"
    is AppSyncCloudResult.ValidationFailed -> reason
    is AppSyncCloudResult.Conflict -> reason
    is AppSyncCloudResult.HttpFailed -> messageText ?: "HTTP $statusCode"
    is AppSyncCloudResult.NetworkFailed -> reason
    is AppSyncCloudResult.Timeout -> reason
    is AppSyncCloudResult.ParseFailed -> reason
    is AppSyncCloudResult.UnknownFailed -> reason
}
