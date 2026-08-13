package me.thenano.yamibo.yamibo_app.profile.settings

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.access.IBackgroundAccessSetupScreen
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IBackupSettingsScreen
import me.thenano.yamibo.yamibo_app.profile.settings.cloud.IAppSyncSettingsScreen
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsItem
import me.thenano.yamibo.yamibo_app.profile.settings.update.IAppUpdateScreen
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

@Composable
internal fun SettingsScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("設定"),
                titleFontSize = 18,
                onBack = { navigator.pop() },
            )
        },
        containerColor = colors.creamBackground,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsItem(
                icon = YamiboIcons.Views,
                title = i18n("外觀"),
                subtitle = i18n("主題色、配色與整體顯示風格"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("appearance")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Language,
                title = i18n("語言"),
                subtitle = i18n("切換介面語言"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("language")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Book,
                title = i18n("小說閱讀器"),
                subtitle = i18n("字體大小、行距與版面寬度"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("novel_reader")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Book,
                title = i18n("漫畫閱讀器"),
                subtitle = i18n("閱讀模式與觸控區域設定"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("manga_reader")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Explore,
                title = i18n("收藏管理"),
                subtitle = i18n("管理類別、整理排序與收藏互動設定"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("favorite")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Sync,
                title = i18n("通知與背景同步"),
                subtitle = i18n("檢查通知權限、電池最佳化與背景同步所需設定"),
                onClick = { navigator.navigate(IBackgroundAccessSetupScreen()) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Storage,
                title = i18n("儲存空間"),
                subtitle = i18n("緩存空間與啟動時清理設定"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("storage")) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Backup,
                title = i18n("本地資料備份"),
                subtitle = i18n("備份收藏、設定、歷史進度與更新紀錄"),
                onClick = { navigator.navigate(IBackupSettingsScreen()) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.CloudSync,
                title = i18n("雲端同步"),
                subtitle = i18n("同步設定、收藏與閱讀紀錄"),
                onClick = { navigator.navigate(IAppSyncSettingsScreen()) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.Reload,
                title = i18n("App 更新"),
                subtitle = i18n("檢查版本、下載安裝檔與打開安裝流程"),
                onClick = { navigator.navigate(IAppUpdateScreen()) },
            )
            SettingsDivider()

            SettingsItem(
                icon = YamiboIcons.EditOrSign,
                title = i18n("簽到設定"),
                subtitle = i18n("每日簽到模式與補簽偏好"),
                onClick = { navigator.navigate(ISettingsCategoryScreen("sign")) },
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    val colors = YamiboTheme.colors
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = colors.brownLight.copy(alpha = 0.15f),
    )
}
