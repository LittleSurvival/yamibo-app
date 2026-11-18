package me.thenano.yamibo.yamibo_app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A failed request consumes at most one retry per newly available network path. */
internal class HomeNetworkRecovery {
    var available by mutableStateOf(false)
        private set
    private var generation by mutableStateOf(0L)
    private var failedRequestGeneration by mutableStateOf<Long?>(null)

    val retryReady: Boolean
        get() = available && failedRequestGeneration?.let { it < generation } == true

    fun returnedToForeground() {
        if (available && failedRequestGeneration == generation) generation++
    }

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
}
