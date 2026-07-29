package me.thenano.yamibo.yamibo_app.repository.appsync.domain

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind

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
        return null
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

    fun requireValid(operation: SyncOperation): SyncDomainContract {
        val contract = requireNotNull(contractFor(operation.domainId)) {
            "Unknown sync domain: ${operation.domainId.value}"
        }
        contract.validate(operation)?.let { throw IllegalArgumentException(it) }
        return contract
    }

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
            "favorite.category",
            "favorite.collection",
            "detail-note",
            "bookmark",
            "reading.thread",
            "reading.image",
            "reading.tag-manga",
            "reading.time",
            "favorite.item-category",
            "favorite.item-collection",
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
                progressDomain("reading.time", monotonicFields = setOf("durationMillis")),
                relationDomain(
                    "favorite.item-category",
                    setOf("targetType", "targetId", "authorId", "categorySyncId"),
                ),
                relationDomain(
                    "favorite.item-collection",
                    setOf("targetType", "targetId", "authorId", "collectionSyncId"),
                ),
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
    }
}
