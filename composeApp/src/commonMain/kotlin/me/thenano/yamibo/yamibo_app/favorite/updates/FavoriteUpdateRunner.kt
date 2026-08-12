package me.thenano.yamibo.yamibo_app.favorite.updates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteUpdateInterval
import kotlin.time.Duration.Companion.milliseconds

class FavoriteUpdateRunner(
    private val repository: FavoriteUpdateRepository,
    private val scheduler: FavoriteUpdateScheduler,
    private val prepareRemoteAccess: suspend () -> String? = { null },
) {
    sealed interface LaunchResult {
        data class Started(val runId: String) : LaunchResult
        data class Rejected(val reason: String, val runId: String? = null) : LaunchResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow<FavoriteUpdateRepository.RunState>(FavoriteUpdateRepository.RunState.Idle)
    private var syncJob: Job? = null

    val state: StateFlow<FavoriteUpdateRepository.RunState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            repository.state.collect { state ->
                stateFlow.value = state
                val snapshot = state.snapshotOrNull()
                if (snapshot != null && state is FavoriteUpdateRepository.RunState.Running) {
                    startSnapshotSync(snapshot.runId)
                }
            }
        }
    }

    suspend fun startManualUpdate(): LaunchResult {
        val latest = repository.getLatestSnapshot()
        if (latest != null && latest.status == FavoriteUpdateRepository.RunStatus.RUNNING) {
            startSnapshotSync(latest.runId)
            return LaunchResult.Started(latest.runId)
        }

        prepareRemoteAccess()?.let { reason ->
            return LaunchResult.Rejected(reason = reason)
        }
        val runId = repository.startRun()
        return startFavoriteUpdateTask(runId)
    }

    suspend fun resumeInterruptedUpdate(): LaunchResult? {
        val resumable = repository.getLatestSnapshot() ?: return null
        prepareRemoteAccess()?.let { reason ->
            return LaunchResult.Rejected(reason = reason, runId = resumable.runId)
        }
        val runId = repository.resumeInterruptedRun() ?: return null
        return startFavoriteUpdateTask(runId)
    }

    private suspend fun startFavoriteUpdateTask(runId: String): LaunchResult {
        startSnapshotSync(runId)
        return when (val result = scheduler.startFavoriteUpdate(runId)) {
            FavoriteUpdateScheduler.StartResult.Started -> LaunchResult.Started(runId)
            is FavoriteUpdateScheduler.StartResult.Rejected -> {
                repository.markRunInterrupted(runId, result.reason)
                syncRunSnapshot(runId)
                LaunchResult.Rejected(i18n(result.reason), runId)
            }
        }
    }

    suspend fun startGlobalRefresh(): LaunchResult = startManualUpdate()

    suspend fun cancelUpdate(runId: String) {
        scheduler.cancelFavoriteUpdate(runId)
        repository.cancelRun(runId)
        syncJob?.cancel()
        stateFlow.value = FavoriteUpdateRepository.RunState.Idle
    }

    suspend fun interruptUpdate(runId: String) {
        scheduler.cancelFavoriteUpdate(runId)
        repository.interruptRun(runId)
        syncRunSnapshot(runId)
    }

    suspend fun schedulePeriodicUpdate(interval: FavoriteUpdateInterval) {
        scheduler.schedulePeriodicFavoriteUpdate(interval)
    }

    private var currentSyncRunId: String? = null

    private fun startSnapshotSync(runId: String) {
        if (currentSyncRunId == runId && syncJob?.isActive == true) return
        syncJob?.cancel()
        currentSyncRunId = runId
        syncJob = scope.launch {
            try {
                while (true) {
                    val state = syncRunSnapshot(runId)
                    if (state !is FavoriteUpdateRepository.RunState.Running) break
                    delay(RUN_SYNC_INTERVAL_MS.milliseconds)
                }
            } finally {
                if (currentSyncRunId == runId) {
                    currentSyncRunId = null
                }
            }
        }
    }

    private suspend fun syncRunSnapshot(runId: String): FavoriteUpdateRepository.RunState {
        val state = repository.getRunSnapshot(runId)?.toState() ?: FavoriteUpdateRepository.RunState.Idle
        stateFlow.value = state
        return state
    }

    private fun FavoriteUpdateRepository.RunSnapshot.toState(): FavoriteUpdateRepository.RunState =
        when (status) {
            FavoriteUpdateRepository.RunStatus.RUNNING -> FavoriteUpdateRepository.RunState.Running(this)
            FavoriteUpdateRepository.RunStatus.INTERRUPTED -> FavoriteUpdateRepository.RunState.Interrupted(this)
            FavoriteUpdateRepository.RunStatus.FAILED -> FavoriteUpdateRepository.RunState.Failed(this)
            FavoriteUpdateRepository.RunStatus.COMPLETED -> FavoriteUpdateRepository.RunState.Completed(this)
            FavoriteUpdateRepository.RunStatus.CANCELED -> FavoriteUpdateRepository.RunState.Idle
        }

    private fun FavoriteUpdateRepository.RunState.snapshotOrNull(): FavoriteUpdateRepository.RunSnapshot? =
        when (this) {
            FavoriteUpdateRepository.RunState.Idle -> null
            is FavoriteUpdateRepository.RunState.Running -> snapshot
            is FavoriteUpdateRepository.RunState.Interrupted -> snapshot
            is FavoriteUpdateRepository.RunState.Failed -> snapshot
            is FavoriteUpdateRepository.RunState.Completed -> snapshot
        }

    companion object {
        private const val RUN_SYNC_INTERVAL_MS = 500L
    }
}

interface FavoriteUpdateScheduler {
    sealed interface StartResult {
        data object Started : StartResult
        data class Rejected(val reason: String) : StartResult
    }

    suspend fun startFavoriteUpdate(runId: String): StartResult
    suspend fun cancelFavoriteUpdate(runId: String)
    suspend fun schedulePeriodicFavoriteUpdate(interval: FavoriteUpdateInterval)
}
