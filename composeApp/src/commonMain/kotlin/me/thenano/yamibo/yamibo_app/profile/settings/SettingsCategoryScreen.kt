package me.thenano.yamibo.yamibo_app.profile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.storage.YamiboStorageUsageOverview
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.core.cache.CacheStorageBreakdown
import me.thenano.yamibo.yamibo_app.core.cache.CacheStorageUsage
import me.thenano.yamibo.yamibo_app.favorite.IFavoriteCategoryManageScreen
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncStatusCard
import me.thenano.yamibo.yamibo_app.favorite.sync.IFavoriteSyncProgressScreen
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.download.IDownloadQueueScreen
import me.thenano.yamibo.yamibo_app.profile.settings.access.IBackgroundAccessSetupScreen
import me.thenano.yamibo.yamibo_app.profile.settings.bound.*
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsChipRow
import me.thenano.yamibo.yamibo_app.profile.settings.components.ThemeSelectorContent
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncState
import me.thenano.yamibo.yamibo_app.repository.download.DownloadedContentSummary
import me.thenano.yamibo.yamibo_app.repository.settings.*
import me.thenano.yamibo.yamibo_app.util.formatStorageSize
import me.thenano.yamibo.yamibo_app.util.state

@Composable
internal fun SettingsCategoryScreen(category: String) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current

    val title = when (category) {
        "appearance" -> i18n("外觀")
        "language" -> i18n("語言")
        "novel_reader" -> i18n("小說閱讀器")
        "manga_reader" -> i18n("漫畫閱讀器")
        "favorite" -> i18n("收藏管理")
        "storage" -> i18n("儲存空間")
        "sign" -> i18n("簽到設定")
        else -> i18n("設定")
    }

    val feedbackController = LocalAppFeedbackController.current

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = title,
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (category) {
                "appearance" -> AppearanceContent(feedbackController)
                "language" -> LanguageContent()
                "novel_reader" -> NovelReaderContent()
                "manga_reader" -> MangaReaderContent()
                "favorite" -> FavoriteSettingsContent(feedbackController)
                "storage" -> StorageContent(feedbackController)
                "sign" -> SignSettingsContent()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = YamiboTheme.colors
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textDark.copy(alpha = 0.5f),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun AppearanceContent(feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController) {
    val appSettingsRepo = LocalAppSettingsRepository.current
    val themeMode = appSettingsRepo.themeMode.state()
    val themeScheme = appSettingsRepo.themeScheme.state()
    val showHomeSwiperImages = appSettingsRepo.showHomeSwiperImages.state()

    ThemeSelectorContent(
        currentMode = themeMode,
        currentScheme = themeScheme,
        onModeChange = { appSettingsRepo.themeMode.setValue(it) },
        onSchemeChange = { appSettingsRepo.themeScheme.setValue(it) },
    )

    Spacer(Modifier.height(24.dp))
    SectionLabel(i18n("首頁"))
    SettingsToggleRow(
        title = i18n("顯示首頁輪播圖"),
        subtitle = i18n("在首頁版塊列表上方顯示百合會活動與推薦橫幅。"),
        checked = showHomeSwiperImages,
        onCheckedChange = { appSettingsRepo.showHomeSwiperImages.setValue(it) },
    )

    Spacer(Modifier.height(24.dp))
    SectionLabel(i18n("字體"))
    FontManagementSetting(feedbackController)
    Spacer(Modifier.height(18.dp))
    AppFontSelectorSetting()
    Spacer(Modifier.height(18.dp))
    ReaderFontSelectorSetting()
}

@Composable
private fun LanguageContent() {
    val appSettingsRepo = LocalAppSettingsRepository.current
    val language = appSettingsRepo.language.state()

    SectionLabel(i18n("介面語言"))
    Text(
        text = i18n("語言"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = YamiboTheme.colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = listOf(
            AppLanguage.TRADITIONAL_CHINESE to i18n("繁體中文"),
            AppLanguage.SIMPLIFIED_CHINESE to i18n("简体中文"),
            AppLanguage.ENGLISH to i18n("English"),
        ),
        selectedValue = language,
        onSelect = { appSettingsRepo.language.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun NovelReaderContent() {
    SectionLabel(i18n("閱讀模式"))
    ThreadReaderModeSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("觸控分區"))
    ThreadReaderTouchZoneSetting()
    ThreadReaderReverseTouchZonesSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("預覽"))
    NovelReaderPreviewSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("字體大小"))
    NovelFontSizeSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("行距"))
    NovelLineSpacingSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("字體"))
    ReaderFontSelectorSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("內容寬度"))
    NovelContentWidthSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("系統列"))
    NovelSystemBarsBackgroundSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("簡繁轉換"))
    NovelChineseConversionSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("浮動跳轉按鈕"))
    NovelScrollButtonDisplayModeSetting()
    Spacer(Modifier.height(16.dp))
    NovelScrollButtonThresholdSetting()
    Spacer(Modifier.height(16.dp))
    NovelScrollButtonJumpTargetSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("閱讀進度"))
    NovelPageProgressHintSetting()
}

