package me.thenano.yamibo.yamibo_app.task

import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent

sealed interface AppTaskResult {
    val feedback: AppFeedbackEvent?

    data class Success(
        override val feedback: AppFeedbackEvent? = null,
    ) : AppTaskResult

    data class Failure(
        val message: String? = null,
        val cause: Throwable? = null,
        override val feedback: AppFeedbackEvent? = null,
    ) : AppTaskResult
}
