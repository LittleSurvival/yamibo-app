package me.thenano.yamibo.yamibo_app.feedback

import kotlin.jvm.JvmInline

@JvmInline
value class AppFeedbackId(val value: Long)

enum class AppFeedbackDuration {
    Short,
    Long,
    Indefinite,
}

enum class AppFeedbackKind {
    Info,
    Success,
    Error,
}

enum class AppFeedbackResult {
    ActionPerformed,
    Dismissed,
}

data class AppFeedbackEvent(
    val message: String,
    val duration: AppFeedbackDuration = AppFeedbackDuration.Short,
    val kind: AppFeedbackKind = AppFeedbackKind.Info,
    val actionLabel: String? = null,
    val withDismissAction: Boolean = false,
    val groupKey: String? = null,
)

class AppFeedbackDelivery internal constructor(
    val id: AppFeedbackId,
    val event: AppFeedbackEvent,
    private val onResult: ((AppFeedbackResult) -> Unit)?,
) {
    private var resolved = false

    internal fun resolve(result: AppFeedbackResult): Boolean {
        if (resolved) return false
        resolved = true
        onResult?.invoke(result)
        return true
    }
}
