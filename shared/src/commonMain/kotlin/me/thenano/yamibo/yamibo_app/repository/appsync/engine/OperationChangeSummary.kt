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
        .distinctBy { it.operationId }
        .filter { it.operationId in winnerIds }
        .groupingBy { Triple(direction, it.domainId.value, it.changeAction()) }
        .eachCount()
        .map { (key, count) ->
            OperationChangeSummary(key.first, key.second, key.third, count)
        }
        .sortedWith(compareBy({ it.direction }, { it.domainId }, { it.action }))

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
        )
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
