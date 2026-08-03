package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderTouchZoneSettingsTest {
    @Test
    fun existingMangaKeyAndValueRemainMangaOwned() {
        val store = TouchZoneMemoryStore().apply {
            putString("mangareadersettings.touchzone", TouchZoneLayout.EDGE.name)
        }

        val manga = MangaReaderSettingsRepository(store)
        val thread = NovelReaderSettingsRepository(store)

        assertEquals("mangareadersettings.touchzone", manga.touchZone.storageKey)
        assertEquals(TouchZoneLayout.EDGE, manga.touchZone.getValue())
        assertEquals(TouchZoneLayout.L_SHAPE, thread.threadTouchZone.getValue())
        assertFalse(store.hasKey(thread.threadTouchZone.storageKey))
    }

    @Test
    fun readerTouchZoneValuesPersistIndependently() {
        val store = TouchZoneMemoryStore()
        val manga = MangaReaderSettingsRepository(store)
        val thread = NovelReaderSettingsRepository(store)

        manga.touchZone.setValue(TouchZoneLayout.KINDLE)
        thread.threadTouchZone.setValue(TouchZoneLayout.LEFT_RIGHT)

        val restoredManga = MangaReaderSettingsRepository(store)
        val restoredThread = NovelReaderSettingsRepository(store)
        assertEquals(TouchZoneLayout.KINDLE, restoredManga.touchZone.getValue())
        assertEquals(TouchZoneLayout.LEFT_RIGHT, restoredThread.threadTouchZone.getValue())
        assertTrue(restoredManga.touchZone.storageKey != restoredThread.threadTouchZone.storageKey)
    }

    @Test
    fun readerReversalDefaultsOffAndPersistsIndependently() {
        val store = TouchZoneMemoryStore()
        val manga = MangaReaderSettingsRepository(store)
        val thread = NovelReaderSettingsRepository(store)

        assertFalse(manga.reverseTouchZones.getValue())
        assertFalse(thread.threadReverseTouchZones.getValue())
        assertFalse(store.hasKey(manga.reverseTouchZones.storageKey))
        assertFalse(store.hasKey(thread.threadReverseTouchZones.storageKey))
        manga.reverseTouchZones.setValue(true)
        thread.threadReverseTouchZones.setValue(false)

        assertTrue(MangaReaderSettingsRepository(store).reverseTouchZones.getValue())
        assertFalse(NovelReaderSettingsRepository(store).threadReverseTouchZones.getValue())
        assertTrue(manga.reverseTouchZones.storageKey in manga.exportableSettingItems.map { it.storageKey })
        assertFalse(manga.reverseTouchZones.storageKey in thread.exportableSettingItems.map { it.storageKey })
        assertTrue(thread.threadReverseTouchZones.storageKey in thread.exportableSettingItems.map { it.storageKey })
        assertFalse(thread.threadReverseTouchZones.storageKey in manga.exportableSettingItems.map { it.storageKey })
        assertTrue(manga.reverseTouchZones.storageKey != thread.threadReverseTouchZones.storageKey)
    }

    @Test
    fun divergentReversalValuesSurviveIndependentWritesAndRestore() {
        val store = TouchZoneMemoryStore()
        val manga = MangaReaderSettingsRepository(store)
        val thread = NovelReaderSettingsRepository(store)

        manga.reverseTouchZones.setValue(true)
        thread.threadReverseTouchZones.setValue(false)
        assertTrue(MangaReaderSettingsRepository(store).reverseTouchZones.getValue())
        assertFalse(NovelReaderSettingsRepository(store).threadReverseTouchZones.getValue())

        thread.threadReverseTouchZones.setValue(true)
        manga.reverseTouchZones.setValue(false)
        assertFalse(MangaReaderSettingsRepository(store).reverseTouchZones.getValue())
        assertTrue(NovelReaderSettingsRepository(store).threadReverseTouchZones.getValue())
    }

    @Test
    fun backupRegistriesExposeIndependentKeysWithoutLegacyDerivation() {
        val store = TouchZoneMemoryStore().apply {
            putString("mangareadersettings.touchzone", TouchZoneLayout.EDGE.name)
        }
        val manga = MangaReaderSettingsRepository(store)
        val thread = NovelReaderSettingsRepository(store)

        assertEquals(TouchZoneLayout.EDGE, manga.touchZone.getValue())
        assertEquals(TouchZoneLayout.L_SHAPE, thread.threadTouchZone.getValue())
        assertEquals(
            setOf(manga.touchZone.storageKey, thread.threadTouchZone.storageKey),
            listOf(manga, thread)
                .flatMap { it.exportableSettingItems }
                .map { it.storageKey }
                .filter { it.endsWith("touchzone") }
                .toSet(),
        )
        assertEquals(
            setOf(manga.reverseTouchZones.storageKey, thread.threadReverseTouchZones.storageKey),
            listOf(manga, thread)
                .flatMap { it.exportableSettingItems }
                .map { it.storageKey }
                .filter { it.endsWith("reversetouchzones") }
                .toSet(),
        )
    }
}

private class TouchZoneMemoryStore : SettingsStore {
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
