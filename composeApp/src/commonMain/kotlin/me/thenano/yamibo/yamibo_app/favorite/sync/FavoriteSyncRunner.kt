package me.thenano.yamibo.yamibo_app.favorite.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.BackgroundTaskRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncState
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import kotlin.time.Duration.Companion.seconds

class FavoriteSyncRunner(
    private val repository: FavoriteSyncRepository,
    private val backgroundTaskRepository: BackgroundTaskRepository,
    private val prepareRemoteAccess: suspend () -> String? = { null },
) {
    sealed interface LaunchResult {
        data class Started(val runId: String) : LaunchResult
        data class Rejected(
            val reason: String,
            val runId: String? = null,
            val requiresBackgroundAccessSetup: Boolean = false,
        ) : LaunchResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dismissedFavoritePageRuns = MutableStateFlow<Set<String>>(emptySet())
    private val pendingActivationStartedAt = linkedMapOf<String, Long>()
    private val stateFlow = MutableStateFlow<FavoriteSyncState>(FavoriteSyncState.Idle)

    val state: StateFlow<FavoriteSyncState> = stateFlow.asStateFlow()
    val hiddenFavoritePageRuns: StateFlow<Set<String>> = dismissedFavoritePageRuns.asStateFlow()

    init {
        scope.launch {
            while (true) {
                refreshStateFromRepository()
                delay(1.seconds)
            }
        }
    }

    suspend fun startImport(targetCategoryId: Long): LaunchResult {
        val runningState = state.value as? FavoriteSyncState.Running
        if (runningState != null) {
            return LaunchResult.Started(runningState.snapshot.runId)
        }

        prepareRemoteAccess()?.let { reason ->
            return LaunchResult.Rejected(reason = reason)
        }
        val runId = repository.startRemoteImport(targetCategoryId)
        return startSyncTask(runId)
    }

    suspend fun resumeInterruptedImport(): LaunchResult? {
        val previousRunningRunId = (state.value as? FavoriteSyncState.Running)?.snapshot?.runId
        val resumable = repository.getLatestSnapshot() ?: return null
        prepareRemoteAccess()?.let { reason ->
            return LaunchResult.Rejected(reason = reason, runId = resumable.runId)
        }
        val runId = repository.resumeInterruptedRun() ?: return null
        if (previousRunningRunId != runId) {
            return startSyncTask(runId)
        }
        refreshStateFromRepository()
        return LaunchResult.Started(runId)
    }

    private suspend fun startSyncTask(runId: String): LaunchResult {
        dismissedFavoritePageRuns.value -= runId
        pendingActivationStartedAt[runId] = currentTimeMillis()
        return when (val result = backgroundTaskRepository.startFavoriteSync(runId)) {
            BackgroundTaskRepository.StartResult.Started -> {
                refreshStateFromRepository()
                LaunchResult.Started(runId)
            }
            is BackgroundTaskRepository.StartResult.Rejected -> {
                pendingActivationStartedAt.remove(runId)
                repository.markRunInterrupted(runId, result.reason)
                refreshStateFromRepository()
                LaunchResult.Rejected(
                    reason = i18n(result.reason),
                    runId = runId,
                    requiresBackgroundAccessSetup = result.requiresBackgroundAccessSetup,
                )
            }
        }
    }

    suspend fun interruptImport(runId: String) {
        backgroundTaskRepository.cancelFavoriteSync(runId)
    }

    fun dismissFavoritePageCard(runId: String) {
        dismissedFavoritePageRuns.value += runId
    }

    private suspend fun refreshStateFromRepository() {
        val runningRunIds = backgroundTaskRepository.runningFavoriteSyncRunIds.value
        pendingActivationStartedAt.entries.removeAll { (runId, startedAt) ->
            runId in runningRunIds || currentTimeMillis() - startedAt > 5000L
        }

        val latest = repository.getLatestSnapshot()
        if (latest?.status == FavoriteSyncRepository.FavoriteSyncStatus.RUNNING &&
            latest.runId !in runningRunIds &&
            latest.runId !in pendingActivationStartedAt.keys
        ) {
            repository.markRunInterrupted(latest.runId, i18n("背景同步任務已中斷，可重新同步。"))
        }

        stateFlow.value = repository.getLatestSnapshot()?.let { snapshot ->
            when (snapshot.status) {
                FavoriteSyncRepository.FavoriteSyncStatus.RUNNING -> FavoriteSyncState.Running(snapshot)
                FavoriteSyncRepository.FavoriteSyncStatus.INTERRUPTED -> FavoriteSyncState.Interrupted(snapshot)
                FavoriteSyncRepository.FavoriteSyncStatus.FAILED -> FavoriteSyncState.Failed(snapshot)
                FavoriteSyncRepository.FavoriteSyncStatus.COMPLETED -> FavoriteSyncState.Completed(snapshot)
            }
        } ?: FavoriteSyncState.Idle
    }
}
