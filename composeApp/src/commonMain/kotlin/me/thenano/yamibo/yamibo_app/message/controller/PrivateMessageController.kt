package me.thenano.yamibo.yamibo_app.message.controller

import androidx.compose.runtime.*
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.PrivateMessagePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.AppTaskState

internal sealed interface PrivateMessageState {
    data object Loading : PrivateMessageState
    data class Success(val page: PrivateMessagePage) : PrivateMessageState
    data class Error(val message: String) : PrivateMessageState
}

/** Owned by one conversation composition; sending remains owned by AppTaskManager. */
internal class PrivateMessageController(
    private val cachedPage: (Int?) -> PrivateMessagePage?,
    private val fetchPage: suspend (Int?) -> YamiboResult<PrivateMessagePage>,
    private val onRefreshFailure: (afterSend: Boolean, reason: String) -> Unit,
) {
    private val initialPage = cachedPage(null)
    var state by mutableStateOf(initialPage?.let(PrivateMessageState::Success) ?: PrivateMessageState.Loading)
        private set
    var currentPage by mutableIntStateOf(initialPage?.pageNav?.currentPage ?: 1)
        private set
    var input by mutableStateOf("")
    var refreshing by mutableStateOf(false)
        private set
    var sendTaskKey by mutableStateOf(initialPage?.let { AppTaskKey("message-send:${it.pmId}") })
        private set
    private var handledSubmissionId: Long? = null
    private var requestId = 0L
    private var activeLoad: Job? = null

    suspend fun loadPage(page: Int? = null, preferCache: Boolean = true, afterSend: Boolean = false): Unit = coroutineScope {
        val id = ++requestId
        activeLoad?.cancel()
        activeLoad = coroutineContext[Job]
        val previous = state as? PrivateMessageState.Success
        refreshing = previous != null
        if (previous == null) state = PrivateMessageState.Loading
        try {
            val cached = if (preferCache) cachedPage(page) else null
            val result = if (cached != null) YamiboResult.Success(cached) else fetchPage(page)
            // Some API versions return canceled requests as Failure values.
            coroutineContext.ensureActive()
            if (id != requestId) return@coroutineScope
            when (result) {
                is YamiboResult.Success -> {
                    currentPage = result.value.pageNav?.currentPage ?: page ?: 1
                    sendTaskKey = AppTaskKey("message-send:${result.value.pmId}")
                    state = PrivateMessageState.Success(result.value)
                }
                else -> {
                    val cause = (result as? YamiboResult.Failure)?.exception
                    if (cause is CancellationException) throw cause
                    if (previous != null) onRefreshFailure(afterSend, result.message())
                    else state = PrivateMessageState.Error(result.message())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            if (id == requestId) {
                val reason = error.message.orEmpty()
                if (previous != null) onRefreshFailure(afterSend, reason)
                else state = PrivateMessageState.Error(reason)
            }
        } finally {
            if (id == requestId) {
                activeLoad = null
                refreshing = false
            }
        }
    }

    suspend fun handleSendSuccess(succeeded: AppTaskState.Succeeded) {
        if (succeeded.key != sendTaskKey || handledSubmissionId == succeeded.submissionId) return
        handledSubmissionId = succeeded.submissionId
        input = ""
        loadPage(preferCache = false, afterSend = true)
    }
}

@Composable
internal fun PrivateMessageSendEffect(controller: PrivateMessageController, task: AppTaskState?) {
    val succeeded = task as? AppTaskState.Succeeded
    LaunchedEffect(controller, succeeded?.submissionId) {
        if (succeeded != null) controller.handleSendSuccess(succeeded)
    }
}
