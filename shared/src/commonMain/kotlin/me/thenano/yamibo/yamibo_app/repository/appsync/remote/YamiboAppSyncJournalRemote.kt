package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog
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
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : AppSyncJournalRemote {
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
        for (summary in pages.flatMap { it.blogs }) {
            val title = normalizeListTitle(
                summary.title,
                AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
            )
            val candidate = StoredAppSyncRemoteBlog(
                remoteKey = "delete-candidate:${summary.bId.value}",
                kind = AppSyncRemoteBlogKind.Journal,
                blogId = summary.bId,
                classId = syncClass.id,
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            )
            when {
                title.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) -> {
                    when (val result = loadJournal(candidate, accountBinding)) {
                        is JournalCandidateResult.Valid -> verifiedIds += summary.bId
                        is JournalCandidateResult.Retryable ->
                            return AppSyncCloudResetResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
                title == APP_SYNC_INDEX_TITLE -> {
                    when (val result = loadIndex(candidate, accountBinding)) {
                        is IndexCandidateResult.Valid -> verifiedIds += summary.bId
                        is IndexCandidateResult.Retryable ->
                            return AppSyncCloudResetResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
                title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) -> {
                    when (val result = loadCheckpoint(candidate, accountBinding)) {
                        is CheckpointCandidateResult.Valid -> verifiedIds += summary.bId
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
        return AppSyncCloudResetResult.Verified(deleted)
    }

    override suspend fun loadJournals(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncJournalLoadResult = discoverCurrentLinks(accountBinding)

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
        return links
    }

    override suspend fun publishOwnJournal(
        payload: AppSyncJournalPayload,
        expectedFingerprint: String?,
        formHash: FormHash,
    ): AppSyncJournalPublishResult {
        val remoteKey = payload.replicaKey()
        val cached = store.load(remoteKey)
        if (cached != null) {
            when (val preflight = loadJournal(cached, payload.accountBinding)) {
                is JournalCandidateResult.Valid -> {
                    if (preflight.journal.payload.writerNonce != payload.writerNonce) {
                        return AppSyncJournalPublishResult.Conflict(
                            "Journal writer nonce belongs to another installation",
                        )
                    }
                    if (expectedFingerprint != null &&
                        preflight.journal.fingerprint != expectedFingerprint
                    ) {
                        return AppSyncJournalPublishResult.Conflict(
                            "Journal changed after the caller's preflight",
                        )
                    }
                }
                JournalCandidateResult.NotFound -> {
                    store.remove(remoteKey)
                    if (expectedFingerprint != null) {
                        return AppSyncJournalPublishResult.Conflict(
                            "Previously verified journal is missing",
                        )
                    }
                }
                is JournalCandidateResult.Retryable ->
                    return AppSyncJournalPublishResult.Unknown(preflight.reason)
                is JournalCandidateResult.Terminal ->
                    return AppSyncJournalPublishResult.TerminalFailure(preflight.reason)
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
        if (encoded.length > SAFE_BLOG_BODY_CHAR_LIMIT) {
            return AppSyncJournalPublishResult.StoragePressure(
                encoded.length,
                SAFE_BLOG_BODY_CHAR_LIMIT,
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

        val candidateIds = buildList {
            cached?.blogId?.let(::add)
            addAll(acknowledgement.candidateBlogIds)
        }.distinct()
        for (candidateId in candidateIds) {
            val candidate = StoredAppSyncRemoteBlog(
                remoteKey = remoteKey,
                kind = AppSyncRemoteBlogKind.Journal,
                blogId = candidateId,
                classId = cached?.classId ?: classSelection.existingClassId(),
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            )
            when (val loaded = loadJournal(candidate, payload.accountBinding)) {
                is JournalCandidateResult.Valid -> {
                    val journal = loaded.journal
                    if (journal.fingerprint == expectedEnvelope.envelope.fingerprint &&
                        journal.payload.operations.map { it.operationId } ==
                        payload.operations.map { it.operationId }
                    ) {
                        saveJournal(candidate, journal)
                        updateIndexBestEffort(payload.accountBinding, classSelection, formHash)
                        return AppSyncJournalPublishResult.Verified(journal)
                    }
                }
                else -> Unit
            }
        }

        return when (val discovered = discoverAll(payload.accountBinding)) {
            is AppSyncJournalLoadResult.Success -> {
                val journal = discovered.journals.singleOrNull {
                    it.payload.deviceId == payload.deviceId &&
                        it.payload.deviceEpoch == payload.deviceEpoch &&
                        it.fingerprint == expectedEnvelope.envelope.fingerprint
                }
                if (journal != null) {
                    updateIndexBestEffort(payload.accountBinding, classSelection, formHash)
                    AppSyncJournalPublishResult.Verified(journal)
                } else {
                    AppSyncJournalPublishResult.Unknown(
                        "Blog POST was acknowledged but exact journal reload verification failed",
                    )
                }
            }
            is AppSyncJournalLoadResult.RetryableFailure ->
                AppSyncJournalPublishResult.Unknown(discovered.reason)
            AppSyncJournalLoadResult.NotLoggedIn -> AppSyncJournalPublishResult.FormExpired
            is AppSyncJournalLoadResult.TerminalFailure ->
                AppSyncJournalPublishResult.TerminalFailure(discovered.reason)
        }
    }

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
        if (encoded.length > SAFE_BLOG_BODY_CHAR_LIMIT) {
            return AppSyncCheckpointPublishResult.StoragePressure(
                encoded.length,
                SAFE_BLOG_BODY_CHAR_LIMIT,
            )
        }
        val expected = checkpointCodec.validate(encoded) as AppSyncCheckpointValidation.Valid
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
        for (blogId in acknowledgement.candidateBlogIds.distinct()) {
            val candidate = StoredAppSyncRemoteBlog(
                remoteKey = checkpointRemoteKey(payload.checkpointId),
                kind = AppSyncRemoteBlogKind.Checkpoint,
                blogId = blogId,
                classId = classSelection.existingClassId(),
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            )
            when (val loaded = loadCheckpoint(candidate, payload.accountBinding)) {
                is CheckpointCandidateResult.Valid -> {
                    val checkpoint = loaded.checkpoint
                    if (checkpoint.envelope.payload.checkpointId == payload.checkpointId &&
                        checkpoint.envelope.fingerprint == expected.envelope.fingerprint &&
                        checkpoint.envelope.payload.coverage == payload.coverage
                    ) {
                        saveCheckpoint(candidate, checkpoint)
                        updateIndexBestEffort(payload.accountBinding, classSelection, formHash)
                        return AppSyncCheckpointPublishResult.Verified(checkpoint)
                    }
                }
                else -> Unit
            }
        }
        return when (val discovered = discoverAll(payload.accountBinding)) {
            is AppSyncJournalLoadResult.Success -> {
                discovered.checkpoints.singleOrNull {
                    it.envelope.payload.checkpointId == payload.checkpointId &&
                        it.envelope.fingerprint == expected.envelope.fingerprint
                }?.let(AppSyncCheckpointPublishResult::Verified)
                    ?: AppSyncCheckpointPublishResult.Unknown(
                        "Checkpoint POST was acknowledged but exact reload verification failed",
                    )
            }
            is AppSyncJournalLoadResult.RetryableFailure ->
                AppSyncCheckpointPublishResult.Unknown(discovered.reason)
            AppSyncJournalLoadResult.NotLoggedIn -> AppSyncCheckpointPublishResult.FormExpired
            is AppSyncJournalLoadResult.TerminalFailure ->
                AppSyncCheckpointPublishResult.TerminalFailure(discovered.reason)
        }
    }

    override suspend fun enforceCheckpointRetention(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        maximumCheckpoints: Int,
    ): AppSyncCheckpointRetentionResult {
        if (maximumCheckpoints <= 0) {
            return AppSyncCheckpointRetentionResult.TerminalFailure(
                "Checkpoint retention limit must be positive",
            )
        }
        val cached = store.loadKind(AppSyncRemoteBlogKind.Checkpoint)
            .filter {
                it.remoteKey.startsWith(CHECKPOINT_REMOTE_KEY_PREFIX) &&
                    it.fingerprint != null &&
                    it.contentUpdatedAtEpochMillis != null
            }
        if (cached.size <= maximumCheckpoints) {
            return AppSyncCheckpointRetentionResult.NotNeeded
        }
        val retained = cached
            .sortedWith(
                compareByDescending<StoredAppSyncRemoteBlog> {
                    it.contentUpdatedAtEpochMillis
                }.thenByDescending {
                    it.validatedAtEpochMillis
                }.thenByDescending {
                    it.blogId.value
                },
            )
            .take(maximumCheckpoints)
        val retainedRemoteKeys = retained.mapTo(hashSetOf()) { it.remoteKey }
        val toDelete = cached.filterNot { it.remoteKey in retainedRemoteKeys }

        var deleted = 0
        for (checkpointBlog in toDelete) {
            when (
                val result = provider.deleteBlog(
                    AppSyncBlogDeleteRequest(
                        blogId = checkpointBlog.blogId,
                        formHash = formHash,
                    ),
                )
            ) {
                is AppSyncCloudResult.VerifiedSuccess,
                AppSyncCloudResult.NotFound,
                -> {
                    deleted += 1
                    store.remove(checkpointBlog.remoteKey)
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
        return AppSyncCheckpointRetentionResult.Verified(
            retainedCheckpointIds = retained.mapTo(linkedSetOf()) {
                it.remoteKey.removePrefix(CHECKPOINT_REMOTE_KEY_PREFIX)
            },
            deletedBlogCount = deleted,
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
        store.loadKind(AppSyncRemoteBlogKind.Checkpoint).forEach {
            cachedCheckpoints[it.remoteKey] = it
        }
        val index = store.load(INDEX_REMOTE_KEY)
        if (index != null) {
            when (val loadedIndex = preloadedIndex ?: loadIndex(index, accountBinding)) {
                is IndexCandidateResult.Valid -> {
                    loadedIndex.payload.journals.forEach { reference ->
                        if (reference.replicaKey !in cachedJournals) {
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
                    }
                    loadedIndex.payload.checkpoints.forEach { reference ->
                        val remoteKey = checkpointRemoteKey(reference.checkpointId)
                        if (remoteKey !in cachedCheckpoints) {
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
                }
                IndexCandidateResult.NotFound -> store.remove(INDEX_REMOTE_KEY)
                is IndexCandidateResult.Retryable -> return null
                is IndexCandidateResult.Terminal -> Unit
            }
        }
        if (cachedJournals.isEmpty() && cachedCheckpoints.isEmpty()) return null

        val loadedJournals = mutableListOf<LoadedAppSyncJournal>()
        for (candidate in cachedJournals.values) {
            when (val result = loadJournal(candidate, accountBinding)) {
                is JournalCandidateResult.Valid -> {
                    saveJournal(candidate, result.journal)
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
            when (val result = loadCheckpoint(candidate, accountBinding)) {
                is CheckpointCandidateResult.Valid -> {
                    saveCheckpoint(candidate, result.checkpoint)
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
            journals = loadedJournals.distinctBy { it.payload.replicaKey() },
            checkpoints = loadedCheckpoints.distinctBy { it.envelope.payload.checkpointId },
        )
    }

    private suspend fun discoverCurrentLinks(
        accountBinding: SyncAccountBinding,
    ): AppSyncJournalLoadResult {
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
        store.clear()

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
                    val newestListedContentAt = firstPage.blogs
                        .asSequence()
                        .filter {
                            val title = normalizeListTitle(
                                it.title,
                                AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
                            )
                            title.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) ||
                                title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX)
                        }
                        .maxOfOrNull { it.timeInfo.epoch }
                    if (newestListedContentAt == null ||
                        newestListedContentAt <= latestIndexSummary.timeInfo.epoch
                    ) {
                        store.save(
                            index.copy(
                                fingerprint = loadedIndex.fingerprint,
                                validatedAtEpochMillis = nowMillis(),
                            ),
                        )
                        loadCachedState(accountBinding, loadedIndex)?.let { return it }
                    }
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
        for (summary in pages.flatMap { it.blogs }) {
            val normalizedTitle = normalizeListTitle(
                summary.title,
                AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
            )
            when {
                normalizedTitle.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX) -> {
                    val candidate = StoredAppSyncRemoteBlog(
                        remoteKey = "candidate:${summary.bId.value}",
                        kind = AppSyncRemoteBlogKind.Journal,
                        blogId = summary.bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = summary.timeInfo.epoch * 1_000L,
                    )
                    when (val result = loadJournal(candidate, accountBinding)) {
                        is JournalCandidateResult.Valid -> {
                            val remoteKey = result.journal.payload.replicaKey()
                            saveJournal(candidate.copy(remoteKey = remoteKey), result.journal)
                            loaded += result.journal
                        }
                        is JournalCandidateResult.Retryable ->
                            return AppSyncJournalLoadResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
                normalizedTitle == APP_SYNC_INDEX_TITLE -> {
                    val index = StoredAppSyncRemoteBlog(
                        remoteKey = INDEX_REMOTE_KEY,
                        kind = AppSyncRemoteBlogKind.Index,
                        blogId = summary.bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = summary.timeInfo.epoch * 1_000L,
                    )
                    when (val result = loadIndex(index, accountBinding)) {
                        is IndexCandidateResult.Valid -> store.save(
                            index.copy(
                                fingerprint = result.fingerprint,
                                validatedAtEpochMillis = nowMillis(),
                            ),
                        )
                        else -> Unit
                    }
                }
                normalizedTitle.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) -> {
                    val candidate = StoredAppSyncRemoteBlog(
                        remoteKey = "checkpoint-candidate:${summary.bId.value}",
                        kind = AppSyncRemoteBlogKind.Checkpoint,
                        blogId = summary.bId,
                        classId = classId,
                        fingerprint = null,
                        validatedAtEpochMillis = 0,
                        contentUpdatedAtEpochMillis = summary.timeInfo.epoch * 1_000L,
                    )
                    when (val result = loadCheckpoint(candidate, accountBinding)) {
                        is CheckpointCandidateResult.Valid -> {
                            saveCheckpoint(candidate, result.checkpoint)
                            checkpoints += result.checkpoint
                        }
                        is CheckpointCandidateResult.Retryable ->
                            return AppSyncJournalLoadResult.RetryableFailure(result.reason)
                        else -> Unit
                    }
                }
            }
        }
        return AppSyncJournalLoadResult.Success(
            loaded.distinctBy { it.payload.replicaKey() },
            checkpoints.distinctBy { it.envelope.payload.checkpointId },
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
            !page.blogInfo.title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX)
        ) {
            return CheckpointCandidateResult.Terminal("Checkpoint reader identity does not match")
        }
        return when (val validation = checkpointCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
            is AppSyncCheckpointValidation.Valid -> {
                if (validation.envelope.payload.accountBinding != accountBinding) {
                    CheckpointCandidateResult.Terminal("Checkpoint account binding does not match")
                } else {
                    CheckpointCandidateResult.Valid(
                        LoadedAppSyncCheckpoint(
                            remoteId = candidate.blogId.value.toString(),
                            envelope = validation.envelope,
                        ),
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
            !page.blogInfo.title.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX)
        ) {
            return JournalCandidateResult.Terminal("Journal reader identity does not match")
        }
        return when (val validation = journalCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
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
                    IndexCandidateResult.Valid(
                        validation.envelope.payload,
                        validation.envelope.fingerprint,
                    )
                }
            }
            is AppSyncIndexValidation.Invalid -> IndexCandidateResult.Terminal(validation.reason)
        }
    }

    private suspend fun updateIndexBestEffort(
        accountBinding: SyncAccountBinding,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ) {
        val journals = store.loadKind(AppSyncRemoteBlogKind.Journal)
        if (journals.isEmpty()) return
        val existing = store.load(INDEX_REMOTE_KEY)
        val payload = AppSyncIndexPayload(
            accountBinding = accountBinding,
            journals = journals.map {
                AppSyncIndexJournalReference(
                    replicaKey = it.remoteKey,
                    blogId = it.blogId.value,
                    fingerprint = it.fingerprint,
                )
            },
            checkpoints = store.loadKind(AppSyncRemoteBlogKind.Checkpoint).mapNotNull {
                val checkpointId = checkpointId(it.remoteKey)
                val fingerprint = it.fingerprint ?: return@mapNotNull null
                AppSyncIndexCheckpointReference(
                    checkpointId = checkpointId,
                    blogId = it.blogId.value,
                    fingerprint = fingerprint,
                )
            },
            updatedAtEpochMillis = nowMillis(),
        )
        val encoded = indexCodec.encode(payload)
        val result = provider.submitBlog(
            AppSyncBlogWriteRequest(
                blogId = existing?.blogId,
                title = APP_SYNC_INDEX_TITLE,
                message = encoded,
                classSelection = classSelection,
                formHash = formHash,
            ),
        )
        val candidateIds = when (result) {
            is AppSyncCloudResult.VerifiedSuccess ->
                listOfNotNull(existing?.blogId) + result.value.candidateBlogIds
            else -> return
        }.distinct()
        candidateIds.forEach { blogId ->
            val candidate = StoredAppSyncRemoteBlog(
                remoteKey = INDEX_REMOTE_KEY,
                kind = AppSyncRemoteBlogKind.Index,
                blogId = blogId,
                classId = classSelection.existingClassId(),
                fingerprint = null,
                validatedAtEpochMillis = 0,
                contentUpdatedAtEpochMillis = null,
            )
            when (val verified = loadIndex(candidate, accountBinding)) {
                is IndexCandidateResult.Valid -> {
                    store.save(
                        candidate.copy(
                            fingerprint = verified.fingerprint,
                            validatedAtEpochMillis = nowMillis(),
                        ),
                    )
                    return
                }
                else -> Unit
            }
        }
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

    private fun normalizeListTitle(title: String, className: String): String =
        title.removePrefix("[$className] ").trim()

    private fun AppSyncBlogClassSelection.existingClassId(): BlogClassId? =
        (this as? AppSyncBlogClassSelection.Existing)?.classId

    private sealed interface JournalCandidateResult {
        data class Valid(val journal: LoadedAppSyncJournal) : JournalCandidateResult
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
        data class Valid(val checkpoint: LoadedAppSyncCheckpoint) : CheckpointCandidateResult
        data object NotFound : CheckpointCandidateResult
        data class Retryable(val reason: String) : CheckpointCandidateResult
        data class Terminal(val reason: String) : CheckpointCandidateResult
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
        const val SAFE_BLOG_BODY_CHAR_LIMIT = 50_000
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

private fun AppSyncCloudResult<*>.describeForJournal(): String = when (this) {
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
