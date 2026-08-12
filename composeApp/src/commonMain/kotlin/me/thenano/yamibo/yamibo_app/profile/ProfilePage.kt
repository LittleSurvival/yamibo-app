package me.thenano.yamibo.yamibo_app.profile


import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme.colors
import me.thenano.yamibo.yamibo_app.event.AppEventBus
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.LoginSuccessEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.localnovel.ILocalNovelBookshelfScreen
import me.thenano.yamibo.yamibo_app.message.IMessageCenterScreen
import me.thenano.yamibo.yamibo_app.message.MessageCenterTab
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.about.IAboutScreen
import me.thenano.yamibo.yamibo_app.profile.download.IDownloadQueueScreen
import me.thenano.yamibo.yamibo_app.profile.settings.ISettingsScreen
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IBackupSettingsScreen
import me.thenano.yamibo.yamibo_app.profile.settings.cloud.IAppSyncSettingsScreen
import me.thenano.yamibo.yamibo_app.profile.sign.ISignInfoScreen
import me.thenano.yamibo.yamibo_app.profile.sign.ISignWebView
import me.thenano.yamibo.yamibo_app.profile.sign.signActionFeedbackMessage
import me.thenano.yamibo.yamibo_app.profile.sign.shouldEmitSignStatusChanged
import me.thenano.yamibo.yamibo_app.profile.support.ISupportAppDevelopmentScreen
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.settings.SignInMode

internal fun shouldShowDownloadBadge(queue: List<DownloadQueueEntry>): Boolean =
    queue.any { entry -> entry.status == DownloadStatus.Queued || entry.status == DownloadStatus.Downloading }

internal enum class PassiveSignButtonState {
    Signed,
    Available,
}

internal fun resolvePassiveSignButtonState(signRepository: SignRepository): PassiveSignButtonState =
    if (signRepository.getKnownSignedToday() == true) {
        PassiveSignButtonState.Signed
    } else {
        PassiveSignButtonState.Available
    }

internal fun isProfileSignRefreshEvent(event: AppEvent): Boolean =
    event == LoginSuccessEvent || event == SignStatusChangedEvent

private fun PassiveSignButtonState.label(): String = when (this) {
    PassiveSignButtonState.Signed -> i18n("今日已簽到")
    PassiveSignButtonState.Available -> i18n("點擊簽到")
}

