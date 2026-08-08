package me.thenano.yamibo.yamibo_app.profile

import kotlinx.coroutines.CancellationException

internal suspend fun runLoginWafPrewarm(
    prewarm: suspend () -> Boolean,
    onComplete: () -> Unit,
) {
    try {
        prewarm()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
    } finally {
        onComplete()
    }
}
