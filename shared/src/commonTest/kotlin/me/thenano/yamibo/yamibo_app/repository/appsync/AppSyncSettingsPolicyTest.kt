package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncSetting
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncSettingType
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppThemeMode
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncSettingsPolicyTest {
    @Test
    fun defaultDenyExcludesCachesPathsAndUnknownKeys() {
        val settings = AppSettingsRepository(MemorySettingsStore())
        settings.themeMode.setValue(AppThemeMode.DARK)
        settings.signPageHtmlCache.setValue("<html>secret-ish cache</html>")
        settings.backupFolderUri.setValue("content://device-only")
        val policy = AppSyncSettingsPolicy(listOf(settings))

        val exported = policy.export()
        assertTrue(exported.any { it.key == "appsettings.thememode" && it.value == "DARK" })
        assertFalse(exported.any { it.key == "appsettings.signpagehtmlcache" })
        assertFalse(exported.any { it.key == "appsettings.backupfolderuri" })

        val prepared = policy.prepare(
            listOf(
                AppSyncSetting("unknown.key", AppSyncSettingType.String, "ignored"),
                AppSyncSetting("appsettings.thememode", AppSyncSettingType.Enum, "LIGHT"),
                AppSyncSetting("appsettings.backupfolderuri", AppSyncSettingType.String, "blocked"),
            ),
            overwrite = false,
        )
        assertEquals(2, prepared.skipped)
        assertEquals(1, prepared.settings.size)
        assertTrue(policy.apply(prepared.settings.single()))
        assertEquals(AppThemeMode.LIGHT, settings.themeMode.getValue())
    }
}

internal open class MemorySettingsStore : SettingsStore {
    protected val values = mutableMapOf<String, String>()

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
