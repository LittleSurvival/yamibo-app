package me.thenano.yamibo.yamibo_app.favorite.updates

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.RunState
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.RunStatus

internal enum class FavoriteUpdateAttemptResult {
    COMPLETED, RETRY, STOPPED,
}

/**
 * Ordinary WorkManager jobs have a limited execution window. Yield before that limit,
 * keeping the persisted RUNNING checkpoint so the next attempt can continue the same run.
 * OS cancellation also leaves the checkpoint intact; explicit user cancellation is stored
 * by the scheduler/receiver. No foreground service is needed for these bounded attempts.
 */
internal suspend fun runFavoriteUpdateAttempt(
    repository: FavoriteUpdateRepository,
    runId: String,
    nowMillis: () -> Long,
    onProgress: suspend (FavoriteUpdateRepository.RunSnapshot) -> Unit,
    budgetMillis: Long = 8 * 60_000L,
): FavoriteUpdateAttemptResult = coroutineScope {
    val initialStatus = repository.getRunSnapshot(runId)?.status
    if (initialStatus == RunStatus.COMPLETED) return@coroutineScope FavoriteUpdateAttemptResult.COMPLETED
    if (initialStatus != RunStatus.RUNNING) {
        return@coroutineScope FavoriteUpdateAttemptResult.STOPPED
    }
    val notificationJob = launch {
        var lastUpdateAt: Long? = null
        repository.state.collect { state ->
            if (state !is RunState.Running || state.snapshot.runId != runId) return@collect
            val now = nowMillis()
            val previousUpdateAt = lastUpdateAt
            if (previousUpdateAt == null || now - previousUpdateAt >= 2_000L) {
                onProgress(state.snapshot)
                lastUpdateAt = now
            }
        }
    }
    val finished = try {
        withTimeoutOrNull(budgetMillis) {
            repository.runUpdate(runId)
            true
        } ?: false
    } finally {
        // Prevent a delayed progress notification from overwriting a terminal notification.
        notificationJob.cancelAndJoin()
    }
    when (repository.getRunSnapshot(runId)?.status) {
        RunStatus.COMPLETED -> FavoriteUpdateAttemptResult.COMPLETED
        RunStatus.RUNNING -> if (!finished) FavoriteUpdateAttemptResult.RETRY else {
            error("Favorite update returned without a terminal state")
        }
        else -> FavoriteUpdateAttemptResult.STOPPED
    }
}
