package me.thenano.yamibo.yamibo_app.store.sign

import kotlin.test.Test
import kotlin.test.assertNotEquals

class SignStatusStorageKeyTest {
    @Test
    fun statusKeysAreIsolatedByAccountAndLocalDate() {
        val current = signStatusStorageKey(uid = "100", localDateKey = "2026-08-03")

        assertNotEquals(current, signStatusStorageKey(uid = "200", localDateKey = "2026-08-03"))
        assertNotEquals(current, signStatusStorageKey(uid = "100", localDateKey = "2026-08-02"))
    }
}
