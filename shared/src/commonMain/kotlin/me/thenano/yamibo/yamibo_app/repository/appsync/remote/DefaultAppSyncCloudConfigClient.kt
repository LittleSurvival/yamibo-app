package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBlogConfig
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudNotice
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudNoticeSink
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncStagedCloudConfig
import me.thenano.yamibo.yamibo_app.repository.appsync.model.NoOpAppSyncCloudNoticeSink
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncBlogConfigStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncBlogConfig
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class DefaultAppSyncCloudConfigClient(
    private val provider: AppSyncBlogProvider,
    private val store: AppSyncBlogConfigStore,
    private val noticeSink: AppSyncCloudNoticeSink = NoOpAppSyncCloudNoticeSink,
    private val envelopeCodec: AppSyncCloudEnvelopeCodec = AppSyncCloudEnvelopeCodec(),
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : AppSyncCloudConfigClient {
    override suspend fun createBlogConfig(
        encodedText: String,
        formHash: FormHash,
    ): AppSyncCloudResult<AppSyncBlogConfig> = guarded {
        val firstPage = when (val result = provider.fetchMyBlogs()) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        val existingClass = firstPage.blogClasses
            .firstOrNull { it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME }
        val classSelection = existingClass?.let {
            AppSyncBlogClassSelection.Existing(it.id)
        } ?: AppSyncBlogClassSelection.Create(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME)

        val updatedAt = nowMillis()
        val body = envelopeCodec.encode(encodedText, updatedAt)
        val expectedFingerprint = envelopeCodec.fingerprintFor(encodedText, updatedAt)
        val acknowledgement = when (
            val result = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = null,
                    title = AppSyncCloudConfigDefaults.BLOG_NAME,
                    message = body,
                    classSelection = classSelection,
                    formHash = formHash,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }

        when (
            val verified = discover(
                expectedFingerprint = expectedFingerprint,
                preferredBlogIds = acknowledgement.candidateBlogIds,
                allowStoreClear = false,
                persistResult = true,
                deleteDamagedCandidates = true,
                formHash = formHash,
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> verified
            else -> AppSyncCloudResult.AcknowledgedButUnverified(
                messageText = acknowledgement.messageText,
                reason = "Create was acknowledged but reload verification failed: ${verified.describe()}",
                candidateBlogId = acknowledgement.candidateBlogIds.firstOrNull(),
            )
        }
    }

    override suspend fun findBlogConfig(
        formHash: FormHash,
    ): AppSyncCloudResult<AppSyncBlogConfig> =
        findBlogConfigInternal(
            persistVerifiedMetadata = true,
            persistDiscoveredMetadata = true,
            deleteDamagedCandidates = true,
            formHash = formHash,
        )

    override suspend fun inspectBlogConfig(): AppSyncCloudResult<AppSyncBlogConfig> =
        findBlogConfigInternal(
            persistVerifiedMetadata = false,
            persistDiscoveredMetadata = true,
            deleteDamagedCandidates = false,
            formHash = null,
        )

    private suspend fun findBlogConfigInternal(
        persistVerifiedMetadata: Boolean,
        persistDiscoveredMetadata: Boolean,
        deleteDamagedCandidates: Boolean,
        formHash: FormHash?,
    ): AppSyncCloudResult<AppSyncBlogConfig> = guarded {
        val stored = store.load()
        if (stored != null && stored.blogName == AppSyncCloudConfigDefaults.BLOG_NAME) {
            when (
                val verified = verifyCandidate(
                    Candidate(
                        blogId = stored.blogId,
                        classId = stored.classId,
                        listUpdatedAtEpochSeconds = 0L,
                    ),
                )
            ) {
                is AppSyncCloudResult.VerifiedSuccess -> {
                    val completed = when (val result = completeClassId(verified.value)) {
                        is AppSyncCloudResult.VerifiedSuccess -> result.value
                        else -> return@guarded result.propagateFailure()
                    }
                    return@guarded if (
                        persistVerifiedMetadata ||
                        stored.classId == null && completed.config.classId != null
                    ) {
                        persistVerified(completed)
                    } else {
                        AppSyncCloudResult.VerifiedSuccess(completed.config)
                    }
                }
                is AppSyncCloudResult.NotFound,
                is AppSyncCloudResult.ValidationFailed,
                -> Unit
                else -> return@guarded verified.propagateFailure()
            }
        }
        discover(
            allowStoreClear = persistVerifiedMetadata && stored != null,
            persistResult = persistDiscoveredMetadata,
            deleteDamagedCandidates = deleteDamagedCandidates,
            formHash = formHash,
        )
    }

    override suspend fun loadBlogConfig(
        blogId: BlogId,
    ): AppSyncCloudResult<String> = guarded {
        val staged = when (val result = loadBlogConfigReadOnly(blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        when (val committed = commitVerifiedBlogConfig(staged.config)) {
            is AppSyncCloudResult.VerifiedSuccess ->
                AppSyncCloudResult.VerifiedSuccess(staged.encodedSnapshot)
            else -> committed.propagateFailure()
        }
    }

    override suspend fun loadBlogConfigReadOnly(
        blogId: BlogId,
    ): AppSyncCloudResult<AppSyncStagedCloudConfig> = guarded {
        val stored = store.load()
        val verified = when (
            val result = verifyCandidate(
                Candidate(
                    blogId = blogId,
                    classId = stored?.takeIf { it.blogId == blogId }?.classId,
                    listUpdatedAtEpochSeconds = 0L,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        val completed = when (val result = completeClassId(verified)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        AppSyncCloudResult.VerifiedSuccess(
            AppSyncStagedCloudConfig(
                config = completed.config,
                encodedSnapshot = completed.envelope.encodedSnapshot,
            ),
        )
    }

    override suspend fun commitVerifiedBlogConfig(
        config: AppSyncBlogConfig,
    ): AppSyncCloudResult<Unit> = guarded {
        store.save(config.toStored())
        AppSyncCloudResult.VerifiedSuccess(Unit)
    }

    override suspend fun updateBlogConfig(
        blogId: BlogId,
        encodedText: String,
        formHash: FormHash,
        expectedRemoteFingerprint: String?,
    ): AppSyncCloudResult<AppSyncBlogConfig> = guarded {
        val current = when (
            val result = verifyCandidate(
                Candidate(
                    blogId = blogId,
                    classId = store.load()?.takeIf { it.blogId == blogId }?.classId,
                    listUpdatedAtEpochSeconds = 0L,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        val expectedBaseFingerprint =
            expectedRemoteFingerprint ?: current.envelope.fingerprint
        if (current.envelope.fingerprint != expectedBaseFingerprint) {
            return@guarded AppSyncCloudResult.Conflict(
                reason = "Remote config changed after preflight discovery",
                candidateBlogIds = listOf(blogId),
            )
        }
        val classId = current.config.classId ?: when (val resolved = resolveClassId(blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> resolved.value
            else -> return@guarded resolved.propagateFailure()
        }
        val preWrite = when (
            val result = verifyCandidate(
                Candidate(
                    blogId = blogId,
                    classId = classId,
                    listUpdatedAtEpochSeconds = 0L,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }
        if (preWrite.envelope.fingerprint != expectedBaseFingerprint) {
            return@guarded AppSyncCloudResult.Conflict(
                reason = "Remote config changed immediately before update",
                candidateBlogIds = listOf(blogId),
            )
        }

        val updatedAt = nowMillis()
        val body = envelopeCodec.encode(encodedText, updatedAt)
        val expectedFingerprint = envelopeCodec.fingerprintFor(encodedText, updatedAt)
        val acknowledgement = when (
            val result = provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = blogId,
                    title = AppSyncCloudConfigDefaults.BLOG_NAME,
                    message = body,
                    classSelection = AppSyncBlogClassSelection.Existing(classId),
                    formHash = formHash,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded result.propagateFailure()
        }

        val reloaded = when (
            val result = verifyCandidate(
                Candidate(
                    blogId = blogId,
                    classId = classId,
                    listUpdatedAtEpochSeconds = 0L,
                ),
            )
        ) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return@guarded AppSyncCloudResult.AcknowledgedButUnverified(
                messageText = acknowledgement.messageText,
                reason = "Update was acknowledged but reload verification failed: ${result.describe()}",
                candidateBlogId = blogId,
            )
        }
        if (reloaded.envelope.fingerprint != expectedFingerprint) {
            return@guarded AppSyncCloudResult.AcknowledgedButUnverified(
                messageText = acknowledgement.messageText,
                reason = "Update reload returned a different config fingerprint",
                candidateBlogId = blogId,
            )
        }
        persistVerified(reloaded)
    }

    override suspend fun deleteBlogConfig(
        blogId: BlogId,
        formHash: FormHash,
    ): AppSyncCloudResult<Unit> = guarded {
        deleteBlogConfigInternal(
            blogId = blogId,
            formHash = formHash,
            requireDamaged = false,
            mutateStore = true,
        )
    }

    private suspend fun discover(
        expectedFingerprint: String? = null,
        preferredBlogIds: List<BlogId> = emptyList(),
        allowStoreClear: Boolean = true,
        persistResult: Boolean = true,
        deleteDamagedCandidates: Boolean = true,
        formHash: FormHash? = null,
    ): AppSyncCloudResult<AppSyncBlogConfig> {
        val initial = when (val result = provider.fetchMyBlogs()) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.propagateFailure()
        }
        val configClass = initial.blogClasses
            .firstOrNull { it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME }
        val allClassNames = initial.blogClasses.associate { it.name to it.id }

        val candidates = linkedMapOf<BlogId, Candidate>()
        if (configClass != null) {
            when (val pages = fetchAllBlogPages(configClass.id)) {
                is AppSyncCloudResult.VerifiedSuccess -> {
                    pages.value.forEach { page ->
                        collectCandidates(
                            page = page,
                            classId = configClass.id,
                            classNames = allClassNames,
                            destination = candidates,
                        )
                    }
                }
                else -> return pages.propagateFailure()
            }
        }
        if (candidates.isEmpty()) {
            when (val pages = fetchAllBlogPages(blogClassId = null, firstPage = initial)) {
                is AppSyncCloudResult.VerifiedSuccess -> {
                    pages.value.forEach { page ->
                        collectCandidates(
                            page = page,
                            classId = null,
                            classNames = allClassNames,
                            destination = candidates,
                        )
                    }
                }
                else -> return pages.propagateFailure()
            }
        }

        val valid = mutableListOf<VerifiedCandidate>()
        val damaged = mutableListOf<VerifiedCandidate>()
        var collisionFound = false
        for (candidate in candidates.values) {
            when (val result = verifyCandidate(candidate)) {
                is AppSyncCloudResult.VerifiedSuccess -> valid += result.value
                is AppSyncCloudResult.ValidationFailed -> {
                    if (result.markerPresent) {
                        damaged += VerifiedCandidate.damaged(candidate, result.reason)
                    } else {
                        collisionFound = true
                    }
                }
                is AppSyncCloudResult.NotFound -> Unit
                else -> return result.propagateFailure()
            }
        }

        val notices = mutableListOf<AppSyncCloudNotice>()
        if (deleteDamagedCandidates) {
            for ((config) in damaged) {
                when (
                    val deleted = deleteBlogConfigInternal(
                        blogId = config.blogId,
                        formHash = requireNotNull(formHash) {
                            "Mutating discovery requires FormHash"
                        },
                        requireDamaged = true,
                        mutateStore = false,
                    )
                ) {
                    is AppSyncCloudResult.VerifiedSuccess -> {
                        val notice = AppSyncCloudNotice.DamagedBlogDeleted(config.blogId)
                        notices += notice
                        emitNotice(notice)
                    }
                    else -> return deleted.propagateFailure()
                }
            }
        }

        val eligible = if (expectedFingerprint == null) {
            valid
        } else {
            valid.filter { it.envelope.fingerprint == expectedFingerprint }
        }
        if (eligible.isEmpty()) {
            val isAuthoritativeAbsence =
                expectedFingerprint == null && !collisionFound && valid.isEmpty()
            if (isAuthoritativeAbsence && allowStoreClear) {
                when (val cleared = clearStoredConfig()) {
                    is AppSyncCloudResult.VerifiedSuccess -> Unit
                    else -> return cleared.propagateFailure()
                }
            }
            return if (collisionFound || valid.isNotEmpty()) {
                AppSyncCloudResult.ValidationFailed(
                    reason = if (valid.isNotEmpty()) {
                        "No valid config blog matched the expected fingerprint"
                    } else {
                        "Config-title candidates did not contain a valid config envelope"
                    },
                    markerPresent = valid.isNotEmpty(),
                )
            } else {
                AppSyncCloudResult.NotFound
            }
        }

        val preferred = preferredBlogIds
            .firstNotNullOfOrNull { preferredId -> eligible.firstOrNull { it.config.blogId == preferredId } }
        val selected = preferred ?: eligible.maxWithOrNull(
            compareBy(
                { it.envelope.updatedAtEpochMillis },
                { it.listUpdatedAtEpochSeconds },
                { it.config.blogId.value },
            ),
        ) ?: return AppSyncCloudResult.NotFound

        if (valid.size > 1) {
            val notice = AppSyncCloudNotice.DuplicateValidBlogs(
                selectedBlogId = selected.config.blogId,
                candidateCount = valid.size,
            )
            notices += notice
            emitNotice(notice)
        }
        return if (persistResult) {
            val persisted = persistVerified(selected)
            AppSyncCloudResult.VerifiedSuccess(persisted.value, notices)
        } else {
            AppSyncCloudResult.VerifiedSuccess(selected.config, notices)
        }
    }

    private suspend fun fetchAllBlogPages(
        blogClassId: BlogClassId?,
        firstPage: UserSpaceBlogPage? = null,
    ): AppSyncCloudResult<List<UserSpaceBlogPage>> {
        val pages = mutableListOf<UserSpaceBlogPage>()
        val visited = mutableSetOf<Int>()
        var pageIndex = 1
        var current = firstPage
        while (pageIndex <= MAX_DISCOVERY_PAGES && visited.add(pageIndex)) {
            val page = current ?: when (
                val result = provider.fetchMyBlogs(blogClassId = blogClassId, page = pageIndex)
            ) {
                is AppSyncCloudResult.VerifiedSuccess -> result.value
                else -> return result.propagateFailure()
            }
            pages += page
            val nav = page.pageNav
            val nextPage = nav?.nextPageIndex
                ?: nav?.totalPages?.takeIf { pageIndex < it }?.let { pageIndex + 1 }
                ?: break
            if (nextPage <= pageIndex) break
            pageIndex = nextPage
            current = null
        }
        if (pageIndex > MAX_DISCOVERY_PAGES) {
            return AppSyncCloudResult.ParseFailed(
                reason = "Config blog discovery exceeded $MAX_DISCOVERY_PAGES pages",
            )
        }
        return AppSyncCloudResult.VerifiedSuccess(pages)
    }

    private fun collectCandidates(
        page: UserSpaceBlogPage,
        classId: BlogClassId?,
        classNames: Map<String, BlogClassId>,
        destination: MutableMap<BlogId, Candidate>,
    ) {
        page.blogs.forEach { summary ->
            val matchedClass = classId ?: classNames.entries
                .firstOrNull { (name, _) ->
                    summary.title.startsWith("[$name] ")
                }
                ?.value
            val className = classNames.entries
                .firstOrNull { it.value == matchedClass }
                ?.key
            if (normalizeListTitle(summary.title, className) == AppSyncCloudConfigDefaults.BLOG_NAME) {
                if (summary.bId !in destination) {
                    destination[summary.bId] = Candidate(
                        blogId = summary.bId,
                        classId = matchedClass,
                        listUpdatedAtEpochSeconds = summary.timeInfo.epoch,
                    )
                }
            }
        }
    }

    private suspend fun verifyCandidate(
        candidate: Candidate,
    ): AppSyncCloudResult<VerifiedCandidate> {
        val page = when (val result = provider.fetchBlog(candidate.blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.propagateFailure()
        }
        if (page.blogInfo.blogId != candidate.blogId) {
            return AppSyncCloudResult.ParseFailed(
                reason = "Reader page blog id did not match the requested blog id",
            )
        }
        if (page.blogInfo.title != AppSyncCloudConfigDefaults.BLOG_NAME) {
            return AppSyncCloudResult.ValidationFailed(
                reason = "Reader page title did not match the fixed config title",
                markerPresent = false,
            )
        }
        return when (val validation = envelopeCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
            is AppSyncCloudEnvelopeValidation.Valid -> {
                AppSyncCloudResult.VerifiedSuccess(
                    VerifiedCandidate(
                        config = AppSyncBlogConfig(
                            blogId = candidate.blogId,
                            classId = candidate.classId,
                            cloudContentUpdatedAtEpochMillis = validation.envelope.updatedAtEpochMillis,
                            validatedAtEpochMillis = nowMillis(),
                            schemaVersion = validation.envelope.schemaVersion,
                            fingerprint = validation.envelope.fingerprint,
                        ),
                        envelope = validation.envelope,
                        listUpdatedAtEpochSeconds = candidate.listUpdatedAtEpochSeconds,
                    ),
                )
            }
            is AppSyncCloudEnvelopeValidation.Invalid -> {
                AppSyncCloudResult.ValidationFailed(
                    reason = validation.reason,
                    markerPresent = validation.markerPresent,
                )
            }
        }
    }

    private suspend fun deleteBlogConfigInternal(
        blogId: BlogId,
        formHash: FormHash,
        requireDamaged: Boolean,
        mutateStore: Boolean,
    ): AppSyncCloudResult<Unit> {
        val page = when (val result = provider.fetchBlog(blogId)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.propagateFailure()
        }
        val deletionValidation = validateDeletionTarget(page, blogId, requireDamaged)
        if (deletionValidation != null) return deletionValidation

        return when (val result = provider.deleteBlog(AppSyncBlogDeleteRequest(blogId, formHash))) {
            is AppSyncCloudResult.VerifiedSuccess,
            AppSyncCloudResult.NotFound,
            -> completeVerifiedDeletion(blogId, mutateStore)
            else -> result.propagateFailure()
        }
    }

    private suspend fun resolveClassId(
        blogId: BlogId,
    ): AppSyncCloudResult<BlogClassId> {
        val initial = when (val result = provider.fetchMyBlogs()) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.propagateFailure()
        }
        val configClass = initial.blogClasses
            .firstOrNull { it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME }
            ?: return AppSyncCloudResult.ValidationFailed(
                reason = "Fixed config blog class was not found",
                markerPresent = true,
            )
        val pages = when (val result = fetchAllBlogPages(configClass.id)) {
            is AppSyncCloudResult.VerifiedSuccess -> result.value
            else -> return result.propagateFailure()
        }
        return if (pages.any { page -> page.blogs.any { it.bId == blogId } }) {
            AppSyncCloudResult.VerifiedSuccess(configClass.id)
        } else {
            AppSyncCloudResult.NotFound
        }
    }

    private suspend fun completeClassId(
        verified: VerifiedCandidate,
    ): AppSyncCloudResult<VerifiedCandidate> {
        if (verified.config.classId != null) {
            return AppSyncCloudResult.VerifiedSuccess(verified)
        }
        return when (val resolved = resolveClassId(verified.config.blogId)) {
            is AppSyncCloudResult.VerifiedSuccess ->
                AppSyncCloudResult.VerifiedSuccess(
                    verified.copy(
                        config = verified.config.copy(classId = resolved.value),
                    ),
                )
            else -> resolved.propagateFailure()
        }
    }

    private fun completeVerifiedDeletion(
        blogId: BlogId,
        mutateStore: Boolean,
    ): AppSyncCloudResult<Unit> {
        if (!mutateStore || store.load()?.blogId != blogId) {
            return AppSyncCloudResult.VerifiedSuccess(Unit)
        }
        return when (val cleared = clearStoredConfig()) {
            is AppSyncCloudResult.VerifiedSuccess -> AppSyncCloudResult.VerifiedSuccess(Unit)
            else -> cleared.propagateFailure()
        }
    }

    private fun validateDeletionTarget(
        page: BlogPage,
        blogId: BlogId,
        requireDamaged: Boolean,
    ): AppSyncCloudResult<Unit>? {
        if (page.blogInfo.blogId != blogId) {
            return AppSyncCloudResult.ParseFailed(
                reason = "Delete verification loaded a different blog id",
            )
        }
        if (page.blogInfo.title != AppSyncCloudConfigDefaults.BLOG_NAME) {
            return AppSyncCloudResult.ValidationFailed(
                reason = "Delete target does not have the fixed config title",
                markerPresent = false,
            )
        }
        return when (val validation = envelopeCodec.validateReaderHtml(page.rootBlog.contentHtml)) {
            is AppSyncCloudEnvelopeValidation.Valid -> {
                if (requireDamaged) {
                    AppSyncCloudResult.Conflict(
                        reason = "Damaged config candidate became valid before deletion",
                        candidateBlogIds = listOf(blogId),
                    )
                } else {
                    null
                }
            }
            is AppSyncCloudEnvelopeValidation.Invalid -> {
                when {
                    !validation.markerPresent -> AppSyncCloudResult.ValidationFailed(
                        reason = "Delete target does not contain the config marker",
                        markerPresent = false,
                    )
                    requireDamaged -> null
                    else -> null
                }
            }
        }
    }

    private fun persistVerified(
        verified: VerifiedCandidate,
    ): AppSyncCloudResult.VerifiedSuccess<AppSyncBlogConfig> = try {
        store.save(verified.config.toStored())
        AppSyncCloudResult.VerifiedSuccess(verified.config)
    } catch (error: Throwable) {
        throw StoreMutationException(
            "Unable to persist verified config blog metadata: ${error.message ?: error::class.simpleName}",
            error,
        )
    }

    private fun clearStoredConfig(): AppSyncCloudResult<Unit> = try {
        store.clear()
        AppSyncCloudResult.VerifiedSuccess(Unit)
    } catch (error: Throwable) {
        AppSyncCloudResult.UnknownFailed(
            "Unable to clear verified stale config metadata: ${error.message ?: error::class.simpleName}",
        )
    }

    private fun AppSyncBlogConfig.toStored(): StoredAppSyncBlogConfig =
        StoredAppSyncBlogConfig(
            blogName = blogName,
            blogId = blogId,
            classId = classId,
            cloudContentUpdatedAtEpochMillis = cloudContentUpdatedAtEpochMillis,
            validatedAtEpochMillis = validatedAtEpochMillis,
            schemaVersion = schemaVersion,
            fingerprint = fingerprint,
        )

    private fun normalizeListTitle(title: String, className: String?): String {
        val prefix = className?.let { "[$it] " } ?: return title
        return title.removePrefix(prefix)
    }

    private fun emitNotice(notice: AppSyncCloudNotice) {
        try {
            noticeSink.emit(notice)
        } catch (_: Throwable) {
            // Notification delivery is intentionally non-authoritative.
        }
    }

    private suspend fun <T> guarded(
        operation: suspend () -> AppSyncCloudResult<T>,
    ): AppSyncCloudResult<T> = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: StoreMutationException) {
        AppSyncCloudResult.UnknownFailed(error.message.orEmpty())
    } catch (error: Throwable) {
        AppSyncCloudResult.UnknownFailed(error.message ?: error::class.simpleName.orEmpty())
    }

    private fun AppSyncCloudResult<*>.describe(): String = when (this) {
        is AppSyncCloudResult.VerifiedSuccess -> "verified"
        is AppSyncCloudResult.AcknowledgedButUnverified -> reason
        is AppSyncCloudResult.NotFound -> "not found"
        is AppSyncCloudResult.NotLoggedIn -> "not logged in"
        is AppSyncCloudResult.NoPermission -> reason
        is AppSyncCloudResult.Maintenance -> "maintenance"
        is AppSyncCloudResult.FormExpired -> messageText ?: "form expired"
        is AppSyncCloudResult.ValidationFailed -> reason
        is AppSyncCloudResult.Conflict -> reason
        is AppSyncCloudResult.HttpFailed -> messageText ?: "HTTP $statusCode"
        is AppSyncCloudResult.NetworkFailed -> reason
        is AppSyncCloudResult.Timeout -> reason
        is AppSyncCloudResult.ParseFailed -> reason
        is AppSyncCloudResult.UnknownFailed -> reason
    }

    private fun <T> AppSyncCloudResult<*>.propagateFailure(): AppSyncCloudResult<T> =
        when (this) {
            is AppSyncCloudResult.VerifiedSuccess ->
                error("Verified success cannot be propagated as a failure")
            is AppSyncCloudResult.AcknowledgedButUnverified -> this
            is AppSyncCloudResult.NotFound -> this
            is AppSyncCloudResult.NotLoggedIn -> this
            is AppSyncCloudResult.NoPermission -> this
            is AppSyncCloudResult.Maintenance -> this
            is AppSyncCloudResult.FormExpired -> this
            is AppSyncCloudResult.ValidationFailed -> this
            is AppSyncCloudResult.Conflict -> this
            is AppSyncCloudResult.HttpFailed -> this
            is AppSyncCloudResult.NetworkFailed -> this
            is AppSyncCloudResult.Timeout -> this
            is AppSyncCloudResult.ParseFailed -> this
            is AppSyncCloudResult.UnknownFailed -> this
        }

    private data class Candidate(
        val blogId: BlogId,
        val classId: BlogClassId?,
        val listUpdatedAtEpochSeconds: Long,
    )

    private data class VerifiedCandidate(
        val config: AppSyncBlogConfig,
        val envelope: ParsedAppSyncCloudEnvelope,
        val listUpdatedAtEpochSeconds: Long,
    ) {
        companion object {
            fun damaged(candidate: Candidate, reason: String): VerifiedCandidate =
                VerifiedCandidate(
                    config = AppSyncBlogConfig(
                        blogId = candidate.blogId,
                        classId = candidate.classId,
                    ),
                    envelope = ParsedAppSyncCloudEnvelope(
                        encodedSnapshot = "",
                        updatedAtEpochMillis = Long.MIN_VALUE,
                        schemaVersion = 0,
                        fingerprint = "damaged:$reason",
                    ),
                    listUpdatedAtEpochSeconds = candidate.listUpdatedAtEpochSeconds,
                )
        }
    }

    private class StoreMutationException(
        message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)

    companion object {
        private const val MAX_DISCOVERY_PAGES = 100
    }
}