@Composable
fun ProfilePage(
    hasNewMessage: Boolean = false,
    onNewMessageStatusChange: (Boolean) -> Unit = {},
) {
    val authRepository = LocalAuthRepository.current
    val downloadRepository = LocalDownloadRepository.current
    val signRepository = LocalSignRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val navigator = LocalNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val colors = colors
    val feedbackController = LocalAppFeedbackController.current
    val hasDownloadBadge by remember(downloadRepository) {
        downloadRepository.queue
            .map(::shouldShowDownloadBadge)
            .distinctUntilChanged()
    }.collectAsState(false)

    var userInfo by remember { mutableStateOf(authRepository.currentUser()) }
    var isLoading by remember { mutableStateOf(false) }
    var signRefreshKey by remember { mutableIntStateOf(0) }
    var isSigning by remember { mutableStateOf(false) }
    var signButtonTitle by remember {
        mutableStateOf(
            if (userInfo == null) {
                i18n("點擊簽到")
            } else {
                resolvePassiveSignButtonState(signRepository).label()
            }
        )
    }

    fun refreshSignStatus() {
        if (isSigning) {
            signButtonTitle = i18n("正在簽到...")
            return
        }
        if (userInfo == null) {
            signButtonTitle = i18n("點擊簽到")
            return
        }
        signButtonTitle = resolvePassiveSignButtonState(signRepository).label()
    }

    LaunchedEffect(Unit) {
        AppEventBus.events.collect { event ->
            if (isProfileSignRefreshEvent(event)) {
                if (event == LoginSuccessEvent) {
                    userInfo = authRepository.currentUser()
                }
                signRefreshKey += 1
            }
        }
    }

    LaunchedEffect(userInfo, signRefreshKey) {
        refreshSignStatus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.creamBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            UserProfileCard(
                userInfo = userInfo,
                isLoading = isLoading,
                onRefresh = {
                    coroutineScope.launch {
                        isLoading = true
                        authRepository.fetchStatus()
                        userInfo = authRepository.currentUser()
                        signRefreshKey += 1
                        isLoading = false
                    }
                },
                onLogout = {
                    coroutineScope.launch {
                        isLoading = true
                        authRepository.logOut()
                        userInfo = authRepository.currentUser()
                        signRefreshKey += 1
                        isLoading = false
                    }
                },
                modifier = Modifier.padding(top = 12.dp)
            )
            Spacer(Modifier.height(8.dp))

            SignEntryCard(
                title = signButtonTitle,
                onSignClick = {
                    if (userInfo == null) {
                        return@SignEntryCard
                    }
                    when (appSettingsRepository.signInMode.getValue()) {
                        SignInMode.FULL_MANUAL -> {
                            navigator.navigate(
                                ISignWebView(
                                    semiAutomatic = false,
                                    onResultObserved = {
                                        coroutineScope.launch {
                                            isSigning = true
                                            signButtonTitle = i18n("正在簽到...")
                                            authRepository.syncCookieFromWebView()
                                            signRepository.markTodaySigned()
                                            AppEventBus.emit(SignStatusChangedEvent)
                                            when (signRepository.fetchPageInfo()) {
                                                is YamiboResult.Success -> {
                                                    signRefreshKey += 1
                                                    refreshSignStatus()
                                                }

                                                else -> Unit
                                            }
                                            isSigning = false
                                            refreshSignStatus()
                                        }
                                    }
                                )
                            )
                        }

                        SignInMode.SEMI_AUTOMATIC -> {
                            isSigning = true
                            signButtonTitle = i18n("正在簽到...")
                            val allowRepair = appSettingsRepository.signInAllowRepair.getValue()
                            navigator.navigate(
                                ISignWebView(
                                    semiAutomatic = true,
                                    onCfCleared = {
                                        coroutineScope.launch {
                                            var snackbarMessage: String?
                                            val result = signRepository.runAutoSign(allowRepair)
                                            when (result) {
                                                is YamiboResult.Success -> {
                                                    signRefreshKey += 1
                                                    snackbarMessage = result.value.message
                                                }

                                                else -> snackbarMessage = result.signActionFeedbackMessage()
                                            }
                                            if (shouldEmitSignStatusChanged(result)) {
                                                AppEventBus.emit(SignStatusChangedEvent)
                                            }
                                            isSigning = false
                                            refreshSignStatus()
                                            snackbarMessage.let { message ->
                                                coroutineScope.launch {
                                                    feedbackController.post(message)
                                                }
                                            }
                                        }
                                    },
                                    onMaintenanceObserved = {
                                        coroutineScope.launch {
                                            isSigning = false
                                            refreshSignStatus()
                                            feedbackController.post(i18n("百合會維護中...現在不是簽到的好時機呢"))
                                        }
                                    },
                                    onLoadFailed = { reason ->
                                        coroutineScope.launch {
                                            isSigning = false
                                            refreshSignStatus()
                                            feedbackController.post(i18n("簽到頁載入失敗：{}", reason))
                                        }
                                    },
                                )
                            )
                        }
                    }
                },
                onInfoClick = {
                    navigator.navigate(
                        ISignInfoScreen(
                            onInfoLoaded = {
                                signRefreshKey += 1
                            }
                        )
                    )
                },
            )

            EntryCard(
                title = i18n("我的消息"),
                icon = YamiboIcons.Message,
                showBadge = hasNewMessage,
                onClick = {
                    navigator.navigate(
                        IMessageCenterScreen(
                            initialTab = MessageCenterTab.PrivateMessages,
                            onPrivateMessageUnreadChange = onNewMessageStatusChange,
                        )
                    )
                },
            )

            EntryDivider()

            EntryCard(
                title = i18n("設定"),
                icon = YamiboIcons.Setting,
                onClick = { navigator.navigate(ISettingsScreen()) }
            )

            EntryCard(
                title = i18n("閱讀統計"),
                icon = YamiboIcons.Statistics,
                onClick = { navigator.navigate(IProfileStatisticsScreen()) }
            )

            EntryCard(
                title = i18n("雲端同步"),
                icon = YamiboIcons.CloudSync,
                onClick = { navigator.navigate(IAppSyncSettingsScreen()) }
            )

            EntryCard(
                title = i18n("本地資料備份"),
                icon = YamiboIcons.Backup,
                onClick = { navigator.navigate(IBackupSettingsScreen()) }
            )

            EntryCard(
                title = i18n("下載管理"),
                icon = YamiboIcons.Download,
                showBadge = hasDownloadBadge,
                onClick = { navigator.navigate(IDownloadQueueScreen()) }
            )

            EntryCard(
                title = i18n("本地小說"),
                icon = YamiboIcons.Book,
                onClick = { navigator.navigate(ILocalNovelBookshelfScreen()) }
            )

            EntryDivider()

            EntryCard(
                title = i18n("支持App開發"),
                icon = YamiboIcons.Heart,
                onClick = { navigator.navigate(ISupportAppDevelopmentScreen()) }
            )

            EntryCard(
                title = i18n("關於 ({})", AppVersion.displayName),
                icon = YamiboIcons.InfoCircle,
                onClick = { navigator.navigate(IAboutScreen()) }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = i18n("app dev by thenano"),
                color = colors.textDark.copy(alpha = 0.58f),
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }

    }
}

@Composable
private fun SignEntryCard(
    title: String,
    onSignClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(0.85f)
                    .profilePressScaleClickable(pressedScale = 0.98f, onClick = onSignClick)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = YamiboIcons.EditOrSign,
                    contentDescription = i18n("每日簽到"),
                    tint = colors.brownPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textDark
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.15f)
                    .fillMaxHeight()
                    .profilePressScaleClickable(pressedScale = 0.92f, onClick = onInfoClick)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(24.dp)
                        .padding(start = 10.dp),
                    color = colors.brownLight.copy(alpha = 0.35f),
                    thickness = 1.dp,
                )
                Icon(
                    imageVector = YamiboIcons.SignStatistics,
                    contentDescription = i18n("簽到資訊"),
                    tint = colors.brownPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun EntryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        color = colors.brownLight.copy(alpha = 0.22f),
    )
}

@Composable
private fun EntryCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    showBadge: Boolean = false,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .profilePressScaleClickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = colors.brownPrimary,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(8.dp)
                            .background(colors.redAccent, CircleShape),
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textDark,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = colors.textDark.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}
