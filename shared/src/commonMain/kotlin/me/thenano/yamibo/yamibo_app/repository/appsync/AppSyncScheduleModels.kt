package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

enum class AppSyncAutomaticTrigger {
    AppStartup,
    ForegroundExit,
}

data class AppSyncScheduleSettings(
    val syncOnAppStart: Boolean = false,
    val syncOnForegroundExit: Boolean = false,
    val periodicInterval: FixedScheduleInterval = FixedScheduleInterval.Hours6,
)

val AppSyncPeriodicIntervals: List<FixedScheduleInterval> = listOf(
    FixedScheduleInterval.Hours1,
    FixedScheduleInterval.Hours2,
    FixedScheduleInterval.Hours3,
    FixedScheduleInterval.Hours4,
    FixedScheduleInterval.Hours6,
    FixedScheduleInterval.Hours12,
    FixedScheduleInterval.Days1,
    FixedScheduleInterval.Days2,
    FixedScheduleInterval.Days3,
    FixedScheduleInterval.Week1,
)

internal fun appSyncIntervalFromStorageKey(storageKey: String?): FixedScheduleInterval =
    FixedScheduleInterval.fromStorageKey(
        storageKey = storageKey,
        fallback = FixedScheduleInterval.Hours6,
    ).takeIf(AppSyncPeriodicIntervals::contains)
        ?: FixedScheduleInterval.Hours6

fun AppSyncServicePhase.isDurableAutomaticTriggerOutcome(): Boolean = when (this) {
    AppSyncServicePhase.Active,
    AppSyncServicePhase.PausedAuth,
    AppSyncServicePhase.Quarantined,
    AppSyncServicePhase.Disabled,
    -> true
    AppSyncServicePhase.BootstrapRequired,
    AppSyncServicePhase.Running,
    AppSyncServicePhase.PausedProvider,
    AppSyncServicePhase.RetryPending,
    -> false
}
