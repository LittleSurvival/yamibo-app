package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeSummary
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.summarizeWinningOperations
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence

class OperationChangeSummaryTest {
    @Test
    fun onlyFinalWinnerIsReportedAndBooleanPatchUsesEnabledState() {
        val received = operation(
            device = "remote",
            kind = SyncOperationKind.Put,
            value = "true",
        )
        val uploaded = operation(
            device = "local",
            kind = SyncOperationKind.Patch,
            value = "false",
            causalContext = SyncCausalContext().advance(received.replicaKey, received.sequence),
        )
        val state = OperationReducer().reduce(operations = listOf(received, uploaded)).entities

        assertEquals(
            listOf(
                OperationChangeSummary(
                    direction = OperationChangeDirection.Uploaded,
                    domainId = "settings",
                    action = OperationChangeAction.Disabled,
                    count = 1,
                ),
            ),
            summarizeWinningOperations(listOf(received), listOf(uploaded), state),
        )
    }

    @Test
    fun rssEnabledPatchUsesToggleAction() {
        val uploaded = operation(
            device = "local",
            kind = SyncOperationKind.Patch,
            value = "false",
            domain = "rss.search-subscription",
            fields = mapOf("enabled" to "false", "updatedAt" to "10"),
        )
        val state = OperationReducer().reduce(operations = listOf(uploaded)).entities

        assertEquals(
            listOf(
                OperationChangeSummary(
                    direction = OperationChangeDirection.Uploaded,
                    domainId = "rss.search-subscription",
                    action = OperationChangeAction.Disabled,
                    count = 1,
                ),
            ),
            summarizeWinningOperations(emptyList(), listOf(uploaded), state),
        )
    }

    @Test
    fun favoriteUpdateDetailsAreBoundedAndExcludeInternalIds() {
        val operations = (1..7).map { index ->
            operation(
                device = "device-$index",
                kind = SyncOperationKind.Put,
                value = "true",
                domain = "favorite.update-fid-filter",
                entity = "fid:$index",
                fields = mapOf(
                    "fid" to index.toString(),
                    "enabled" to "true",
                ),
            )
        }
        val state = OperationReducer().reduce(operations = operations).entities

        val summary = summarizeWinningOperations(operations, emptyList(), state).single()

        assertEquals(7, summary.count)
        assertEquals((1..5).map { "FID $it" }, summary.details)
        assertEquals(2, summary.remainingDetailCount)
        assertFalse(summary.details.any { "fid:" in it })
    }

    @Test
    fun rssHistorySummaryUsesReadableContentInsteadOfStableIdentity() {
        val uploaded = operation(
            device = "local",
            kind = SyncOperationKind.Put,
            value = "unused",
            domain = "reading.rss-catalog",
            entity = "rss:canonical-subscription",
            fields = mapOf(
                "subscriptionSyncId" to "rss:canonical-subscription",
                "subscriptionTitle" to "訂閱名稱",
                "subscriptionQuery" to "query",
                "subscriptionPage" to "1",
                "threadId" to "1",
                "threadTitle" to "帖子標題",
                "threadPage" to "1",
                "postId" to "2",
                "postTitle" to "回覆標題",
                "authorId" to null,
                "anchorPostId" to "2",
                "anchorPostRatio" to null,
                "anchorBlockId" to null,
                "anchorBlockType" to null,
                "anchorBlockRatio" to null,
                "viewportHeight" to null,
                "firstVisibleItemIndex" to null,
                "firstVisibleItemOffset" to null,
                "lastVisitTime" to "10",
                "coverUrl" to null,
            ),
        )
        val state = OperationReducer().reduce(operations = listOf(uploaded)).entities

        val summary = summarizeWinningOperations(emptyList(), listOf(uploaded), state).single()

        assertEquals(listOf("帖子標題"), summary.details)
        assertFalse(summary.details.any { "canonical-subscription" in it })
    }

    private fun operation(
        device: String,
        kind: SyncOperationKind,
        value: String,
        domain: String = "settings",
        entity: String = "feature",
        fields: Map<String, String?> = mapOf("type" to "bool", "value" to value),
        causalContext: SyncCausalContext = SyncCausalContext(),
    ): SyncOperation {
        val deviceId = SyncDeviceId(device)
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(deviceId, epoch, sequence),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId(domain),
            entityId = SyncEntityId(entity),
            kind = kind,
            fields = fields,
            causalContext = causalContext,
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.UserAction,
        )
    }
}
