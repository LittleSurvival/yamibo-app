package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class AppSettingsRepositoryTest {
    @Test
    fun autoDownloadSettingsDefaultDisabled() {
        val repository = AppSettingsRepository(AppSettingsMemoryStore())

        assertFalse(repository.favoriteUpdateAutoDownload.getValue())
        assertFalse(repository.downloadedContentRefreshAutoUpdate.getValue())
    }

    @Test
    fun favoriteAddDownloadPromptDefaultsEnabledAndPersists() {
        val store = AppSettingsMemoryStore()
        val repository = AppSettingsRepository(store)

        assertTrue(repository.favoriteAddDownloadPromptEnabled.getValue())
        repository.favoriteAddDownloadPromptEnabled.setValue(false)

        assertFalse(AppSettingsRepository(store).favoriteAddDownloadPromptEnabled.getValue())
        repository.favoriteAddDownloadPromptEnabled.setValue(true)
        assertTrue(AppSettingsRepository(store).favoriteAddDownloadPromptEnabled.getValue())
    }

    @Test
    fun fixedIntervalRefactorPreservesPersistedEnumNamesAndDurations() {
        val store = AppSettingsMemoryStore()
        val repository = AppSettingsRepository(store)

        repository.favoriteUpdateInterval.setValue(FavoriteUpdateInterval.DAYS_3)
        repository.appUpdateLaunchCheckThreshold.setValue(
            AppUpdateLaunchCheckThreshold.HOURS_24,
        )
        repository.backupInterval.setValue(BackupInterval.WEEK_1)

        val recreated = AppSettingsRepository(store)
        assertEquals(FavoriteUpdateInterval.DAYS_3, recreated.favoriteUpdateInterval.getValue())
        assertEquals(
            AppUpdateLaunchCheckThreshold.HOURS_24,
            recreated.appUpdateLaunchCheckThreshold.getValue(),
        )
        assertEquals(BackupInterval.WEEK_1, recreated.backupInterval.getValue())
        assertEquals(FixedScheduleInterval.Days3, FavoriteUpdateInterval.DAYS_3.fixedInterval)
        assertEquals(FixedScheduleInterval.Hours24, AppUpdateLaunchCheckThreshold.HOURS_24.fixedInterval)
        assertEquals(FixedScheduleInterval.Week1, BackupInterval.WEEK_1.fixedInterval)
    }

    @Test
    fun messageNotificationsDefaultEnabledAtThreeHoursAndTwiceDaily() {
        val repository = AppSettingsRepository(AppSettingsMemoryStore())

        assertTrue(repository.messageNotificationEnabled.getValue())
        assertEquals(FixedScheduleInterval.Hours3, repository.messageNotificationInterval.getValue())
        assertEquals(MessageNotificationDailyLimit.TWICE, repository.messageNotificationDailyLimit.getValue())
        assertEquals((1..6).toList(), MessageNotificationDailyLimit.entries.mapNotNull { it.maxPerDay })
        assertEquals(null, MessageNotificationDailyLimit.UNLIMITED.maxPerDay)
        assertEquals(10, MessageNotificationIntervals.size)
        assertTrue(FixedScheduleInterval.Hours3 in MessageNotificationIntervals)
    }
}

private class AppSettingsMemoryStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
    override fun putInt(key: String, value: Int) {
        values[key] = value.toString()
    }

    override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
    override fun putFloat(key: String, value: Float) {
        values[key] = value.toString()
    }

    override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value.toString()
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun hasKey(key: String): Boolean = key in values
}