@Composable
private fun MangaReaderContent() {
    SectionLabel(i18n("閱讀模式"))
    MangaReadingModeSetting()
    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("觸控分區"))
    MangaTouchZoneSetting()
    MangaReverseTouchZonesSetting()
}

@Composable
private fun FavoriteSettingsContent(feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val favoriteSyncRunner = LocalFavoriteSyncRunner.current
    val favoriteUpdateRunner = LocalFavoriteUpdateRunner.current
    val skipConfirm = appSettingsRepository.skipFavoriteRemovalConfirm.state()
    val addSyncPromptEnabled = appSettingsRepository.favoriteAddSyncPromptEnabled.state()
    val addSyncDefault = appSettingsRepository.favoriteAddSyncDefault.state()
    val removeSyncPromptEnabled = appSettingsRepository.favoriteRemoveSyncPromptEnabled.state()
    val removeSyncDefault = appSettingsRepository.favoriteRemoveSyncDefault.state()
    val gridMode = appSettingsRepository.favoriteGridMode.state()
    val sortMode = appSettingsRepository.favoriteSortMode.state()
    val sortDescending = appSettingsRepository.favoriteSortDescending.state()
    val updateInterval = appSettingsRepository.favoriteUpdateInterval.state()
    val favoriteUpdateAutoDownload = appSettingsRepository.favoriteUpdateAutoDownload.state()
    val downloadedContentRefreshAutoUpdate = appSettingsRepository.downloadedContentRefreshAutoUpdate.state()
    val syncState by favoriteSyncRunner.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SectionLabel(i18n("收藏管理"))
    SettingsActionRow(
        title = i18n("管理收藏類別"),
        subtitle = i18n("新增、編輯、刪除或調整你的收藏類別"),
        onClick = { navigator.navigate(IFavoriteCategoryManageScreen()) },
    )

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("收藏顯示"))
    Text(
        text = i18n("排列方式"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = FavoriteGridMode.entries.map { it to it.localizedLabel() },
        selectedValue = gridMode,
        onSelect = { appSettingsRepository.favoriteGridMode.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(18.dp))
    Text(
        text = i18n("排序方式"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = FavoriteSortMode.entries.map { mode ->
            val label = mode.localizedLabel()
            mode to if (mode == sortMode) "$label${if (sortDescending) " ↓" else " ↑"}" else label
        },
        selectedValue = sortMode,
        onSelect = {
            if (it == sortMode) {
                appSettingsRepository.favoriteSortDescending.setValue(!sortDescending)
            } else {
                appSettingsRepository.favoriteSortMode.setValue(it)
                appSettingsRepository.favoriteSortDescending.setValue(
                    it != FavoriteSortMode.NAME && it != FavoriteSortMode.FORUM_NAME
                )
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("收藏刪除"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { appSettingsRepository.skipFavoriteRemovalConfirm.setValue(!skipConfirm) }
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = i18n("略過刪除確認"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textDark,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = i18n("開啟後，刪除收藏時不再跳出確認視窗。"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = skipConfirm,
            onCheckedChange = { appSettingsRepository.skipFavoriteRemovalConfirm.setValue(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("收藏同步偏好"))
    SettingsActionRow(
        title = i18n("通知與背景同步設定"),
        subtitle = i18n("檢查通知權限、電池最佳化與背景同步所需的系統設定。"),
        onClick = { navigator.navigate(IBackgroundAccessSetupScreen()) },
    )

    Spacer(Modifier.height(18.dp))
    Text(
        text = i18n("收藏更新檢查週期"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = FavoriteUpdateInterval.entries.map { it to it.localizedLabel() },
        selectedValue = updateInterval,
        onSelect = { interval ->
            appSettingsRepository.favoriteUpdateInterval.setValue(interval)
            coroutineScope.launch {
                favoriteUpdateRunner.schedulePeriodicUpdate(interval)
                if (interval == FavoriteUpdateInterval.SMART) {
                    feedbackController.post(i18n("智能更新策略尚未接入，暫停週期背景檢查。"))
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(18.dp))
    SettingsToggleRow(
        title = i18n("更新檢查後自動刷新離線頁"),
        subtitle = i18n("開啟後，只會自動刷新已確認可更新的離線頁面。"),
        checked = favoriteUpdateAutoDownload,
        onCheckedChange = { appSettingsRepository.favoriteUpdateAutoDownload.setValue(it) },
    )

    Spacer(Modifier.height(10.dp))
    SettingsToggleRow(
        title = i18n("重新整理後自動更新離線內容"),
        subtitle = i18n("開啟後，重新整理已下載內容時會沿用目前目標刷新離線資料。"),
        checked = downloadedContentRefreshAutoUpdate,
        onCheckedChange = { appSettingsRepository.downloadedContentRefreshAutoUpdate.setValue(it) },
    )

    Spacer(Modifier.height(18.dp))

    SettingsToggleRow(
        title = i18n("新增收藏時詢問同步"),
        subtitle = i18n("開啟後，新建收藏時會詢問是否同步到百合會。"),
        checked = addSyncPromptEnabled,
        onCheckedChange = { appSettingsRepository.favoriteAddSyncPromptEnabled.setValue(it) },
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = i18n("新增收藏預設動作"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = listOf(true to i18n("同步到百合會"), false to i18n("只存本地")),
        selectedValue = addSyncDefault,
        onSelect = { appSettingsRepository.favoriteAddSyncDefault.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(18.dp))
    SettingsToggleRow(
        title = i18n("完全移除收藏時詢問同步刪除"),
        subtitle = i18n("開啟後，收藏將完全消失時會詢問是否同步從百合會移除。"),
        checked = removeSyncPromptEnabled,
        onCheckedChange = { appSettingsRepository.favoriteRemoveSyncPromptEnabled.setValue(it) },
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = i18n("完全移除收藏預設動作"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = listOf(true to i18n("同步移除"), false to i18n("只刪本地")),
        selectedValue = removeSyncDefault,
        onSelect = { appSettingsRepository.favoriteRemoveSyncDefault.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("收藏同步"))
    if (syncState != FavoriteSyncState.Idle) {
        FavoriteSyncStatusCard(
            state = syncState,
            modifier = Modifier.fillMaxWidth(),
            onOpenProgress = {
                settingsCurrentSyncRunId(syncState)?.let { navigator.navigate(IFavoriteSyncProgressScreen(it)) }
            },
            onResume = {
                coroutineScope.launch {
                    when (val result = favoriteSyncRunner.resumeInterruptedImport()) {
                        null -> Unit
                        is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Started -> {
                            navigator.navigate(IFavoriteSyncProgressScreen(result.runId))
                        }
                        is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Rejected -> {
                            feedbackController.post(result.reason)
                            if (result.requiresBackgroundAccessSetup) {
                                navigator.navigate(IBackgroundAccessSetupScreen())
                            }
                        }
                    }
                }
            },
            onInterrupt = {
                val runId = settingsCurrentSyncRunId(syncState)
                if (runId != null) {
                    coroutineScope.launch {
                        favoriteSyncRunner.interruptImport(runId)
                    }
                }
            },
        )
    } else {
        Text(
            text = i18n("這裡會顯示最近一次收藏同步的狀態與進度。同步開始後，你也可以從這裡重新打開進度畫面。"),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.68f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowDescription(title = title, subtitle = subtitle, colors = colors)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowDescription(title = title, subtitle = subtitle, colors = colors)
        Text(
            text = ">",
            color = colors.textDark.copy(alpha = 0.35f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RowScope.SettingsRowDescription(
    title: String,
    subtitle: String,
    colors: me.thenano.yamibo.yamibo_app.components.theme.YamiboColors,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textDark,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StorageContent(feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val appSettingsRepo = LocalAppSettingsRepository.current
    val diskCacheFactory = LocalDiskCacheFactory.current
    val downloadRepository = LocalDownloadRepository.current
    val coroutineScope = rememberCoroutineScope()

    val clearOnLaunch = appSettingsRepo.clearCacheOnAppLaunch.state()
    var cacheSizeText by remember { mutableStateOf(i18n("正在計算中...")) }
    var cacheBreakdown by remember { mutableStateOf(CacheStorageBreakdown(rootPath = "", usages = emptyList())) }
    var downloadSummary by remember { mutableStateOf(DownloadedContentSummary()) }

    suspend fun refreshCacheUsage() {
        cacheBreakdown = diskCacheFactory.getCacheStorageBreakdown()
        downloadSummary = runCatching { downloadRepository.getDownloadedContentSummary() }
            .onFailure { Logger.w(TAG, "Failed to load downloaded content summary for storage settings", it) }
            .getOrDefault(DownloadedContentSummary())
        cacheSizeText = formatStorageSize(cacheBreakdown.usages.sumOf { it.bytes })
    }

    LaunchedEffect(Unit) {
        refreshCacheUsage()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { appSettingsRepo.clearCacheOnAppLaunch.setValue(!clearOnLaunch) }
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = i18n("App 啟動時清除緩存"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textDark,
            )
        }
        Switch(
            checked = clearOnLaunch,
            onCheckedChange = { appSettingsRepo.clearCacheOnAppLaunch.setValue(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }

    Spacer(Modifier.height(24.dp))

    val storageUsages = cacheBreakdown.usages + CacheStorageUsage(
        key = "downloads",
        label = i18n("下載內容"),
        bytes = downloadSummary.imageBytes,
    )
    YamiboStorageUsageOverview(
        title = i18n("儲存空間使用情形"),
        rootPath = cacheBreakdown.rootPath,
        usages = storageUsages,
    )
    Spacer(Modifier.height(18.dp))

    DownloadStorageSummaryTable(
        summary = downloadSummary,
        onOpenDownloadManager = { navigator.navigate(IDownloadQueueScreen()) },
    )
    Spacer(Modifier.height(18.dp))

    SettingsActionRow(
        title = i18n("立即清除所有緩存"),
        subtitle = i18n("清除圖片、頁面與其他暫存資料，釋放目前已使用的儲存空間。") +
            "\n" +
            i18n("目前緩存大小：{}", cacheSizeText),
        onClick = {
            coroutineScope.launch {
                diskCacheFactory.clearAllCache()
                cacheSizeText = "0 kB"
                cacheBreakdown = cacheBreakdown.copy(usages = emptyList())
                feedbackController.post(i18n("已清除所有緩存"))
            }
        },
    )
}

private const val TAG = "SettingsCategoryScreen"

@Composable
private fun DownloadStorageSummaryTable(
    summary: DownloadedContentSummary,
    onOpenDownloadManager: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Text(
        text = i18n("下載內容"),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.brownDeep,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    StorageSummaryRow(i18n("帖子頁數"), summary.threadPages.toString())
    StorageSummaryRow(i18n("標籤漫畫章節數"), summary.tagMangaChapters.toString())
    StorageSummaryRow(i18n("圖片張數"), summary.imageCount.toString())
    StorageSummaryRow(i18n("容量"), formatStorageSize(summary.imageBytes))
    Spacer(Modifier.height(8.dp))
    SettingsActionRow(
        title = i18n("前往下載管理"),
        subtitle = i18n("查看下載佇列、已下載內容與清除/重新載入操作。"),
        onClick = onOpenDownloadManager,
    )
}

@Composable
private fun StorageSummaryRow(label: String, value: String) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textDark,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = colors.textDark.copy(alpha = 0.68f),
            fontSize = 13.sp,
        )
    }
}
@Composable
private fun SignSettingsContent() {
    val colors = YamiboTheme.colors
    val appSettingsRepository = LocalAppSettingsRepository.current
    val signMode = appSettingsRepository.signInMode.state()
    val launchReminder = appSettingsRepository.signInLaunchReminderEnabled.state()
    val allowRepair = appSettingsRepository.signInAllowRepair.state()
    val directWebView = appSettingsRepository.signInDirectWebView.state()
    val reminderFrequency = appSettingsRepository.signInReminderFrequency.state()
    val signReminderScheduler = LocalSignReminderScheduler.current
    val coroutineScope = rememberCoroutineScope()

    SectionLabel(i18n("啟動時簽到提醒"))
    SettingsToggleRow(
        title = i18n("啟動時簽到提醒"),
        subtitle = i18n("今天尚未簽到時，在 app 啟動後提醒你前往簽到。"),
        checked = launchReminder,
        onCheckedChange = { appSettingsRepository.signInLaunchReminderEnabled.setValue(it) },
    )
    val dismissToday = appSettingsRepository.signInLaunchReminderDismissToday.state()
    SettingsToggleRow(
        title = i18n("今日不再提醒"),
        subtitle = i18n("關閉彈窗後，今天內不會再次彈出提醒。關閉此設定後，每次開啟 app 都會提醒。"),
        checked = dismissToday,
        onCheckedChange = { appSettingsRepository.signInLaunchReminderDismissToday.setValue(it) },
    )
    SettingsToggleRow(
        title = i18n("提醒點擊直接進入簽到頁面"),
        subtitle = i18n("點擊開屏簽到提醒後，跳過個人中心，直接打開簽到網頁。"),
        checked = directWebView,
        onCheckedChange = { appSettingsRepository.signInDirectWebView.setValue(it) },
    )

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("背景簽到提醒排程"))
    Text(
        text = i18n("提醒頻率"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = SignReminderFrequency.entries.map { it to it.localizedLabel() },
        selectedValue = reminderFrequency,
        onSelect = { freq ->
            appSettingsRepository.signInReminderFrequency.setValue(freq)
            coroutineScope.launch {
                signReminderScheduler.schedule(freq)
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(24.dp))
    SectionLabel(i18n("簽到模式"))
    Text(
        text = i18n("簽到行為"),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textDark,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(6.dp))
    SettingsChipRow(
        options = SignInMode.entries.map { it to it.localizedLabel() },
        selectedValue = signMode,
        onSelect = { appSettingsRepository.signInMode.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(24.dp))

    SectionLabel(i18n("補簽"))
    SettingsToggleRow(
        title = i18n("自動進行補簽"),
        subtitle = when (signMode) {
            SignInMode.FULL_MANUAL -> i18n("手動模式下只保存這個偏好；半自動模式才會真的自動補簽。")
            SignInMode.SEMI_AUTOMATIC -> i18n("完成 Cloudflare 驗證後，程式會在簽到成功後一路補到不能再補。")
        },
        checked = allowRepair,
        onCheckedChange = { appSettingsRepository.signInAllowRepair.setValue(it) },
    )
}

private fun settingsCurrentSyncRunId(state: FavoriteSyncState): String? {
    return when (state) {
        FavoriteSyncState.Idle -> null
        is FavoriteSyncState.Running -> state.snapshot.runId
        is FavoriteSyncState.Interrupted -> state.snapshot.runId
        is FavoriteSyncState.Failed -> state.snapshot.runId
        is FavoriteSyncState.Completed -> state.snapshot.runId
    }
}
