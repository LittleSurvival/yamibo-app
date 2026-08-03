package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

/** Reading mode configuration for manga reader. */
enum class ReadingMode(val label: String) {
    SINGLE_LTR("single_ltr"),
    SINGLE_RTL("single_rtl"),
    SINGLE_TTB("single_ttb"),
    SCROLL_CONTINUOUS("scroll_continuous"),
    SCROLL_GAP("scroll_gap")
}

/** Touch zone layout options for manga reader navigation. */
enum class TouchZoneLayout(val label: String) {
    L_SHAPE("l_shape"),
    KINDLE("kindle"),
    EDGE("edge"),
    LEFT_RIGHT("left_right"),
    DISABLED("disabled")
}

class MangaReaderSettingsRepository(store: SettingsStore) : SettingsRegistry(store, prefix = "mangareadersettings") {

    val readingMode by enumSetting(
        name = "reading_mode",
        default = ReadingMode.SINGLE_LTR
    )

    val touchZone by enumSetting(
        name = "touch_zone",
        default = TouchZoneLayout.L_SHAPE
    )

    val reverseTouchZones by boolSetting(
        name = "reverse_touch_zones",
        default = false,
    )
}

