package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

enum class AppThemeMode(val label: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

enum class AppThemeScheme(val label: String) {
    DEFAULT("default"),
    DEFAULT_DARK("default_dark"),
    CLASSIC_BLACK("classic_black"),
    CLASSIC_WHITE("classic_white"),
    CATPPUCCIN("Catppuccin"),
    GREEN_APPLE("Green Apple"),
    LAVENDER("Lavender"),
    MIDNIGHT_DUSK("Midnight Dusk"),
    NORD("Nord"),
    STRAWBERRY_DAIQUIRI("Strawberry Daiquiri"),
    TAKO("Tako"),
    TEAL_TURQUOISE("Teal & Turquoise"),
    TIDAL_WAVE("Tidal Wave"),
    YIN_YANG("Yin & Yang"),
    YOTSUBA("Yotsuba"),
    MONOCHROME("Monochrome");

    fun toScheme(): YamiboColorScheme = when (this) {
        DEFAULT -> YamiboColorScheme.Default
        DEFAULT_DARK -> YamiboColorScheme.DefaultDark
        CLASSIC_BLACK -> YamiboColorScheme.ClassicBlack
        CLASSIC_WHITE -> YamiboColorScheme.ClassicWhite
        CATPPUCCIN -> YamiboColorScheme.Catppuccin
        GREEN_APPLE -> YamiboColorScheme.GreenApple
        LAVENDER -> YamiboColorScheme.Lavender
        MIDNIGHT_DUSK -> YamiboColorScheme.MidnightDusk
        NORD -> YamiboColorScheme.Nord
        STRAWBERRY_DAIQUIRI -> YamiboColorScheme.StrawberryDaiquiri
        TAKO -> YamiboColorScheme.Tako
        TEAL_TURQUOISE -> YamiboColorScheme.TealTurquoise
        TIDAL_WAVE -> YamiboColorScheme.TidalWave
        YIN_YANG -> YamiboColorScheme.YinYang
        YOTSUBA -> YamiboColorScheme.Yotsuba
        MONOCHROME -> YamiboColorScheme.Monochrome
    }
}

enum class FavoriteGridMode(val label: String) {
    FIXED_GRID("fixed_grid"),
    STAGGERED("staggered"),
    ROW_CARD("row_card"),
    ROW_CARD_TEXT("row_card_text"),
}

enum class FavoriteSortMode(val label: String) {
    DEFAULT("default"),
    UPDATED_AT("updated_at"),
    FAVORITED_ORDER("favorited_order"),
    NAME("name"),
    FORUM_NAME("forum_name"),
    LAST_READ("last_read"),
}

enum class FavoriteUpdateInterval(
    val label: String,
    val fixedInterval: FixedScheduleInterval?,
    val smart: Boolean = false,
) {
    MANUAL("manual", null),
    HOURS_6("6h", FixedScheduleInterval.Hours6),
    HOURS_12("12h", FixedScheduleInterval.Hours12),
    HOURS_24("24h", FixedScheduleInterval.Hours24),
    DAYS_3("3d", FixedScheduleInterval.Days3),
    DAYS_7("7d", FixedScheduleInterval.Days7),
    SMART("smart_todo", null, smart = true),
    ;

    val hours: Long?
        get() = fixedInterval?.duration?.inWholeHours
}

enum class AppUpdateLaunchCheckThreshold(
    val label: String,
    val fixedInterval: FixedScheduleInterval?,
) {
    MANUAL("manual", null),
    HOURS_6("6h", FixedScheduleInterval.Hours6),
    HOURS_12("12h", FixedScheduleInterval.Hours12),
    HOURS_24("24h", FixedScheduleInterval.Hours24),
    DAYS_3("3d", FixedScheduleInterval.Days3),
    DAYS_7("7d", FixedScheduleInterval.Days7),
    ;

    val hours: Long?
        get() = fixedInterval?.duration?.inWholeHours
}

enum class BackupInterval(
    val label: String,
    val fixedInterval: FixedScheduleInterval?,
) {
    HOURS_6("6h", FixedScheduleInterval.Hours6),
    HOURS_12("12h", FixedScheduleInterval.Hours12),
    DAYS_1("1d", FixedScheduleInterval.Days1),
    DAYS_3("3d", FixedScheduleInterval.Days3),
    WEEK_1("1week", FixedScheduleInterval.Week1),
    NEVER("never", null),
    ;

    val hours: Long?
        get() = fixedInterval?.duration?.inWholeHours
}

enum class SignInMode(val label: String) {
    SEMI_AUTOMATIC("semi_automatic"),
    FULL_MANUAL("full_manual"),
}

enum class SignReminderFrequency(val label: String, val timesPerDay: Int) {
    OFF("off", 0),
    ONCE_A_DAY("1x_day", 1),
    TWICE_A_DAY("2x_day", 2),
    THRICE_A_DAY("3x_day", 3),
    FOUR_TIMES_A_DAY("4x_day", 4),
    FIVE_TIMES_A_DAY("5x_day", 5),
    SIX_TIMES_A_DAY("6x_day", 6),
}

enum class AppLanguage(val label: String, val languageTag: String) {
    TRADITIONAL_CHINESE("zh-TW", "zh-TW"),
    SIMPLIFIED_CHINESE("zh-CN", "zh-CN"),
    ENGLISH("English", "en"),
}

class AppSettingsRepository(store: SettingsStore) : SettingsRegistry(store, prefix = "appsettings") {

    /** 顏色主題 */
    val themeMode by enumSetting(
        name = "theme_mode",
        default = AppThemeMode.SYSTEM,
    )

    /** 顏色主題 */
    val themeScheme by enumSetting(
        name = "theme_scheme",
        default = AppThemeScheme.DEFAULT,
    )

    /** 語言 */
    val language by enumSetting(
        name = "language",
        default = AppLanguage.TRADITIONAL_CHINESE,
    )

    /** 漫畫模式 */
    val isMangaMode by boolSetting(
        name = "manga_mode",
        default = false,
    )

    /** App 啟動時清除緩存 */
    val clearCacheOnAppLaunch by boolSetting(
        name = "clear_cache_on_app_launch",
        default = false,
    )

    /** 顯示首頁輪播圖 */
    val showHomeSwiperImages by boolSetting(
        name = "show_home_swiper_images",
        default = true,
    )

    /** App 字體 */
    val appFontId by stringSetting(
        name = "app_font_id",
        default = "",
    )

    /** 略過刪除確認 */
    val skipFavoriteRemovalConfirm by boolSetting(
        name = "skip_favorite_removal_confirm",
        default = false,
    )

    /** 新增收藏時詢問同步 */
    val favoriteAddSyncPromptEnabled by boolSetting(
        name = "favorite_add_sync_prompt_enabled",
        default = true,
    )

    /** 新增收藏預設動作 */
    val favoriteAddSyncDefault by boolSetting(
        name = "favorite_add_sync_default",
        default = true,
    )

    /** 完全移除收藏時詢問同步刪除 */
    val favoriteRemoveSyncPromptEnabled by boolSetting(
        name = "favorite_remove_sync_prompt_enabled",
        default = true,
    )

    /** 完全移除收藏預設動作 */
    val favoriteRemoveSyncDefault by boolSetting(
        name = "favorite_remove_sync_default",
        default = false,
    )

    /** 排列方式 */
    val favoriteGridMode by enumSetting(
        name = "favorite_grid_mode",
        default = FavoriteGridMode.FIXED_GRID,
    )

    /** 排序方式 */
    val favoriteSortMode by enumSetting(
        name = "favorite_sort_mode",
        default = FavoriteSortMode.DEFAULT,
    )

    /** 排序方式 */
    val favoriteSortDescending by boolSetting(
        name = "favorite_sort_descending",
        default = true,
    )

    /** 收藏分類 */
    val favoriteLastCategoryId by intSetting(
        name = "favorite_last_category_id",
        default = 0,
    )

    /** 收藏更新檢查週期 */
    val favoriteUpdateInterval by enumSetting(
        name = "favorite_update_interval",
        default = FavoriteUpdateInterval.MANUAL,
    )

    /** 收藏更新檢查週期 */
    val favoriteUpdateHiddenRunId by stringSetting(
        name = "favorite_update_hidden_run_id",
        default = "",
    )

    /** 收藏更新檢查到離線內容更新時自動刷新 */
    val favoriteUpdateAutoDownload by boolSetting(
        name = "favorite_update_auto_download",
        default = false,
    )

    /** 重新整理離線內容後自動更新 */
    val downloadedContentRefreshAutoUpdate by boolSetting(
        name = "downloaded_content_refresh_auto_update",
        default = false,
    )

    /** 啟動檢查更新間隔 */
    val appUpdateLastCheckAt by stringSetting(
        name = "app_update_last_check_at",
        default = "",
    )

    /** 忽略此版本 */
    val appUpdateIgnoredVersionCode by intSetting(
        name = "app_update_ignored_version_code",
        default = 0,
    )

    /** App 更新 */
    val appUpdatePreferredSourceIndex by intSetting(
        name = "app_update_preferred_source_index",
        default = 0,
    )

    /** 啟動檢查更新間隔 */
    val appUpdateLaunchCheckThreshold by enumSetting(
        name = "app_update_launch_check_threshold",
        default = AppUpdateLaunchCheckThreshold.HOURS_6,
    )

    /** 定期自動備份 */
    val backupInterval by enumSetting(
        name = "backup_interval",
        default = BackupInterval.HOURS_12,
    )

    /** 最多自動備份檔案數量 */
    val backupMaxAutoFiles by intSetting(
        name = "backup_max_auto_files",
        default = 3,
        min = 1,
        max = 10,
    )

    /** 備份資料夾 */
    val backupFolderUri by stringSetting(
        name = "backup_folder_uri",
        default = "",
    )

    /** 定期自動備份 */
    val backupLastAutoBackupAt by stringSetting(
        name = "backup_last_auto_backup_at",
        default = "",
    )

    /** 簽到行為 */
    val signInMode by enumSetting(
        name = "sign_in_mode",
        default = SignInMode.SEMI_AUTOMATIC,
    )

    /** 啟動時簽到提醒 */
    val signInLaunchReminderEnabled by boolSetting(
        name = "sign_in_launch_reminder_enabled",
        default = true,
    )

    /** 啟動時簽到提醒 */
    val signInLaunchReminderDismissedDate by stringSetting(
        name = "sign_in_launch_reminder_dismissed_date",
        default = "",
    )

    /** 關閉後今日不再提醒 (default true) */
    val signInLaunchReminderDismissToday by boolSetting(
        name = "sign_in_launch_reminder_dismiss_today",
        default = true,
    )

    /** 自動進行補簽 */
    val signInAllowRepair by boolSetting(
        name = "sign_in_allow_repair",
        default = false,
    )

    /** 簽到提醒頻率 */
    val signInReminderFrequency by enumSetting(
        name = "sign_in_reminder_frequency",
        default = SignReminderFrequency.ONCE_A_DAY,
    )

    /** 簽到提醒直接進入網頁 */
    val signInDirectWebView by boolSetting(
        name = "sign_in_direct_web_view",
        default = true,
    )

    /** 簽到 */
    val signPageHtmlCache by stringSetting(
        name = "sign_page_html_cache",
        default = "",
    )

    /** 簽到 */
    val signPageHtmlCacheUpdatedAt by stringSetting(
        name = "sign_page_html_cache_updated_at",
        default = "",
    )

    @Suppress("unused")
    companion object {
        val themeModeOptions = AppThemeMode.entries.map { it to it.label }
        val themeSchemeOptions = AppThemeScheme.entries.map { it to it.label }
        val favoriteGridModeOptions = FavoriteGridMode.entries.map { it to it.label }
        val favoriteSortModeOptions = FavoriteSortMode.entries.map { it to it.label }
        val favoriteUpdateIntervalOptions = FavoriteUpdateInterval.entries.map { it to it.label }
        val appUpdateLaunchCheckThresholdOptions = AppUpdateLaunchCheckThreshold.entries.map { it to it.label }
        val backupIntervalOptions = BackupInterval.entries.map { it to it.label }
        val signInModeOptions = SignInMode.entries.map { it to it.label }
        val signInReminderFrequencyOptions = SignReminderFrequency.entries.map { it to it.label }
        val languageOptions = AppLanguage.entries.map { it to it.label }
    }
}
