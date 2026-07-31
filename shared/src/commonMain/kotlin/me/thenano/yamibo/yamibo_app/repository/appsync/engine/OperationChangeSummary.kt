package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind

internal enum class OperationChangeDirection {
    Received,
    Uploaded,
}

internal enum class OperationChangeAction {
    Added,
    Updated,
    Deleted,
    Enabled,
    Disabled,
    Read,
    Dismissed,
}

internal data class OperationChangeSummary(
    val direction: OperationChangeDirection,
    val domainId: String,
    val action: OperationChangeAction,
    val count: Int,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

internal fun summarizeWinningOperations(
    received: Collection<SyncOperation>,
    uploaded: Collection<SyncOperation>,
    state: Map<SyncEntityKey, ResolvedSyncEntity>,
): List<OperationChangeSummary> {
    val winnerIds = state.values.flatMapTo(linkedSetOf()) { entity ->
        buildList {
            entity.fields.values.forEach { add(it.operation.operationId) }
            entity.relationOperation?.let { add(it.operationId) }
            entity.tombstone?.let { add(it.operationId) }
        }
    }
    return buildList {
        addAll(summarize(OperationChangeDirection.Received, received, winnerIds))
        addAll(summarize(OperationChangeDirection.Uploaded, uploaded, winnerIds))
    }
}

private fun summarize(
    direction: OperationChangeDirection,
    operations: Collection<SyncOperation>,
    winnerIds: Set<SyncOperationId>,
): List<OperationChangeSummary> =
    operations
        .asSequence()
        .distinctBy { it.operationId }
        .filter { it.operationId in winnerIds }
        .groupBy { Triple(direction, it.domainId.value, it.changeAction()) }
        .map { (key, groupedOperations) ->
            val details = groupedOperations
                .mapNotNull { it.safeDisplayDetail() }
                .distinct()
                .take(MAX_DETAILS)
            OperationChangeSummary(
                direction = key.first,
                domainId = key.second,
                action = key.third,
                count = groupedOperations.size,
                details = details,
                remainingDetailCount = if (details.isEmpty()) {
                    0
                } else {
                    (groupedOperations.size - details.size).coerceAtLeast(0)
                },
            )
        }
        .sortedWith(compareBy({ it.direction }, { it.domainId }, { it.action }))
        .toList()

private fun SyncOperation.safeDisplayDetail(): String? = when (domainId.value) {
    "favorite.update-event" ->
        fields["title"].nonBlank()
            ?: fields["latestPostTitle"].nonBlank()
            ?: fields["forumName"].nonBlank()
            ?: "未命名更新項目"
    "favorite.update-fid-filter" ->
        fields["forumName"].nonBlank()
            ?: fields["fid"].nonBlank()?.let { "FID $it" }
            ?: "未命名版塊篩選"
    "favorite.update-category-filter" -> "分類更新篩選"
    "rss.search-subscription" ->
        fields["title"].nonBlank() ?: fields["query"].nonBlank()
    "reading.tag-manga",
    "reading.tag-catalog",
    -> fields["threadTitle"].nonBlank() ?: fields["tagName"].nonBlank()
    "reading.rss-search",
    "reading.rss-catalog",
    -> fields["threadTitle"].nonBlank()
        ?: fields["postTitle"].nonBlank()
        ?: fields["subscriptionTitle"].nonBlank()
    "favorite.item",
    "favorite.category",
    "favorite.collection",
    "reading.thread",
    -> fields["title"].nonBlank()
        ?: fields["name"].nonBlank()
        ?: fields["threadName"].nonBlank()
        ?: "未命名項目"
    else -> fields["title"].nonBlank() ?: fields["name"].nonBlank()
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun SyncOperation.changeAction(): OperationChangeAction {
    if (domainId.value == "favorite.update-event" && kind == SyncOperationKind.Patch) {
        return when {
            "dismissedAt" in fields -> OperationChangeAction.Dismissed
            "readAt" in fields -> OperationChangeAction.Read
            else -> OperationChangeAction.Updated
        }
    }
    if (
        domainId.value in setOf(
            "favorite.update-fid-filter",
            "favorite.update-category-filter",
            "rss.search-subscription",
        )
            && "enabled" in fields
    ) {
        return if (fields["enabled"] == "true") {
            OperationChangeAction.Enabled
        } else {
            OperationChangeAction.Disabled
        }
    }
    if (domainId.value == "settings" && fields["value"] in setOf("true", "false")) {
        return if (fields["value"] == "true") {
            OperationChangeAction.Enabled
        } else {
            OperationChangeAction.Disabled
        }
    }
    return when (kind) {
        SyncOperationKind.Put,
        SyncOperationKind.RelationAdd,
        -> OperationChangeAction.Added
        SyncOperationKind.Patch -> OperationChangeAction.Updated
        SyncOperationKind.Delete,
        SyncOperationKind.RelationRemove,
        -> OperationChangeAction.Deleted
    }
}

private const val MAX_DETAILS = 5
