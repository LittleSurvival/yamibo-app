package me.thenano.yamibo.yamibo_app.appsync

object AndroidAppSyncLifecycleBridge {
    private var controller: AppSyncLifecycleController? = null
    private var foreground = false
    private var sessionGeneration = 0L
    private var deliveredSessionGeneration = 0L

    fun attach(value: AppSyncLifecycleController) {
        controller = value
        value.reconcileRegistration()
        deliverStartupIfNeeded()
    }

    fun detach(value: AppSyncLifecycleController) {
        if (controller === value) controller = null
    }

    fun onActivityStarted() {
        if (!foreground) {
            foreground = true
            sessionGeneration += 1
        }
        deliverStartupIfNeeded()
    }

    fun onActivityStopped(changingConfigurations: Boolean) {
        if (changingConfigurations || !foreground) return
        foreground = false
        controller?.onForegroundSessionExited()
    }

    private fun deliverStartupIfNeeded() {
        val current = controller ?: return
        if (!foreground || deliveredSessionGeneration == sessionGeneration) return
        deliveredSessionGeneration = sessionGeneration
        current.onForegroundSessionStarted()
    }

    internal fun resetForTest() {
        controller = null
        foreground = false
        sessionGeneration = 0
        deliveredSessionGeneration = 0
    }
}
