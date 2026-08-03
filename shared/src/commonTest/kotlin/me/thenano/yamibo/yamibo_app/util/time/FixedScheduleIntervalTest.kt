package me.thenano.yamibo.yamibo_app.util.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class FixedScheduleIntervalTest {
    @Test
    fun keysDecodeToTheSingleCanonicalValue() {
        FixedScheduleInterval.entries.forEach { interval ->
            assertEquals(
                interval,
                FixedScheduleInterval.fromStorageKey(
                    interval.storageKey,
                    FixedScheduleInterval.Hours6,
                ),
            )
        }
    }

    @Test
    fun unknownKeysUseTheProvidedFallback() {
        assertEquals(
            FixedScheduleInterval.Hours6,
            FixedScheduleInterval.fromStorageKey("unknown", FixedScheduleInterval.Hours6),
        )
    }

    @Test
    fun equivalentDurationsKeepDistinctKeysAndDisplayUnits() {
        assertEquals(24.hours, FixedScheduleInterval.Hours24.duration)
        assertEquals(1.days, FixedScheduleInterval.Days1.duration)
        assertNotEquals(
            FixedScheduleInterval.Hours24.storageKey,
            FixedScheduleInterval.Days1.storageKey,
        )

        assertEquals(7.days, FixedScheduleInterval.Days7.duration)
        assertEquals(7.days, FixedScheduleInterval.Week1.duration)
        assertNotEquals(
            FixedScheduleInterval.Days7.displayUnit,
            FixedScheduleInterval.Week1.displayUnit,
        )
    }
}
