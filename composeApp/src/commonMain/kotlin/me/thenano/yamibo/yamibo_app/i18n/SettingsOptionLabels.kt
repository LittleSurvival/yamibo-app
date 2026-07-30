package me.thenano.yamibo.yamibo_app.i18n

import me.thenano.yamibo.yamibo_app.repository.settings.*

fun AppThemeMode.localizedLabel(): String = when (this) {
    AppThemeMode.SYSTEM -> i18n("跟隨系統")
    AppThemeMode.LIGHT -> i18n("淺色模式")
    AppThemeMode.DARK -> i18n("深色模式")
}

fun AppThemeScheme.localizedLabel(): String = when (this) {
    AppThemeScheme.DEFAULT -> i18n("預設")
    AppThemeScheme.DEFAULT_DARK -> i18n("預設（深色）")
    AppThemeScheme.CLASSIC_BLACK -> i18n("傳統黑")
    AppThemeScheme.CLASSIC_WHITE -> i18n("傳統白")
    else -> label
}

fun FavoriteGridMode.localizedLabel(): String = when (this) {
    FavoriteGridMode.FIXED_GRID -> i18n("固定網格")
    FavoriteGridMode.STAGGERED -> i18n("瀑布貼齊")
    FavoriteGridMode.ROW_CARD -> i18n("橫排卡片")
    FavoriteGridMode.ROW_CARD_TEXT -> i18n("橫排卡片(無封面)")
}

fun FavoriteSortMode.localizedLabel(): String = when (this) {
    FavoriteSortMode.DEFAULT -> i18n("默認")
    FavoriteSortMode.UPDATED_AT -> i18n("更新時間")
    FavoriteSortMode.FAVORITED_ORDER -> i18n("收藏順序")
    FavoriteSortMode.NAME -> i18n("名稱")
    FavoriteSortMode.FORUM_NAME -> i18n("版區")
    FavoriteSortMode.LAST_READ -> i18n("最後一次閱讀")
}

fun FavoriteUpdateInterval.localizedLabel(): String = when (this) {
    FavoriteUpdateInterval.MANUAL -> i18n("手動刷新")
    FavoriteUpdateInterval.SMART -> i18n("智能更新（TODO）")
    else -> requireNotNull(fixedInterval).localizedLabel()
}

fun SignInMode.localizedLabel(): String = when (this) {
    SignInMode.SEMI_AUTOMATIC -> i18n("半自動簽到")
    SignInMode.FULL_MANUAL -> i18n("全手動簽到")
}

fun ReadingMode.localizedLabel(): String = when (this) {
    ReadingMode.SINGLE_LTR -> i18n("單頁(左至右)")
    ReadingMode.SINGLE_RTL -> i18n("單頁(右至左)")
    ReadingMode.SINGLE_TTB -> i18n("單頁(上至下)")
    ReadingMode.SCROLL_CONTINUOUS -> i18n("捲動(連續)")
    ReadingMode.SCROLL_GAP -> i18n("捲動(留空)")
}

fun ThreadReaderMode.localizedLabel(): String = when (this) {
        ThreadReaderMode.SCROLL_CONTINUOUS -> i18n("捲動(連續)")
    ThreadReaderMode.SINGLE_LTR -> i18n("單頁(左至右)")
    ThreadReaderMode.SINGLE_RTL -> i18n("單頁(右至左)")
    ThreadReaderMode.SINGLE_TTB -> i18n("單頁(上至下)")
}

fun TouchZoneLayout.localizedLabel(): String = when (this) {
    TouchZoneLayout.L_SHAPE -> i18n("L式")
    TouchZoneLayout.KINDLE -> i18n("Kindle式")
    TouchZoneLayout.EDGE -> i18n("邊緣式")
    TouchZoneLayout.LEFT_RIGHT -> i18n("左右式")
    TouchZoneLayout.DISABLED -> i18n("停用")
}

fun ReaderChineseConversionOption.localizedLabel(): String = when (this) {
    ReaderChineseConversionOption.DEFAULT -> i18n("默認")
    ReaderChineseConversionOption.SIMPLIFIED -> i18n("簡體")
    ReaderChineseConversionOption.TRADITIONAL -> i18n("繁體")
}

fun SignReminderFrequency.localizedLabel(): String = when (this) {
    SignReminderFrequency.OFF -> i18n("關閉")
    SignReminderFrequency.ONCE_A_DAY -> i18n("每天 1 次")
    SignReminderFrequency.TWICE_A_DAY -> i18n("每天 2 次")
    SignReminderFrequency.THRICE_A_DAY -> i18n("每天 3 次")
    SignReminderFrequency.FOUR_TIMES_A_DAY -> i18n("每天 4 次")
    SignReminderFrequency.FIVE_TIMES_A_DAY -> i18n("每天 5 次")
    SignReminderFrequency.SIX_TIMES_A_DAY -> i18n("每天 6 次")
}

