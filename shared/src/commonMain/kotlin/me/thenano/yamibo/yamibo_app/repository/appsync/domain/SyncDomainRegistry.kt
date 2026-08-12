package me.thenano.yamibo.yamibo_app.repository.appsync.domain

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.repository.rss.normalizeRssSearchKeyword
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId

internal enum class SyncConflictPolicy {
    FieldRegister,
    MonotonicProgress,
    RemoveWinsRelation,
    RemoveWinsEntity,
}

internal data class SyncDomainContract(
    val id: SyncDomainId,
    val policyVersion: Int = 1,
    val conflictPolicy: SyncConflictPolicy,
    val allowedKinds: Set<SyncOperationKind>,
    val requiredFieldsByKind: Map<SyncOperationKind, Set<String>> = emptyMap(),
    val monotonicNumericFields: Set<String> = emptySet(),
    val allowedFieldsByKind: Map<SyncOperationKind, Set<String>> = emptyMap(),
    val semanticValidator: ((SyncOperation) -> String?)? = null,
) {
    init {
        require(policyVersion > 0) { "Policy version must be positive" }
        require(allowedKinds.isNotEmpty()) { "A sync domain must allow at least one operation kind" }
    }

    fun validate(operation: SyncOperation): String? {
        if (operation.domainId != id) return "Operation domain does not match contract"
        if (operation.kind !in allowedKinds) return "Operation kind is not allowed by ${id.value}"
        val missing = requiredFieldsByKind[operation.kind].orEmpty() - operation.fields.keys
        if (missing.isNotEmpty()) return "Missing required fields: ${missing.sorted().joinToString()}"
        allowedFieldsByKind[operation.kind]?.let { allowed ->
            val unexpected = operation.fields.keys - allowed
            if (unexpected.isNotEmpty()) {
                return "Unexpected fields: ${unexpected.sorted().joinToString()}"
            }
        }
        return semanticValidator?.invoke(operation)
    }
}

