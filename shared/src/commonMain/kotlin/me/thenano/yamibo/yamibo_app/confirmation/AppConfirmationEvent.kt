package me.thenano.yamibo.yamibo_app.confirmation

import kotlinx.coroutines.CompletableDeferred
import kotlin.jvm.JvmInline

@JvmInline
value class AppConfirmationId(val value: Long)

enum class AppConfirmationResult {
    Confirmed,
    Dismissed,
}

data class AppConfirmationEvent(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
)

class AppConfirmationDelivery internal constructor(
    val id: AppConfirmationId,
    val event: AppConfirmationEvent,
    private val result: CompletableDeferred<AppConfirmationResult>,
) {
    suspend fun awaitResult(): AppConfirmationResult = result.await()
}
