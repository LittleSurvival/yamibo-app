package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

internal enum class ManualSyncOverrideDirection {
    ForcePush,
    ForcePull,
}

internal data class ManualSyncDifference(
    val domainId: String,
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val enabled: Int = 0,
    val disabled: Int = 0,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

internal data class ManualSyncOverridePreview(
    val direction: ManualSyncOverrideDirection,
    val token: String,
    val differences: List<ManualSyncDifference>,
)

internal sealed interface ManualSyncPreviewResult {
    data class Ready(val preview: ManualSyncOverridePreview) : ManualSyncPreviewResult
    data class Failed(val reason: String) : ManualSyncPreviewResult
}

internal sealed interface ManualSyncApplyResult {
    data class Applied(val operationCount: Int) : ManualSyncApplyResult
    data object StalePreview : ManualSyncApplyResult
    data class Failed(val reason: String) : ManualSyncApplyResult
}

internal class ManualSyncOverrideCoordinator(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val domainState: SyncDomainStateAdapter,
    private val captureAuthoritativeLocalDrafts: (() -> List<LocalSyncOperationDraft>)? = null,
    private val reducer: OperationReducer = OperationReducer(),
    private val nowMillis: () -> Long,
) {
    suspend fun preview(
        accountBinding: SyncAccountBinding,
        direction: ManualSyncOverrideDirection,
    ): ManualSyncPreviewResult {
        val loaded = when (val result = loadCloud(accountBinding)) {
            is CloudLoad.Failed -> return ManualSyncPreviewResult.Failed(result.reason)
            is CloudLoad.Ready -> result
        }
        val local = try {
            authoritativeLocalState(accountBinding)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ManualSyncPreviewResult.Failed(
                "Authoritative local snapshot failed (${error::class.simpleName ?: "unknown"})",
            )
        }
        return ManualSyncPreviewResult.Ready(
            ManualSyncOverridePreview(
                direction = direction,
                token = previewToken(direction, local, loaded),
                differences = differences(
                    source = if (direction == ManualSyncOverrideDirection.ForcePush) {
                        loaded.state
                    } else {
                        local
                    },
                    target = if (direction == ManualSyncOverrideDirection.ForcePush) {
                        local
                    } else {
                        loaded.state
                    },
                ),
            ),
        )
    }

    suspend fun apply(
        accountBinding: SyncAccountBinding,
        preview: ManualSyncOverridePreview,
    ): ManualSyncApplyResult {
        val leaseOwner = "manual-override-${SyncIdentityGenerator.writerNonce().value}"
        if (!store.acquireLease(leaseOwner, nowMillis(), LEASE_DURATION_MILLIS)) {
            return ManualSyncApplyResult.Failed("Another synchronization is already running")
        }
        return try {
            applyWithLease(accountBinding, preview)
        } finally {
            store.releaseLease(leaseOwner)
        }
    }

    private suspend fun applyWithLease(
        accountBinding: SyncAccountBinding,
        preview: ManualSyncOverridePreview,
    ): ManualSyncApplyResult {
        val loaded = when (val result = loadCloud(accountBinding)) {
            is CloudLoad.Failed -> return ManualSyncApplyResult.Failed(result.reason)
            is CloudLoad.Ready -> result
        }
        val local = try {
            authoritativeLocalState(accountBinding)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ManualSyncApplyResult.Failed(
                "Authoritative local snapshot failed (${error::class.simpleName ?: "unknown"})",
            )
        }
        if (preview.token != previewToken(preview.direction, local, loaded)) {
            return ManualSyncApplyResult.StalePreview
        }
        return try {
            when (preview.direction) {
                ManualSyncOverrideDirection.ForcePull -> {
                    store.replaceWithVerifiedCloudState(
                        result = loaded.reduction,
                        coverage = loaded.coverage,
                        cloudOperationIds = loaded.operationIds,
                        appliedAtEpochMillis = nowMillis(),
                        domainMutation = {
                            domainState.adoptCheckpointWithinTransaction(it.entities.values)
                        },
                    )
                    domainState.reconcileProjections()
                    ManualSyncApplyResult.Applied(preview.differences.sumOf { it.total() })
                }
                ManualSyncOverrideDirection.ForcePush -> {
                    val drafts = forcePushDrafts(local, loaded.state)
                    val authorized = authorizeDeletes(drafts)
                    if (authorized.isNotEmpty()) {
                        store.appendLocalOperations(
                            accountBinding = accountBinding,
                            drafts = authorized,
                            causalContext = store.causalContext().merge(loaded.coverage),
                            createdAtEpochMillis = nowMillis(),
                            origin = SyncOperationOrigin.UserAction,
                        ) { operations ->
                            val reduction = reducer.reduce(domainState.currentState(), operations)
                            check(reduction.quarantined.isEmpty()) {
                                "Force push generated invalid operations"
                            }
                            domainState.applyWithinTransaction(reduction)
                            store.bindAccount(accountBinding, me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState.Active)
                        }
                        domainState.reconcileProjections()
                    } else {
                        store.bindAccount(accountBinding, me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState.Active)
                    }
                    ManualSyncApplyResult.Applied(authorized.size)
                }
            }
        } catch (error: Throwable) {
            ManualSyncApplyResult.Failed(
                "Manual override failed (${error::class.simpleName ?: "unknown"})",
            )
        }
    }

    private suspend fun loadCloud(accountBinding: SyncAccountBinding): CloudLoad {
        val cloud = when (val result = remote.loadJournals(accountBinding, forceDiscovery = true)) {
            is AppSyncJournalLoadResult.Success -> result
            AppSyncJournalLoadResult.NotLoggedIn ->
                return CloudLoad.Failed("Yamibo login is unavailable")
            is AppSyncJournalLoadResult.RetryableFailure -> return CloudLoad.Failed(result.reason)
            is AppSyncJournalLoadResult.TerminalFailure -> return CloudLoad.Failed(result.reason)
        }
        val checkpoint = cloud.checkpoints.maxWithOrNull(
            compareBy(
                { it.envelope.payload.coverage.asStableMap().values.sum() },
                { it.envelope.payload.createdAtEpochMillis },
                { it.envelope.payload.checkpointId },
            ),
        )
        val checkpointCoverage = checkpoint?.envelope?.payload?.coverage ?: SyncCausalContext()
        val initial = checkpoint?.envelope?.payload?.resolvedEntities
            ?.associateBy { it.key }
            .orEmpty()
        val operations = cloud.journals
            .flatMap { it.payload.operations }
            .distinctBy { it.operationId }
        val later = operations.filterNot(checkpointCoverage::includes)
        val reduction = reducer.reduce(initial, later)
        if (reduction.quarantined.isNotEmpty()) {
            return CloudLoad.Failed(
                "Cloud contains ${reduction.quarantined.size} quarantined operation(s)",
            )
        }
        val coverage = operations.fold(checkpointCoverage) { context, operation ->
            context.advance(operation.replicaKey, operation.sequence)
        }
        return CloudLoad.Ready(
            state = reduction.entities,
            reduction = reduction,
            coverage = coverage,
            operationIds = operations.mapTo(linkedSetOf()) { it.operationId },
            remoteFingerprint = buildString {
                checkpoint?.let { append("c:${it.remoteId}:${it.envelope.fingerprint};") }
                cloud.journals.sortedBy { it.remoteId }.forEach {
                    append("j:${it.remoteId}:${it.fingerprint};")
                }
            },
        )
    }

    private fun previewToken(
        direction: ManualSyncOverrideDirection,
        local: Map<SyncEntityKey, ResolvedSyncEntity>,
        cloud: CloudLoad.Ready,
    ): String = stableAppSyncFingerprint(
        "${direction.name}|${semanticFingerprint(local)}|" +
            "${semanticFingerprint(cloud.state)}|${cloud.remoteFingerprint}",
    )

    private fun forcePushDrafts(
        local: Map<SyncEntityKey, ResolvedSyncEntity>,
        cloud: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): List<LocalSyncOperationDraft> {
        val localByIdentity = latestByIdentity(local)
        val cloudByIdentity = latestByIdentity(cloud)
        return (localByIdentity.keys + cloudByIdentity.keys)
            .distinct()
            .sortedWith(compareBy({ it.first.value }, { it.second.value }))
            .mapNotNull { identity ->
                val target = localByIdentity[identity]
                val current = cloudByIdentity[identity]
                if (semantic(target) == semantic(current)) return@mapNotNull null
                draftFor(target, current)
            }
    }

    /**
     * Force operations use the live application database as their authoritative local side.
     * The operation projection is intentionally not audited or repaired here: a force push
     * must treat rows missing from the live database as deletions instead of resurrecting
     * stale projected entities.
     */
    private fun authoritativeLocalState(
        accountBinding: SyncAccountBinding,
    ): Map<SyncEntityKey, ResolvedSyncEntity> {
        val drafts = captureAuthoritativeLocalDrafts?.invoke() ?: return domainState.currentState()
        if (drafts.isEmpty()) return emptyMap()
        val deviceId = SyncDeviceId("manual-force-source")
        val deviceEpoch = SyncDeviceEpoch("authoritative-snapshot")
        val operations = drafts.mapIndexed { index, draft ->
            val sequence = SyncSequence(index + 1L)
            SyncOperation(
                operationId = SyncOperation.idFor(deviceId, deviceEpoch, sequence),
                deviceId = deviceId,
                deviceEpoch = deviceEpoch,
                sequence = sequence,
                accountBinding = accountBinding,
                domainId = draft.domainId,
                entityId = draft.entityId,
                entityGeneration = draft.entityGeneration,
                kind = draft.kind,
                fields = draft.fields,
                createdAtEpochMillis = 0L,
                origin = SyncOperationOrigin.Migration,
            )
        }
        return reducer.reduce(operations = operations).also { reduction ->
            check(reduction.quarantined.isEmpty()) {
                "Authoritative local snapshot produced invalid operations"
            }
        }.entities
    }

    private fun draftFor(
        target: ResolvedSyncEntity?,
        current: ResolvedSyncEntity?,
    ): LocalSyncOperationDraft {
        val key = target?.key ?: requireNotNull(current).key
        val targetGeneration = target?.key?.generation ?: key.generation
        val relation = target?.relationPresent != null || current?.relationPresent != null
        val targetLive = target != null && target.tombstone == null &&
            (!relation || target.relationPresent == true)
        val fields = if (targetLive) {
            val targetValues = target.fields.mapValues { it.value.value }
            val removedFields = current?.fields.orEmpty().keys
                .minus(targetValues.keys)
                .associateWith<String, String?> { null }
            targetValues + removedFields
        } else {
            (target ?: current)?.fields.orEmpty().mapValues { it.value.value }
        }
        val kind = when {
            relation && targetLive -> SyncOperationKind.RelationAdd
            relation -> SyncOperationKind.RelationRemove
            !targetLive -> SyncOperationKind.Delete
            current == null ||
                current.tombstone != null ||
                current.key.generation > targetGeneration -> SyncOperationKind.Put
            else -> SyncOperationKind.Patch
        }
        val generation = if (
            kind == SyncOperationKind.Put &&
            current != null &&
            (current.tombstone != null || current.key.generation >= targetGeneration)
        ) {
            maxOf(targetGeneration, current.key.generation) + 1L
        } else {
            key.generation
        }
        return LocalSyncOperationDraft(
            domainId = key.domainId,
            entityId = key.entityId,
            entityGeneration = generation,
            kind = kind,
            fields = fields,
        )
    }

    private fun authorizeDeletes(
        drafts: List<LocalSyncOperationDraft>,
    ): List<LocalSyncOperationDraft> {
        val createdAt = nowMillis()
        val deleteGroups = drafts.filter { it.kind == SyncOperationKind.Delete }.groupBy { it.domainId }
        val authorizedByKey = deleteGroups.flatMap { (domain, deletes) ->
            val authorizationId = SyncIdentityGenerator.writerNonce().value
            val expiresAt = createdAt + AUTHORIZATION_WINDOW_MILLIS
            store.saveBulkDeleteAuthorization(
                AppSyncBulkDeleteAuthorization(
                    authorizationId = authorizationId,
                    domainId = domain.value,
                    scopeKey = FORCE_PUSH_SCOPE,
                    operationCount = deletes.size.toLong(),
                    expiresAtEpochMillis = expiresAt,
                    consumedAtEpochMillis = null,
                ),
            )
            deletes.map { draft ->
                Triple(draft.domainId, draft.entityId, draft.entityGeneration) to draft.copy(
                    fields = draft.fields + mapOf(
                        AppSyncBulkDeleteProofFields.SCOPE to FORCE_PUSH_SCOPE,
                        AppSyncBulkDeleteProofFields.COUNT to deletes.size.toString(),
                        AppSyncBulkDeleteProofFields.EXPIRES_AT to expiresAt.toString(),
                    ),
                    bulkDeleteAuthorizationId = authorizationId,
                )
            }
        }.toMap()
        return drafts.map {
            authorizedByKey[Triple(it.domainId, it.entityId, it.entityGeneration)] ?: it
        }
    }

    private fun differences(
        source: Map<SyncEntityKey, ResolvedSyncEntity>,
        target: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): List<ManualSyncDifference> {
        data class Counts(
            var added: Int = 0,
            var updated: Int = 0,
            var deleted: Int = 0,
            var enabled: Int = 0,
            var disabled: Int = 0,
            val details: MutableList<String> = mutableListOf(),
        )
        val counts = linkedMapOf<String, Counts>()
        val sourceByIdentity = latestByIdentity(source)
        val targetByIdentity = latestByIdentity(target)
        (sourceByIdentity.keys + targetByIdentity.keys).distinct().forEach { identity ->
            val before = sourceByIdentity[identity]
            val after = targetByIdentity[identity]
            if (semantic(before) == semantic(after)) return@forEach
            val domainId = identity.first.value
            val bucket = counts.getOrPut(domainId, ::Counts)
            val beforeLive = isLive(before)
            val afterLive = isLive(after)
            when {
                !beforeLive && afterLive -> bucket.added++
                beforeLive && !afterLive -> bucket.deleted++
                domainId == "settings" &&
                    after?.fields?.get("type")?.value == "bool" -> {
                    if (after.fields["value"]?.value == "true") bucket.enabled++ else bucket.disabled++
                }
                domainId in TOGGLE_DOMAINS -> {
                    if (after?.fields?.get("enabled")?.value == "true") {
                        bucket.enabled++
                    } else {
                        bucket.disabled++
                    }
                }
                else -> bucket.updated++
            }
            val detail = (after ?: before)?.safeDisplayDetail(domainId)
            if (detail != null && detail !in bucket.details && bucket.details.size < MAX_DETAILS) {
                bucket.details += detail
            }
        }
        return counts.map { (domain, value) ->
            val total = value.added + value.updated + value.deleted + value.enabled + value.disabled
            ManualSyncDifference(
                domainId = domain,
                added = value.added,
                updated = value.updated,
                deleted = value.deleted,
                enabled = value.enabled,
                disabled = value.disabled,
                details = value.details,
                remainingDetailCount = if (value.details.isEmpty()) {
                    0
                } else {
                    (total - value.details.size).coerceAtLeast(0)
                },
            )
        }
    }

    private fun ResolvedSyncEntity.safeDisplayDetail(domainId: String): String? {
        fun field(name: String): String? = fields[name]?.value?.takeIf { it.isNotBlank() }
        return when (domainId) {
            "favorite.update-event" ->
                field("title") ?: field("latestPostTitle") ?: field("forumName") ?: "未命名更新項目"
            "favorite.update-fid-filter" ->
                field("forumName") ?: field("fid")?.let { "FID $it" } ?: "未命名版塊篩選"
            "favorite.update-category-filter" -> "分類更新篩選"
            "rss.search-subscription" -> field("title") ?: field("query")
            "reading.tag-manga",
            "reading.tag-catalog",
            -> field("threadTitle") ?: field("tagName")
            "reading.rss-search",
            "reading.rss-catalog",
            -> field("threadTitle") ?: field("postTitle") ?: field("subscriptionTitle")
            "favorite.item",
            "favorite.category",
            "favorite.collection",
            "reading.thread",
            -> field("title") ?: field("name") ?: field("threadName") ?: "未命名項目"
            else -> field("title") ?: field("name")
        }
    }

    private fun semanticFingerprint(
        state: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): String = stableAppSyncFingerprint(
        state.entries
            .sortedWith(compareBy({ it.key.domainId.value }, { it.key.entityId.value }, { it.key.generation }))
            .joinToString(";") { (key, entity) ->
                "${key.domainId.value}|${key.entityId.value}|${key.generation}|${semantic(entity)}"
            },
    )

    private fun semantic(entity: ResolvedSyncEntity?): String = when {
        entity == null -> "absent"
        entity.tombstone != null || entity.relationPresent == false -> "absent"
        entity.relationPresent != null -> buildString {
            append("relation:")
            append(entity.relationPresent)
            append(':')
            append(entity.fields.entries.sortedBy { it.key }.joinToString(",") {
                "${it.key}=${it.value.value}"
            })
        }
        else -> entity.fields.entries.sortedBy { it.key }.joinToString(",") {
            "${it.key}=${it.value.value}"
        }
    }

    private fun isLive(entity: ResolvedSyncEntity?): Boolean =
        entity != null && entity.tombstone == null && entity.relationPresent != false

    private fun latestByIdentity(
        state: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): Map<Pair<SyncDomainId, SyncEntityId>, ResolvedSyncEntity> =
        state.values
            .groupBy { it.key.domainId to it.key.entityId }
            .mapValues { (_, entities) -> entities.maxBy { it.key.generation } }

    private sealed interface CloudLoad {
        data class Ready(
            val state: Map<SyncEntityKey, ResolvedSyncEntity>,
            val reduction: OperationReductionResult,
            val coverage: SyncCausalContext,
            val operationIds: Set<SyncOperationId>,
            val remoteFingerprint: String,
        ) : CloudLoad

        data class Failed(val reason: String) : CloudLoad
    }

    private fun ManualSyncDifference.total(): Int =
        added + updated + deleted + enabled + disabled

    private companion object {
        val TOGGLE_DOMAINS = setOf(
            "favorite.update-fid-filter",
            "favorite.update-category-filter",
            "rss.search-subscription",
        )
        const val FORCE_PUSH_SCOPE = "manual-force-push"
        const val AUTHORIZATION_WINDOW_MILLIS = 5 * 60 * 1_000L
        const val LEASE_DURATION_MILLIS = 15 * 60 * 1_000L
        const val MAX_DETAILS = 5
    }
}
