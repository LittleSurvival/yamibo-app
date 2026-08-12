package me.thenano.yamibo.yamibo_app.util.time

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeUtilsTest {
    @Test
    fun formatDateTimeCarriesUtcPlus8AcrossMidnight() {
        val july31At1906Utc = 1_785_524_760_000L

        assertEquals("2026/08/01 03:06", formatDateTime(july31At1906Utc))
        assertEquals("2026/8/1", formatDate(july31At1906Utc))
    }
}
