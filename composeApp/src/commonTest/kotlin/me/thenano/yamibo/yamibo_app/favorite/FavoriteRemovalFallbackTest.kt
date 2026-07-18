package me.thenano.yamibo.yamibo_app.favorite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationResult
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteRemovalFallbackTest {
    @Test
    fun remoteSuccessRemovesOnceWithoutConfirmation() = runBlocking {
        val runtime = runtime()
        val repository = FakeFavoriteSyncRepository(result(true))

        complete(runtime, repository)

        assertEquals(listOf(true), repository.removeRemoteCalls)
        assertEquals("已從百合會取消收藏", runtime.feedback.deliveries.first().event.message)
        assertNull(withTimeoutOrNull(50) { runtime.confirmation.deliveries.first() })
        runtime.close()
    }

    @Test
    fun everyRemoteFailureReasonIsShownCompletelyAndDismissKeepsLocalFavorite() = runBlocking {
        val reasons = listOf(
            "目前未登入百合會，無法同步刪除網站收藏。",
            "沒有權限解除這筆收藏",
            "百合會目前維護中，請稍後再試。",
            "HTTP 500: complete server response",
        )
        for (reason in reasons) {
            val runtime = runtime()
            val repository = FakeFavoriteSyncRepository(result(false, reason))
            val operation = async { complete(runtime, repository) }

            val delivery = runtime.confirmation.deliveries.first()
            assertEquals("無法從百合會解除收藏", delivery.event.title)
            assertEquals(reason, delivery.event.message)
            assertEquals("僅解除本地收藏", delivery.event.confirmLabel)
            assertEquals("保留本地收藏", delivery.event.dismissLabel)
            assertTrue(delivery.event.dismissOnBackPress)
            assertTrue(delivery.event.dismissOnClickOutside)
            runtime.confirmation.resolve(delivery.id, AppConfirmationResult.Dismissed)
            operation.await()

            assertEquals(listOf(true), repository.removeRemoteCalls)
            runtime.close()
        }
    }

    @Test
    fun confirmedFallbackRemovesLocalOnlyAndDoesNotRetryRemote() = runBlocking {
        val runtime = runtime()
        val repository = FakeFavoriteSyncRepository(
            result(false, "remote failed"),
            result(true),
        )
        val operation = async { complete(runtime, repository) }
        val delivery = runtime.confirmation.deliveries.first()

        runtime.confirmation.resolve(delivery.id, AppConfirmationResult.Confirmed)
        operation.await()
        repository.awaitCallCount(2)

        assertEquals(listOf(true, false), repository.removeRemoteCalls)
        assertEquals(
            "已解除本地收藏，百合會收藏仍可能存在",
            runtime.feedback.deliveries.first().event.message,
        )
        runtime.close()
    }

    @Test
    fun localDeleteFailurePublishesReasonAndDoesNotSendAnotherRemoteRequest() = runBlocking {
        val runtime = runtime()
        val repository = FakeFavoriteSyncRepository(
            result(false, "remote failed"),
            result(false, "local database failed"),
        )
        val operation = async { complete(runtime, repository) }
        val delivery = runtime.confirmation.deliveries.first()

        runtime.confirmation.resolve(delivery.id, AppConfirmationResult.Confirmed)
        operation.await()
        repository.awaitCallCount(2)

        assertEquals(listOf(true, false), repository.removeRemoteCalls)
        assertEquals("local database failed", runtime.feedback.deliveries.first().event.message)
        runtime.close()
    }

    @Test
    fun duplicateConfirmedFallbackSubmitsOnlyOneActiveLocalDelete() = runBlocking {
        val runtime = runtime()
        val localGate = CompletableDeferred<Unit>()
        val repository = FakeFavoriteSyncRepository(
            result(false, "remote failed one"),
            result(false, "remote failed two"),
            result(true),
            localGate = localGate,
        )
        val first = async { complete(runtime, repository) }
        val second = async { complete(runtime, repository) }
        val deliveries = listOf(
            runtime.confirmation.deliveries.first(),
            runtime.confirmation.deliveries.first(),
        )

        deliveries.forEach {
            runtime.confirmation.resolve(it.id, AppConfirmationResult.Confirmed)
        }
        first.await()
        second.await()
        repository.awaitCallCount(3)
        assertEquals(listOf(true, true, false), repository.removeRemoteCalls)

        localGate.complete(Unit)
        runtime.close()
    }

    @Test
    fun appTaskContinuesAfterInitiatingObserverIsDiscarded() = runBlocking {
        val runtime = runtime()
        val repository = FakeFavoriteSyncRepository(
            result(false, "remote failed"),
            result(true),
        )
        runtime.tasks.launch(AppTaskKey("favorite:remove:thread:42")) {
            complete(runtime, repository)
        }

        val delivery = runtime.confirmation.deliveries.first()
        runtime.confirmation.resolve(delivery.id, AppConfirmationResult.Confirmed)
        repository.awaitCallCount(2)

        assertEquals(listOf(true, false), repository.removeRemoteCalls)
        runtime.close()
    }

    private suspend fun complete(runtime: Runtime, repository: FavoriteSyncRepository) {
        completeFavoriteRemovalByItemIdWithFeedback(
            itemId = 42L,
            removeRemote = true,
            favoriteSyncRepository = repository,
            feedbackController = runtime.feedback,
            confirmationController = runtime.confirmation,
            appTaskManager = runtime.tasks,
            remoteSuccessMessage = "已從百合會取消收藏",
            confirmationTitle = "無法從百合會解除收藏",
            localOnlyConfirmLabel = "僅解除本地收藏",
            keepLocalDismissLabel = "保留本地收藏",
            localOnlySuccessMessage = "已解除本地收藏，百合會收藏仍可能存在",
            successMessage = "removed",
            failureMessage = "failed",
        )
    }

    private fun kotlinx.coroutines.CoroutineScope.runtime(): Runtime {
        val feedback = AppFeedbackController()
        val confirmation = AppConfirmationController(this)
        return Runtime(feedback, confirmation, AppTaskManager(this, feedback))
    }

    private data class Runtime(
        val feedback: AppFeedbackController,
        val confirmation: AppConfirmationController,
        val tasks: AppTaskManager,
    ) {
        fun close() {
            confirmation.close()
            feedback.close()
        }
    }

    private class FakeFavoriteSyncRepository(
        vararg results: FavoriteSyncRepository.FavoriteSyncDeleteResult,
        private val localGate: CompletableDeferred<Unit>? = null,
    ) : FavoriteSyncRepository {
        private val results = results.toList()
        private var resultIndex = 0
        val removeRemoteCalls = mutableListOf<Boolean>()
        override val state: StateFlow<FavoriteSyncRepository.FavoriteSyncState> =
            MutableStateFlow(FavoriteSyncRepository.FavoriteSyncState.Idle)

        override suspend fun removeLocalFavoriteItem(
            itemId: Long,
            removeRemote: Boolean,
        ): FavoriteSyncRepository.FavoriteSyncDeleteResult {
            removeRemoteCalls += removeRemote
            if (!removeRemote) localGate?.await()
            return results[resultIndex++]
        }

        suspend fun awaitCallCount(count: Int) {
            withTimeout(1_000) {
                while (removeRemoteCalls.size < count) yield()
            }
        }

        override suspend fun startRemoteImport(targetCategoryId: Long): String = error("Not used")
        override fun observeRun(runId: String): Flow<FavoriteSyncRepository.FavoriteSyncState> = error("Not used")
        override suspend fun resumeInterruptedRun(): String? = error("Not used")
        override suspend fun cancelUiAttachment(runId: String) = error("Not used")
        override suspend fun interruptRun(runId: String) = error("Not used")
        override suspend fun markRunInterrupted(runId: String, reason: String) = error("Not used")
        override suspend fun getLatestSnapshot(): FavoriteSyncRepository.FavoriteSyncSnapshot? = error("Not used")
        override suspend fun runImport(runId: String) = error("Not used")
        override suspend fun syncLocalFavoriteItem(itemId: Long): FavoriteSyncRepository.FavoriteSyncActionResult = error("Not used")
        override suspend fun hasRemoteFavorite(itemId: Long): Boolean = error("Not used")
        override suspend fun getRemoteFavoriteOrderMap(itemIds: Set<Long>): Map<Long, Long> = error("Not used")
        override suspend fun removeLocalFavoriteItems(
            itemIds: Set<Long>,
            removeRemote: Boolean,
        ): FavoriteSyncRepository.FavoriteSyncBulkDeleteResult = error("Not used")
    }

    private fun result(success: Boolean, message: String? = null) =
        FavoriteSyncRepository.FavoriteSyncDeleteResult(success, message)
}
