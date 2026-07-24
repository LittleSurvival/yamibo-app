package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.thread.reader.debug.debugPerfLog
import kotlin.math.floor

internal data class ReaderProgressGeometry(
    val postId: Long,
    val title: String,
    val top: Int,
    val bottom: Int,
)

internal data class ReaderProgressResult(
    val progressPercent: Int,
    val read: Boolean,
)

internal class ReaderScrollSession {
    private var generation = 0
    private var active = false
    private var startIndex = 0
    private var maxIndex = 0
    private var idleJob: Job? = null

    fun start(currentIndex: Int): Int {
        if (!active) {
            generation += 1
            active = true
            startIndex = currentIndex
            maxIndex = currentIndex
        }
        return generation
    }

    fun observe(currentIndex: Int): IntRange? {
        if (!active || currentIndex <= maxIndex) return null
        val crossedIndices = maxIndex until currentIndex
        maxIndex = currentIndex
        return crossedIndices
    }

    fun isActive(expectedGeneration: Int): Boolean = active && generation == expectedGeneration

    fun activeGeneration(): Int? = generation.takeIf { active }

    fun scheduleIdle(scope: CoroutineScope, block: suspend () -> Unit) {
        idleJob?.cancel()
        idleJob = scope.launch { block() }
    }

    fun cancel(expectedGeneration: Int) {
        if (active && generation == expectedGeneration) {
            active = false
            idleJob?.cancel()
            idleJob = null
        }
    }

    fun finish(expectedGeneration: Int, currentIndex: Int): IntRange? {
        if (!active || generation != expectedGeneration) return null
        val crossedIndices = observe(currentIndex) ?: IntRange.EMPTY
        active = false
        idleJob = null
        return crossedIndices
    }
}

internal fun calculateReaderProgress(
    geometry: ReaderProgressGeometry,
    viewportTop: Int,
    viewportBottom: Int,
    allowPassedShortPost: Boolean = false,
): ReaderProgressResult? {
    val viewportHeight = viewportBottom - viewportTop
    val postHeight = geometry.bottom - geometry.top
    if (viewportHeight <= 0 || postHeight <= 0) return null

    if (postHeight <= viewportHeight) {
        return if (
            geometry.bottom in (viewportTop + 1)..viewportBottom &&
            (geometry.top >= viewportTop || allowPassedShortPost)
        ) {
            ReaderProgressResult(progressPercent = 100, read = true)
        } else {
            null
        }
    }

    if (geometry.bottom <= viewportBottom) {
        return ReaderProgressResult(progressPercent = 100, read = true)
    }
    if (geometry.top > viewportTop) return null

    val scrollableHeight = postHeight - viewportHeight
    val traversed = (viewportTop - geometry.top).coerceAtLeast(0)
    val percent = floor((traversed.toDouble() / scrollableHeight.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 99)
    return ReaderProgressResult(progressPercent = percent, read = false)
}

internal class ReaderProgressCoordinator(
    private val repository: ChapterStateRepository,
    private val parentId: Long,
    scope: CoroutineScope,
) {
    val chapterStates: StateFlow<Map<Long, ChapterStateRepository.Entry>> =
        repository.observeEntriesByParent(
            targetType = ChapterStateRepository.TargetType.ThreadPost,
            parentId = parentId,
        ).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val writeMutex = Mutex()
    private val itemHeights = HashMap<String, Int>()
    private val pendingReadPosts = LinkedHashMap<Long, String>()
    private val submittedStates = HashMap<Long, Pair<Int, Boolean>>()
    private var writesBlockedUntilScroll = false

    fun updateItemHeight(key: String, heightPx: Int) {
        if (heightPx > 0) itemHeights[key] = heightPx
    }

    fun itemHeight(key: String): Int? = itemHeights[key]

    fun isRead(postId: Long): Boolean =
        submittedStates[postId]?.second ?: (chapterStates.value[postId]?.read == true)

    fun recordCrossedPost(postId: Long, title: String) {
        if (!writesBlockedUntilScroll) pendingReadPosts[postId] = title
    }

    fun noteScrollStarted() {
        writesBlockedUntilScroll = false
    }

    suspend fun applyProgress(updates: List<ChapterStateRepository.ProgressUpdate>) {
        writeMutex.withLock {
            if (writesBlockedUntilScroll) {
                debugPerfLog("progress_write_blocked|updates=${updates.size}")
                return
            }
            val merged = LinkedHashMap<Long, ChapterStateRepository.ProgressUpdate>()
            pendingReadPosts.forEach { (postId, title) ->
                val existing = chapterStates.value[postId]
                if (existing?.read != true && submittedStates[postId] != (100 to true)) {
                    merged[postId] = update(postId, title, progressPercent = 100, read = true)
                }
            }
            updates.forEach { candidate ->
                val existingState = chapterStates.value[candidate.targetId]
                val submittedState = submittedStates[candidate.targetId]
                val effectiveState = submittedState
                    ?: existingState?.let { it.progressPercent to it.read }
                val candidateState = candidate.progressPercent to candidate.read
                if ((submittedState?.second ?: existingState?.read) == true || effectiveState == candidateState) {
                    return@forEach
                }
                if (
                    effectiveState != null &&
                    !effectiveState.second &&
                    !candidate.read &&
                    candidate.progressPercent < effectiveState.first
                ) {
                    return@forEach
                }
                val existing = merged[candidate.targetId]
                merged[candidate.targetId] = if (existing?.read == true) existing else candidate
            }
            if (merged.isEmpty()) {
                pendingReadPosts.keys.removeAll { chapterStates.value[it]?.read == true }
                debugPerfLog("progress_write_skipped|updates=${updates.size}")
                return
            }
            debugPerfLog("progress_write|updates=${merged.size}|targets=${merged.keys.joinToString(",")}")
            repository.applyProgressUpdates(merged.values.toList())
            merged.forEach { (postId, update) ->
                submittedStates[postId] = update.progressPercent to update.read
            }
            pendingReadPosts.keys.removeAll(merged.keys)
        }
    }

    suspend fun setRead(postId: Long, title: String, read: Boolean) {
        writeMutex.withLock {
            repository.setRead(
                targetType = ChapterStateRepository.TargetType.ThreadPost,
                parentId = parentId,
                targetId = postId,
                title = title,
                read = read,
            )
            submittedStates[postId] = (if (read) 100 else 0) to read
            pendingReadPosts.remove(postId)
        }
    }

    suspend fun clearAll(clearThreadHistory: suspend () -> Unit) {
        writeMutex.withLock {
            writesBlockedUntilScroll = true
            pendingReadPosts.clear()
            submittedStates.clear()
            repository.clearParent(
                targetType = ChapterStateRepository.TargetType.ThreadPost,
                parentId = parentId,
            )
            clearThreadHistory()
        }
    }

    fun update(
        postId: Long,
        title: String,
        progressPercent: Int,
        read: Boolean,
        lastPageIndex: Int? = null,
        totalPages: Int? = null,
    ) = ChapterStateRepository.ProgressUpdate(
        targetType = ChapterStateRepository.TargetType.ThreadPost,
        parentId = parentId,
        targetId = postId,
        title = title,
        progressPercent = progressPercent,
        read = read,
        lastPageIndex = lastPageIndex,
        totalPages = totalPages,
    )
}
