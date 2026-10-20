package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.flow.StateFlow

interface FavoriteUpdateRepository {
    enum class TargetMode {
        NormalThread,
        NovelThread,
        TagManga,
        RssSearch,
    }

    enum class RunStatus {
        RUNNING,
        INTERRUPTED,
        FAILED,
        COMPLETED,
        CANCELED,
    }

    enum class RunPhase {
        PREPARING,
        CHECKING,
        INTERRUPTED,
        FAILED,
        COMPLETED,
        CANCELED,
    }

    data class UpdateEvent(
        val id: Long,
        val targetType: FavoriteStoreRepository.FavoriteTargetType,
        val targetId: Long,
        val authorId: Long?,
        val fid: Int?,
        val forumName: String?,
        val title: String,
        val latestPostTitle: String?,
        val mode: TargetMode,
        val summary: String,
        val detailIds: List<Long>,
        val coverUrl: String?,
        val detectedAt: Long,
        val readAt: Long?,
        val dismissedAt: Long?,
        val ambiguous: Boolean,
    )

    data class FidFilter(
        val fid: Int,
        val forumName: String,
        val enabled: Boolean,
        val itemCount: Int,
    )

    data class CategoryFilter(
        val categoryId: Long,
        val categoryName: String,
        val enabled: Boolean,
        val itemCount: Int,
    )

    data class ScopeTarget(
        val fid: Int?,
        val categoryIds: Set<Long>,
    )

    data class RunSnapshot(
        val runId: String,
        val status: RunStatus,
        val phase: RunPhase,
        val startedAt: Long,
        val updatedAt: Long,
        val finishedAt: Long?,
        val totalCount: Int,
        val completedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val detectedCount: Int,
        val currentItem: String?,
        val logMessage: String?,
        val warningMessage: String?,
        val errorMessage: String?,
    )

    sealed interface RunState {
        data object Idle : RunState
        data class Running(val snapshot: RunSnapshot) : RunState
        data class Interrupted(val snapshot: RunSnapshot) : RunState
        data class Failed(val snapshot: RunSnapshot) : RunState
        data class Completed(val snapshot: RunSnapshot) : RunState
    }

    val state: StateFlow<RunState>

    suspend fun startRun(runId: String? = null): String
    suspend fun resumeInterruptedRun(): String?
    suspend fun interruptRun(runId: String)
    suspend fun cancelRun(runId: String)
    suspend fun markRunInterrupted(runId: String, reason: String)
    suspend fun getLatestSnapshot(): RunSnapshot?
    suspend fun getRunSnapshot(runId: String): RunSnapshot?
    suspend fun runUpdate(runId: String)
    suspend fun getActiveEvents(): List<UpdateEvent>
    suspend fun getActiveEventsFiltered(): List<UpdateEvent>
    suspend fun markEventRead(eventId: Long)
    suspend fun dismissEvent(eventId: Long)
    suspend fun dismissEvents(eventIds: List<Long>)
    suspend fun dismissAllEvents()
    suspend fun getFidFilters(): List<FidFilter>
    suspend fun setFidEnabled(fid: Int, enabled: Boolean)
    suspend fun getCategoryFilters(): List<CategoryFilter>
    suspend fun setCategoryEnabled(categoryId: Long, enabled: Boolean)
    suspend fun getScopeTargets(): List<ScopeTarget>
}
