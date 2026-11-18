package me.thenano.yamibo.yamibo_app.home

/** A failed request consumes at most one retry per newly available network path. */
internal class HomeNetworkRecovery {
    private var available = false
    private var generation = 0L
    private var failedRequestGeneration: Long? = null

    fun availabilityChanged(isAvailable: Boolean) {
        if (isAvailable && !available) generation++
        available = isAvailable
    }

    fun requestStarted(): Long {
        failedRequestGeneration = null
        return generation
    }

    fun requestFailed(requestGeneration: Long, isNetworkFailure: Boolean) {
        failedRequestGeneration = requestGeneration.takeIf { isNetworkFailure }
    }

    fun takeRetry(): Boolean {
        val failedGeneration = failedRequestGeneration ?: return false
        if (!available || generation <= failedGeneration) return false
        failedRequestGeneration = null
        return true
    }
}
