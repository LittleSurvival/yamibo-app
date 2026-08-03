package me.thenano.yamibo.yamibo_app.repository.settings

import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

enum class ReaderChineseConversionOption(val label: String) {
    DEFAULT("default"),
    SIMPLIFIED("simplified"),
    TRADITIONAL("traditional"),
}

enum class ReaderScrollButtonDisplayMode(val label: String) {
    ALWAYS("always"),
    WHEN_USER_SLIDE("when_user_slide"),
    NEVER("never"),
}

enum class ReaderScrollButtonJumpTarget(val label: String) {
    PAGE_EDGE("page_edge"),
    POST_EDGE("post_edge"),
}

enum class ThreadReaderMode(val label: String) {
    SCROLL_CONTINUOUS("scroll_continuous"),
    SINGLE_LTR("single_ltr"),
    SINGLE_RTL("single_rtl"),
    SINGLE_TTB("single_ttb"),
}

class NovelReaderSettingsRepository(store: SettingsStore) : SettingsRegistry(store, prefix = "novelreadersettings") {

    val fontSize by intSetting(
        name = "font_size",
        description = "novel_reader_font_size",
        default = 16,
        min = 10,
        max = 40,
        interval = 1,
    )

    val lineSpacing by floatSetting(
        name = "line_spacing",
        description = "novel_reader_line_spacing",
        default = 1.5f,
        min = 1.0f,
        max = 3.0f,
        interval = 0.05f,
    )

    val readerFontId by stringSetting(
        name = "reader_font_id",
        description = "novel_reader_font_id",
        default = "",
    )

    val contentWidthFraction by floatSetting(
        name = "content_width",
        description = "novel_reader_content_width",
        default = 1.0f,
        min = 0.6f,
        max = 1.0f,
        interval = 0.01f,
    )

    val keepSystemBarsBackground by boolSetting(
        name = "keep_system_bars_background",
        description = "novel_reader_keep_system_bars_background",
        default = true,
    )

    val chineseConversion by enumSetting(
        name = "chinese_conversion",
        description = "novel_reader_chinese_conversion",
        default = ReaderChineseConversionOption.DEFAULT,
    )

    val threadReaderMode by enumSetting(
        name = "thread_reader_mode",
        description = "novel_reader_thread_reader_mode",
        default = ThreadReaderMode.SCROLL_CONTINUOUS,
    )

    val threadTouchZone by enumSetting(
        name = "thread_touch_zone",
        description = "novel_reader_thread_touch_zone",
        default = TouchZoneLayout.L_SHAPE,
    )

    val threadReverseTouchZones by boolSetting(
        name = "thread_reverse_touch_zones",
        default = false,
    )

    val scrollButtonDisplayMode by enumSetting(
        name = "scroll_button_display_mode",
        description = "novel_reader_scroll_button_display_mode",
        default = ReaderScrollButtonDisplayMode.WHEN_USER_SLIDE,
    )

    val scrollButtonDirectionThreshold by intSetting(
        name = "scroll_button_direction_threshold",
        description = "novel_reader_scroll_button_direction_threshold",
        default = 500,
        min = 100,
        max = 2000,
        interval = 50,
    )

    val scrollButtonJumpTarget by enumSetting(
        name = "scroll_button_jump_target",
        description = "novel_reader_scroll_button_jump_target",
        default = ReaderScrollButtonJumpTarget.PAGE_EDGE,
    )

    val showPageProgressHint by boolSetting(
        name = "show_page_progress_hint",
        description = "novel_reader_show_page_progress_hint",
        default = true,
    )
}
