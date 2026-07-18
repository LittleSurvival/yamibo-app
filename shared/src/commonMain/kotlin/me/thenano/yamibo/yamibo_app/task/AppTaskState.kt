package me.thenano.yamibo.yamibo_app.task

sealed interface AppTaskState {
    val key: AppTaskKey
    val submissionId: Long

    data class Queued(
        override val key: AppTaskKey,
        override val submissionId: Long,
    ) : AppTaskState

    data class Running(
        override val key: AppTaskKey,
        override val submissionId: Long,
    ) : AppTaskState

    data class Succeeded(
        override val key: AppTaskKey,
        override val submissionId: Long,
    ) : AppTaskState

    data class Failed(
        override val key: AppTaskKey,
        override val submissionId: Long,
        val message: String?,
        val cause: Throwable?,
    ) : AppTaskState

    data class Canceled(
        override val key: AppTaskKey,
        override val submissionId: Long,
    ) : AppTaskState
}

val AppTaskState.isActive: Boolean
    get() = this is AppTaskState.Queued || this is AppTaskState.Running
