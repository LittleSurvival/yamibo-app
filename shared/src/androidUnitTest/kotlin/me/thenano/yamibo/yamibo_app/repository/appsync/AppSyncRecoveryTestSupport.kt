package me.thenano.yamibo.yamibo_app.repository.appsync

internal enum class AppSyncRecoveryCrashPoint {
    BeforeSegmentWrite,
    AfterAmbiguousSegmentWrite,
    BeforeRootCreation,
    BeforeIndexCommit,
    BeforeLocalActivation,
}

internal class AppSyncRecoveryCrashInjector(
    private val crashAt: AppSyncRecoveryCrashPoint?,
) {
    fun reach(point: AppSyncRecoveryCrashPoint) {
        if (crashAt == point) throw SimulatedRecoveryCrash(point)
    }
}

internal class SimulatedRecoveryCrash(val point: AppSyncRecoveryCrashPoint) : RuntimeException(point.name)
