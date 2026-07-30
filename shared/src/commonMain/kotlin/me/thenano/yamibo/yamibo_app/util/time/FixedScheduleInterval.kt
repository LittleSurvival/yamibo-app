package me.thenano.yamibo.yamibo_app.util.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

enum class FixedScheduleUnit {
    Hours,
    Days,
    Weeks,
}

enum class FixedScheduleInterval(
    val storageKey: String,
    val duration: Duration,
    val displayValue: Int,
    val displayUnit: FixedScheduleUnit,
) {
    Hours1("1h", 1.hours, 1, FixedScheduleUnit.Hours),
    Hours2("2h", 2.hours, 2, FixedScheduleUnit.Hours),
    Hours3("3h", 3.hours, 3, FixedScheduleUnit.Hours),
    Hours4("4h", 4.hours, 4, FixedScheduleUnit.Hours),
    Hours6("6h", 6.hours, 6, FixedScheduleUnit.Hours),
    Hours12("12h", 12.hours, 12, FixedScheduleUnit.Hours),
    Hours24("24h", 24.hours, 24, FixedScheduleUnit.Hours),
    Days1("1d", 1.days, 1, FixedScheduleUnit.Days),
    Days2("2d", 2.days, 2, FixedScheduleUnit.Days),
    Days3("3d", 3.days, 3, FixedScheduleUnit.Days),
    Days7("7d", 7.days, 7, FixedScheduleUnit.Days),
    Week1("1week", 7.days, 1, FixedScheduleUnit.Weeks),
    ;

    companion object {
        private val byStorageKey = entries.associateBy(FixedScheduleInterval::storageKey)

        fun fromStorageKey(
            storageKey: String?,
            fallback: FixedScheduleInterval,
        ): FixedScheduleInterval = storageKey?.let(byStorageKey::get) ?: fallback
    }
}
