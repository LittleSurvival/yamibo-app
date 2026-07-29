package me.thenano.yamibo.yamibo_app.store.appsync

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class SettingsAppSyncBlogConfigStoreTest {
    @Test
    fun preservesTypeSafeBlogIdentity() {
        val store = SettingsAppSyncBlogConfigStore(MemorySettingsStore())
        val expected = StoredAppSyncBlogConfig(
            blogName = "sync config",
            blogId = BlogId(123),
            classId = BlogClassId(45),
            cloudContentUpdatedAtEpochMillis = 9_876_543_210L,
            validatedAtEpochMillis = 9_876_543_211L,
            schemaVersion = 1,
            fingerprint = "abc",
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    @Test
    fun clearRemovesStoredIdentity() {
        val store = SettingsAppSyncBlogConfigStore(MemorySettingsStore())
        store.save(StoredAppSyncBlogConfig("sync config", BlogId(123)))

        store.clear()

        assertNull(store.load())
    }
}

private class MemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, Any>()

    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }

    override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun hasKey(key: String) = key in values
}
