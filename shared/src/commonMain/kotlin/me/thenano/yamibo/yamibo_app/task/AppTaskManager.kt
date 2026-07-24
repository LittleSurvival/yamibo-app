package me.thenano.yamibo.yamibo_app.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent

class AppTaskHandle internal constructor(
    val key: AppTaskKey,
    val submissionId: Long,
    val state: StateFlow<AppTaskState>,
    private val cancelAction: () -> Unit,
) {
    fun cancel() = cancelAction()
}

class AppTaskManager(
    private val scope: CoroutineScope,
    private val feedbackController: AppFeedbackController,
) {
    private data class Entry(
        val state: MutableStateFlow<AppTaskState>,
        val handle: AppTaskHandle,
        var job: Job? = null,
    )

    private val entries = mutableMapOf<AppTaskKey, Entry>()
    private val _tasks = MutableStateFlow<Map<AppTaskKey, AppTaskState>>(emptyMap())
    private val terminalOrder = ArrayDeque<TerminalRecord>()
    private var nextSubmissionId = 0L

    val tasks: StateFlow<Map<AppTaskKey, AppTaskState>> = _tasks.asStateFlow()

    fun launch(
        key: AppTaskKey,
        duplicatePolicy: AppTaskDuplicatePolicy = AppTaskDuplicatePolicy.KeepExisting,
        instanceId: String? = null,
        startedFeedback: AppFeedbackEvent? = null,
        operation: suspend () -> Unit,
    ): AppTaskHandle = submit(
        key = key,
        duplicatePolicy = duplicatePolicy,
        instanceId = instanceId,
        startedFeedback = startedFeedback,
    ) {
        operation()
        AppTaskResult.Success()
    }

    fun submit(
        key: AppTaskKey,
        duplicatePolicy: AppTaskDuplicatePolicy = AppTaskDuplicatePolicy.KeepExisting,
        instanceId: String? = null,
        startedFeedback: AppFeedbackEvent? = null,
        operation: suspend () -> AppTaskResult,
    ): AppTaskHandle {
        val effectiveKey = when (duplicatePolicy) {
            AppTaskDuplicatePolicy.AllowParallel -> key.withInstance(requireNotNull(instanceId) {
                "AllowParallel requires an instance ID"
            })
            else -> key
        }
        val existing = entries[effectiveKey]
        if (existing != null && existing.state.value.isActive) {
            when (duplicatePolicy) {
                AppTaskDuplicatePolicy.KeepExisting,
                AppTaskDuplicatePolicy.AllowParallel,
                -> return existing.handle

                AppTaskDuplicatePolicy.ReplacePending -> {
                    if (existing.state.value is AppTaskState.Running) return existing.handle
                    existing.job?.cancel()
                }
            }
        }

        val submissionId = ++nextSubmissionId
        val state = MutableStateFlow<AppTaskState>(AppTaskState.Queued(effectiveKey, submissionId))
        lateinit var entry: Entry
        val handle = AppTaskHandle(
            key = effectiveKey,
            submissionId = submissionId,
            state = state.asStateFlow(),
            cancelAction = { cancel(effectiveKey, submissionId) },
        )
        entry = Entry(state = state, handle = handle)
        entries[effectiveKey] = entry
        publish(state.value)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            transition(entry, AppTaskState.Running(effectiveKey, submissionId))
            startedFeedback?.let(feedbackController::post)
            try {
                when (val result = operation()) {
                    is AppTaskResult.Success -> {
                        transition(entry, AppTaskState.Succeeded(effectiveKey, submissionId))
                        result.feedback?.let { feedback ->
                            feedbackController.post(
                                if (feedback.kind == me.thenano.yamibo.yamibo_app.feedback.AppFeedbackKind.Info) {
                                    feedback.copy(kind = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackKind.Success)
                                } else {
                                    feedback
                                },
                            )
                        }
                    }
                    is AppTaskResult.Failure -> {
                        transition(
                            entry,
                            AppTaskState.Failed(effectiveKey, submissionId, result.message, result.cause),
                        )
                        result.feedback?.let { feedback ->
                            feedbackController.post(
                                if (feedback.kind == me.thenano.yamibo.yamibo_app.feedback.AppFeedbackKind.Info) {
                                    feedback.copy(kind = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackKind.Error)
                                } else {
                                    feedback
                                },
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                transition(entry, AppTaskState.Canceled(effectiveKey, submissionId))
                throw error
            } catch (error: Throwable) {
                transition(
                    entry,
                    AppTaskState.Failed(effectiveKey, submissionId, error.message, error),
                )
            }
        }
        entry.job = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && entry.state.value.isActive) {
                transition(entry, AppTaskState.Canceled(effectiveKey, submissionId))
            }
        }
        job.start()
        return handle
    }

    fun state(key: AppTaskKey): AppTaskState? = tasks.value[key]

    private fun cancel(key: AppTaskKey, submissionId: Long) {
        val entry = entries[key] ?: return
        if (entry.state.value.submissionId != submissionId) return
        entry.job?.cancel()
    }

    private fun transition(entry: Entry, state: AppTaskState) {
        entry.state.value = state
        if (entries[state.key] === entry) {
            val published = (tasks.value + (state.key to state)).toMutableMap()
            if (!state.isActive) {
                terminalOrder.addLast(TerminalRecord(state.key, state.submissionId))
                while (terminalOrder.size > MAX_RETAINED_TERMINAL_TASKS) {
                    val expired = terminalOrder.removeFirst()
                    val expiredEntry = entries[expired.key] ?: continue
                    if (
                        expiredEntry.state.value.submissionId == expired.submissionId &&
                        !expiredEntry.state.value.isActive
                    ) {
                        entries.remove(expired.key)
                        published -= expired.key
                    }
                }
            }
            _tasks.value = published
        }
    }

    private fun publish(state: AppTaskState) {
        _tasks.value += (state.key to state)
    }

    private data class TerminalRecord(
        val key: AppTaskKey,
        val submissionId: Long,
    )

    private companion object {
        const val MAX_RETAINED_TERMINAL_TASKS = 128
    }
}
