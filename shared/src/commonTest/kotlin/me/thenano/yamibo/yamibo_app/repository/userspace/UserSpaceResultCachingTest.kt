package me.thenano.yamibo.yamibo_app.repository.userspace

import io.github.littlesurvival.core.YamiboResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import me.thenano.yamibo.yamibo_app.core.cache.DiskCache

class UserSpaceResultCachingTest {
    @Test
    fun cacheSuccessStoresSuccessAndReturnsOriginalResult() {
        val cache = FakeDiskCache<String>()
        val result = YamiboResult.Success("profile")

        val cached = result.cacheSuccess(cache, "self")

        assertSame(result, cached)
        assertEquals("profile", cache.get("self"))
    }

    @Test
    fun cacheSuccessDoesNotStoreOrRebuildFailures() {
        val cache = FakeDiskCache<String>()
        val result = YamiboResult.NoPermission("denied")

        val cached = result.cacheSuccess(cache, "self")

        assertSame(result, cached)
        assertEquals(null, cache.get("self"))
    }

    private class FakeDiskCache<T : Any> : DiskCache<T> {
        private val values = linkedMapOf<String, T>()

        override fun set(key: String, value: T) {
            values[key] = value
        }

        override fun get(key: String): T? = values[key]

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun removeByPrefix(prefix: String) {
            values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
        }

        override fun clear() {
            values.clear()
        }
    }
}
