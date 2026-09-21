package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class NovelReaderTypographySettingsTest {
    @Test
    fun typographySettingsStartDisabledAndPersistIndependently() {
        val store = TypographyMemoryStore()
        val repository = NovelReaderSettingsRepository(store)

        assertFalse(repository.defaultBold.getValue())
        assertFalse(repository.defaultItalic.getValue())
        assertFalse(repository.firstLineIndent.getValue())
        assertEquals(2f, repository.firstLineIndentChars.getValue())
        assertFalse(store.hasKey(repository.defaultBold.storageKey))
        assertFalse(store.hasKey(repository.defaultItalic.storageKey))
        assertFalse(store.hasKey(repository.firstLineIndent.storageKey))

        repository.defaultBold.setValue(true)
        assertTrue(NovelReaderSettingsRepository(store).defaultBold.getValue())
        assertFalse(NovelReaderSettingsRepository(store).defaultItalic.getValue())

        repository.defaultItalic.setValue(true)
        repository.firstLineIndent.setValue(true)
        repository.firstLineIndentChars.setValue(2.5f)
        val restored = NovelReaderSettingsRepository(store)
        assertTrue(restored.defaultBold.getValue())
        assertTrue(restored.defaultItalic.getValue())
        assertTrue(restored.firstLineIndent.getValue())
        assertEquals(2.5f, restored.firstLineIndentChars.getValue())
        assertTrue(restored.defaultBold.storageKey in restored.exportableSettingItems.map { it.storageKey })
        assertTrue(restored.defaultItalic.storageKey in restored.exportableSettingItems.map { it.storageKey })
        assertTrue(restored.firstLineIndent.storageKey in restored.exportableSettingItems.map { it.storageKey })
        assertTrue(restored.firstLineIndentChars.storageKey in restored.exportableSettingItems.map { it.storageKey })
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
