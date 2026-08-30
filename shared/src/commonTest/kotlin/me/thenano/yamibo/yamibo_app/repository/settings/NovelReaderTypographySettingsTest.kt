package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NovelReaderTypographySettingsTest {
    @Test
    fun defaultTextStylesStartDisabledAndPersistIndependently() {
        val store = TypographyMemoryStore()
        val repository = NovelReaderSettingsRepository(store)

        assertFalse(repository.defaultBold.getValue())
        assertFalse(repository.defaultItalic.getValue())
        assertFalse(store.hasKey(repository.defaultBold.storageKey))
        assertFalse(store.hasKey(repository.defaultItalic.storageKey))

        repository.defaultBold.setValue(true)
        assertTrue(NovelReaderSettingsRepository(store).defaultBold.getValue())
        assertFalse(NovelReaderSettingsRepository(store).defaultItalic.getValue())

        repository.defaultItalic.setValue(true)
        val restored = NovelReaderSettingsRepository(store)
        assertTrue(restored.defaultBold.getValue())
        assertTrue(restored.defaultItalic.getValue())
        assertTrue(restored.defaultBold.storageKey in restored.exportableSettingItems.map { it.storageKey })
        assertTrue(restored.defaultItalic.storageKey in restored.exportableSettingItems.map { it.storageKey })
    }
}

private class TypographyMemoryStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
    override fun putInt(key: String, value: Int) { values[key] = value.toString() }
    override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
    override fun putFloat(key: String, value: Float) { values[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
    override fun remove(key: String) { values.remove(key) }
    override fun hasKey(key: String): Boolean = key in values
}