internal class SyncDomainRegistry(
    contracts: List<SyncDomainContract>,
) {
    private val contractsById = contracts.associateBy { it.id }

    init {
        require(contractsById.size == contracts.size) { "Duplicate sync domain contract" }
    }

    fun contractFor(id: SyncDomainId): SyncDomainContract? = contractsById[id]

    fun validationFailure(operation: SyncOperation): String? {
        val contract = contractFor(operation.domainId)
            ?: return "Unknown sync domain: ${operation.domainId.value}"
        return contract.validate(operation)
    }

    val domainIds: Set<SyncDomainId>
        get() = contractsById.keys

    fun requireExactCoverage(expected: Set<SyncDomainId>) {
        val missing = expected - domainIds
        val unexpected = domainIds - expected
        require(missing.isEmpty() && unexpected.isEmpty()) {
            "Sync domain registry mismatch; missing=${missing.map { it.value }.sorted()}, " +
                "unexpected=${unexpected.map { it.value }.sorted()}"
        }
    }

    companion object {
        val REQUIRED_DOMAIN_IDS = setOf(
            "settings",
            "favorite.item",
            "rss.search-subscription",
            "favorite.category",
            "favorite.collection",
            "detail-note",
            "bookmark",
            "reading.thread",
            "reading.image",
            "reading.tag-manga",
            "reading.tag-catalog",
            "reading.rss-search",
            "reading.rss-catalog",
            "reading.time",
            "favorite.item-category",
            "favorite.item-collection",
            "favorite.update-event",
            "favorite.update-fid-filter",
            "favorite.update-category-filter",
        ).mapTo(linkedSetOf(), ::SyncDomainId)

        val Default = SyncDomainRegistry(
            listOf(
                fieldDomain("settings", setOf("type", "value")),
                fieldDomain(
                    "favorite.item",
                    setOf(
                        "targetType", "targetId", "authorId", "title", "createdAt",
                        "lastFavoriteStatusUpdateAt",
                    ),
                ),
                rssSearchSubscriptionDomain(),
                fieldDomain(
                    "favorite.category",
                    setOf("name", "sortOrder", "createdAt", "updatedAt"),
                ),
                fieldDomain(
                    "favorite.collection",
                    setOf(
                        "categorySyncId", "name", "colorKey", "sortOrder", "createdAt", "updatedAt",
                    ),
                ),
                fieldDomain(
                    "detail-note",
                    setOf("targetType", "targetId", "authorId", "content", "createdAt", "updatedAt"),
                ),
                fieldDomain(
                    "bookmark",
                    setOf(
                        "targetType", "parentId", "targetId", "title", "bookmarked", "read",
                        "createdAt", "updatedAt",
                    ),
                ),
                progressDomain("reading.thread"),
                progressDomain("reading.image"),
                progressDomain("reading.tag-manga"),
                historyDomain(
                    "reading.tag-catalog",
                    "tagId",
                    setOf(
                        "tagId", "tagName", "tagPage", "threadId", "threadTitle", "threadPage",
                        "postId", "postTitle", "authorId", "anchorPostId", "anchorPostRatio",
                        "anchorBlockId", "anchorBlockType", "anchorBlockRatio", "viewportHeight",
                        "firstVisibleItemIndex", "firstVisibleItemOffset", "lastVisitTime", "coverUrl",
                    ),
                ),
                historyDomain(
                    "reading.rss-search",
                    "subscriptionSyncId",
                    setOf(
                        "subscriptionSyncId", "subscriptionTitle", "subscriptionQuery",
                        "subscriptionPage", "threadId", "threadTitle", "threadImagePageIndex",
                        "threadImageTotalPages", "firstVisibleItemIndex", "firstVisibleItemOffset",
                        "lastVisitTime", "coverUrl",
                    ),
                ),
                historyDomain(
                    "reading.rss-catalog",
                    "subscriptionSyncId",
                    setOf(
                        "subscriptionSyncId", "subscriptionTitle", "subscriptionQuery",
                        "subscriptionPage", "threadId", "threadTitle", "threadPage", "postId",
                        "postTitle", "authorId", "anchorPostId", "anchorPostRatio", "anchorBlockId",
                        "anchorBlockType", "anchorBlockRatio", "viewportHeight",
                        "firstVisibleItemIndex", "firstVisibleItemOffset", "lastVisitTime", "coverUrl",
                    ),
                ),
                progressDomain("reading.time", monotonicFields = setOf("durationMillis")),
                relationDomain(
                    "favorite.item-category",
                    setOf("targetType", "targetId", "authorId", "categorySyncId"),
                ),
                relationDomain(
                    "favorite.item-collection",
                    setOf("targetType", "targetId", "authorId", "collectionSyncId"),
                ),
                favoriteUpdateEventDomain(),
                favoriteUpdateFilterDomain("favorite.update-fid-filter", "fid"),
                favoriteUpdateFilterDomain("favorite.update-category-filter", "categorySyncId"),
            ),
        ).also { it.requireExactCoverage(REQUIRED_DOMAIN_IDS) }

        private fun fieldDomain(
            value: String,
            putFields: Set<String>,
        ) = SyncDomainContract(
            id = SyncDomainId(value),
            conflictPolicy = SyncConflictPolicy.FieldRegister,
            allowedKinds = setOf(
                SyncOperationKind.Put,
                SyncOperationKind.Patch,
                SyncOperationKind.Delete,
            ),
            requiredFieldsByKind = mapOf(SyncOperationKind.Put to putFields),
        )

        private fun progressDomain(
            value: String,
            monotonicFields: Set<String> = emptySet(),
        ) = SyncDomainContract(
            id = SyncDomainId(value),
            conflictPolicy = SyncConflictPolicy.MonotonicProgress,
            allowedKinds = setOf(
                SyncOperationKind.Put,
                SyncOperationKind.Patch,
                SyncOperationKind.Delete,
            ),
            monotonicNumericFields = monotonicFields,
        )

        private fun historyDomain(
            value: String,
            identityField: String,
            putFields: Set<String>,
        ) = SyncDomainContract(
            id = SyncDomainId(value),
            conflictPolicy = SyncConflictPolicy.MonotonicProgress,
            allowedKinds = setOf(
                SyncOperationKind.Put,
                SyncOperationKind.Patch,
                SyncOperationKind.Delete,
            ),
            requiredFieldsByKind = mapOf(SyncOperationKind.Put to putFields),
            semanticValidator = { operation ->
                runCatching {
                    if (operation.kind != SyncOperationKind.Delete) {
                        val identity = requireNotNull(operation.fields[identityField])
                        require(identity == operation.entityId.value)
                        require(requireNotNull(operation.fields["lastVisitTime"]).toLong() >= 0L)
                    }
                }.exceptionOrNull()?.let { "Invalid $value history: ${it.message}" }
            },
        )

        private fun rssSearchSubscriptionDomain(): SyncDomainContract {
            val putFields = setOf(
                "title", "query", "forumId", "forumName", "enabled", "createdAt", "updatedAt",
            )
            val patchFields = setOf("title", "enabled", "updatedAt")
            return SyncDomainContract(
                id = SyncDomainId("rss.search-subscription"),
                conflictPolicy = SyncConflictPolicy.RemoveWinsEntity,
                allowedKinds = setOf(
                    SyncOperationKind.Put,
                    SyncOperationKind.Patch,
                    SyncOperationKind.Delete,
                ),
                requiredFieldsByKind = mapOf(
                    SyncOperationKind.Put to putFields,
                    SyncOperationKind.Patch to setOf("updatedAt"),
                ),
                allowedFieldsByKind = mapOf(
                    SyncOperationKind.Put to putFields,
                    SyncOperationKind.Patch to patchFields,
                    SyncOperationKind.Delete to emptySet(),
                ),
                semanticValidator = { operation ->
                    runCatching {
                        when (operation.kind) {
                            SyncOperationKind.Put -> {
                                val query = requireNotNull(operation.fields["query"])
                                require(normalizeRssSearchKeyword(query).isNotBlank())
                                require(!operation.fields["title"].isNullOrBlank())
                                val forumId = operation.fields["forumId"]?.toLong()
                                require(
                                    operation.entityId.value ==
                                        rssSearchSubscriptionSyncId(query, forumId),
                                )
                                requireNotNull(operation.fields["enabled"]).toBooleanStrict()
                                require(requireNotNull(operation.fields["createdAt"]).toLong() >= 0)
                                require(requireNotNull(operation.fields["updatedAt"]).toLong() >= 0)
                            }
                            SyncOperationKind.Patch -> {
                                operation.fields["title"]?.let { require(it.isNotBlank()) }
                                operation.fields["enabled"]?.toBooleanStrict()
                                require(requireNotNull(operation.fields["updatedAt"]).toLong() >= 0)
                            }
                            SyncOperationKind.Delete -> Unit
                            else -> error("Unsupported RSS subscription operation")
                        }
                    }.exceptionOrNull()?.let { "Invalid RSS subscription: ${it.message}" }
                },
            )
        }

        private fun relationDomain(
            value: String,
            identityFields: Set<String>,
        ) = SyncDomainContract(
            id = SyncDomainId(value),
            conflictPolicy = SyncConflictPolicy.RemoveWinsRelation,
            allowedKinds = setOf(
                SyncOperationKind.RelationAdd,
                SyncOperationKind.RelationRemove,
            ),
            requiredFieldsByKind = mapOf(
                SyncOperationKind.RelationAdd to identityFields,
                SyncOperationKind.RelationRemove to identityFields,
            ),
        )

        private fun favoriteUpdateEventDomain(): SyncDomainContract {
            val putFields = setOf(
                "targetType", "targetId", "authorId", "fid", "forumName", "title",
                "latestPostTitle", "mode", "summary", "detailIds", "coverUrl", "detectedAt",
                "ambiguous", "sourceFingerprint", "sourceDiscriminator",
            )
            val lifecycleFields = setOf("readAt", "dismissedAt")
            return SyncDomainContract(
                id = SyncDomainId("favorite.update-event"),
                conflictPolicy = SyncConflictPolicy.RemoveWinsEntity,
                allowedKinds = setOf(
                    SyncOperationKind.Put,
                    SyncOperationKind.Patch,
                    SyncOperationKind.Delete,
                ),
                requiredFieldsByKind = mapOf(SyncOperationKind.Put to putFields),
                allowedFieldsByKind = mapOf(
                    SyncOperationKind.Put to putFields + lifecycleFields,
                    SyncOperationKind.Patch to lifecycleFields,
                ),
                semanticValidator = { operation ->
                    when (operation.kind) {
                        SyncOperationKind.Put -> validateFavoriteUpdateEvent(operation)
                        SyncOperationKind.Patch -> {
                            if (operation.fields.isEmpty()) {
                                "FavoriteUpdate lifecycle patch cannot be empty"
                            } else {
                                operation.fields.entries
                                    .firstOrNull { (_, value) -> value?.toLongOrNull() == null }
                                    ?.let { "FavoriteUpdate lifecycle markers must be non-null integers" }
                            }
                        }
                        SyncOperationKind.Delete -> null
                        else -> "Unsupported FavoriteUpdate event operation"
                    }
                },
            )
        }

        private fun validateFavoriteUpdateEvent(operation: SyncOperation): String? = runCatching {
            fun required(name: String) = requireNotNull(operation.fields[name])
            val detailIds = required("detailIds").split(",")
                .filter(String::isNotBlank)
                .map(String::toLong)
            val identity = favoriteUpdateEventIdentity(
                targetType = required("targetType"),
                targetId = required("targetId").toLong(),
                authorId = required("authorId").toLong().takeIf { it != 0L },
                mode = required("mode"),
                detailIds = detailIds,
                ambiguous = required("ambiguous").toBooleanStrict(),
                detectedAt = required("detectedAt").toLong(),
                summary = required("summary"),
                title = required("title"),
                sourceDiscriminator = required("sourceDiscriminator"),
            )
            require(operation.entityId.value == identity.syncId)
            require(required("sourceFingerprint") == identity.sourceFingerprint)
            listOf("readAt", "dismissedAt").forEach { field ->
                operation.fields[field]?.toLong()
            }
        }.exceptionOrNull()?.let { "Invalid FavoriteUpdate event: ${it.message}" }

        private fun favoriteUpdateFilterDomain(
            domain: String,
            identityField: String,
        ): SyncDomainContract {
            val fields = setOf(identityField, "enabled")
            return SyncDomainContract(
                id = SyncDomainId(domain),
                conflictPolicy = SyncConflictPolicy.FieldRegister,
                allowedKinds = setOf(SyncOperationKind.Put, SyncOperationKind.Patch),
                requiredFieldsByKind = mapOf(
                    SyncOperationKind.Put to fields,
                    SyncOperationKind.Patch to fields,
                ),
                allowedFieldsByKind = mapOf(
                    SyncOperationKind.Put to fields,
                    SyncOperationKind.Patch to fields,
                ),
                semanticValidator = { operation ->
                    val identity = operation.fields[identityField]
                    val enabled = operation.fields["enabled"]
                    when {
                        identity.isNullOrBlank() -> "FavoriteUpdate filter identity is required"
                        enabled !in setOf("true", "false") -> "FavoriteUpdate filter enabled must be canonical boolean"
                        identityField == "fid" && identity.toLongOrNull() == null ->
                            "FavoriteUpdate FID must be numeric"
                        identityField == "fid" && operation.entityId.value != "fid:$identity" ->
                            "FavoriteUpdate FID entity id mismatch"
                        identityField == "categorySyncId" &&
                            operation.entityId.value != "category:$identity" ->
                            "FavoriteUpdate category entity id mismatch"
                        else -> null
                    }
                },
            )
        }
    }
}
