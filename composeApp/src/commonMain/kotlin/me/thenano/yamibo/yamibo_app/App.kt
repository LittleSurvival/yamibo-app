package me.thenano.yamibo.yamibo_app

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.waf.YamiboWafChallengeHost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.components.font.getFontFamily
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationDelivery
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationResult
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackResult
import me.thenano.yamibo.yamibo_app.event.AppEventBus
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.home.HomePageScreen
import me.thenano.yamibo.yamibo_app.i18n.AppLocaleProvider
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.ComposableNavigator
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.NavAction
import me.thenano.yamibo.yamibo_app.profile.settings.update.AppUpdatePromptContent
import me.thenano.yamibo.yamibo_app.profile.sign.ISignWebView
import me.thenano.yamibo.yamibo_app.profile.sign.shouldDismissSignReminderFor
import me.thenano.yamibo.yamibo_app.profile.sign.shouldEmitSignStatusChanged
import me.thenano.yamibo.yamibo_app.profile.sign.signActionFeedbackMessage
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateCheckResult
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateDownloadState
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateRelease
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.ChineseConversionMode
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.ReaderChineseConversionOption
import me.thenano.yamibo.yamibo_app.repository.settings.SignInMode
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKey
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal val showSignWebViewTrigger = mutableStateOf(false)
private const val APP_FEEDBACK_Z_INDEX = Float.MAX_VALUE

@Composable
fun YamiboWafRecoveryRoot(
    client: YamiboClient,
    content: @Composable () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isForeground = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        YamiboWafChallengeHost(
            client = client,
            isForeground = isForeground,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}

@Composable
fun HomeScreenContent(
    onNewMessageStatusChange: (Boolean) -> Unit = {},
) {
    HomePageScreen(onNewMessageStatusChange = onNewMessageStatusChange)
}

@Composable
fun App() {
    val imageLoaderFactory = remember {
        { context: PlatformContext ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.35)
                        .build()
                }
                .components {
                    // Coil lazily retains this client for the singleton ImageLoader, allowing the
                    // in-memory WAF cookie to be reused without another app-level singleton.
                    add(KtorNetworkFetcherFactory(httpClient = { HttpClientFactory.create() }))
                    add(SvgDecoder.Factory())
                }
                .build()
            }
    }
    setSingletonImageLoaderFactory(imageLoaderFactory)

    val navigator = LocalNavigator.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val authRepository = LocalAuthRepository.current
    val signRepository = LocalSignRepository.current
    val signReminderScheduler = LocalSignReminderScheduler.current
    val appUpdateRepository = LocalAppUpdateRepository.current
    val fontRepository = LocalFontRepository.current
    val feedbackController = LocalAppFeedbackController.current
    val confirmationController = LocalAppConfirmationController.current
    val appTaskManager = LocalAppTaskManager.current
    val appLanguage = appSettingsRepository.language.state()
    val appFontId = appSettingsRepository.appFontId.state()
    val appFontFamily = remember(appFontId) { fontRepository.getFontFamily(appFontId) }
    val signLaunchReminderEnabled = appSettingsRepository.signInLaunchReminderEnabled.state()
    val holder = rememberSaveableStateHolder()
    navigator.stateHolder = holder
    ChineseConversionModeSync()

    val stack = navigator.stack
    val poppingIdx by navigator.poppingIndex
    val duration = 250
    var completedPushTopId by remember { mutableStateOf(stack.lastOrNull()?.id) }
    var showSignReminder by remember { mutableStateOf(false) }
    var launchUpdateRelease by remember { mutableStateOf<AppUpdateRelease?>(null) }
    LaunchedEffect(signReminderScheduler) {
        AppEventBus.events.collect { event ->
            if (shouldDismissSignReminderFor(event)) {
                signReminderScheduler.dismissActiveReminder()
            }
        }
    }
    LaunchedEffect(Unit) {
        val threshold = appSettingsRepository.appUpdateLaunchCheckThreshold.getValue()
        val intervalMillis = threshold.fixedInterval?.duration?.inWholeMilliseconds
            ?: return@LaunchedEffect
        val now = currentTimeMillis()
        val lastCheckAt = appSettingsRepository.appUpdateLastCheckAt.getValue().toLongOrNull() ?: 0L
        if (now - lastCheckAt >= intervalMillis) {
            val result = appUpdateRepository.checkForUpdate(force = false)
            if (result is AppUpdateCheckResult.UpdateAvailable) {
                launchUpdateRelease = result.release
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val downloadState by appUpdateRepository.downloadState.collectAsState()

    DisposableEffect(lifecycleOwner, downloadState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val state = downloadState
                if (state is AppUpdateDownloadState.PermissionRequired && appUpdateRepository.isInstallPermissionGranted) {
                    appTaskManager.launch(AppTaskKey("app-update:download-install")) {
                        appUpdateRepository.downloadAndInstall(state.release)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AppLocaleProvider(appLanguage) {
        val rootTextStyle = LocalTextStyle.current
        CompositionLocalProvider(
            LocalTextStyle provides rootTextStyle.copy(fontFamily = appFontFamily),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = YamiboTheme.colors.creamBackground
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val topIndex = stack.lastIndex
                val topId = stack.lastOrNull()?.id
                val renderPreviousForPush =
                    navigator.lastAction == NavAction.Push &&
                        topIndex > 0 &&
                        completedPushTopId != topId
                stack.forEachIndexed { index, navigatable ->
                    val isPopping = index == poppingIdx
                    val isTop = index == stack.lastIndex
                    val isNewPush =
                        navigator.lastAction == NavAction.Push &&
                            isTop &&
                            !isPopping &&
                            topIndex > 0 &&
                            completedPushTopId != navigatable.id
                    val shouldDraw =
                        isTop ||
                            isPopping ||
                            (poppingIdx >= 0 && index == poppingIdx - 1) ||
                            (renderPreviousForPush && index == topIndex - 1)

                    key(navigatable.id) {
                        // New push screens start invisible (false→true), others start visible
                        val visibleState = remember {
                            MutableTransitionState(!isNewPush)
                        }

                        // Drive animation: pop = true→false, otherwise stay/become true
                        if (isPopping) {
                            visibleState.targetState = false
                        } else {
                            visibleState.targetState = true
                        }

                        holder.SaveableStateProvider(navigatable.id) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(duration)
                                ) + fadeIn(animationSpec = tween(duration)),
                                exit = slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(duration)
                                ) + fadeOut(animationSpec = tween(duration)),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawOnlyWhen(shouldDraw)
                                    .blockPointerPassthrough(isTop || isPopping)
                                    .zIndex(index.toFloat())
                            ) {
                                navigatable.Content()
                            }
                        }

                        // When exit animation finished, actually remove from stack
                        if (isPopping && visibleState.isIdle && !visibleState.currentState) {
                            LaunchedEffect(Unit) {
                                navigator.completePop()
                            }
                        }
                        if (isNewPush && visibleState.isIdle && visibleState.currentState && completedPushTopId != navigatable.id) {
                            LaunchedEffect(navigatable.id) {
                                completedPushTopId = navigatable.id
                            }
                        }
                    }
                }
                AppFeedbackHost(
                    controller = feedbackController,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 72.dp)
                        .zIndex(APP_FEEDBACK_Z_INDEX)
                )
            }
            LaunchSignReminderDialog(
                visible = showSignReminder,
                dismissTodayChecked = appSettingsRepository.signInLaunchReminderDismissToday.state(),
                onDismissTodayChange = { appSettingsRepository.signInLaunchReminderDismissToday.setValue(it) },
                onDismiss = {
                    if (appSettingsRepository.signInLaunchReminderDismissToday.getValue()) {
                        appSettingsRepository.signInLaunchReminderDismissedDate.setValue(currentLocalDateKey())
                    }
                    showSignReminder = false
                },
                onGoSign = {
                    showSignReminder = false
                    navigateToSignWebViewOrProfile(
                        navigator = navigator,
                        appSettingsRepository = appSettingsRepository,
                        authRepository = authRepository,
                        signRepository = signRepository,
                        appTaskManager = appTaskManager,
                        feedbackController = feedbackController,
                    )
                },
            )
            LaunchUpdateAvailableDialog(
                release = launchUpdateRelease,
                onDismiss = { launchUpdateRelease = null },
                onDownload = { release ->
                    launchUpdateRelease = null
                    appTaskManager.launch(AppTaskKey("app-update:download-install")) {
                        appUpdateRepository.downloadAndInstall(release)
                    }
                },
                onOpenReleasePage = { release ->
                    launchUpdateRelease = null
                    appUpdateRepository.openReleasePage(release)
                },
            )
            AppConfirmationHost(controller = confirmationController)
            }
        }
    }

    LaunchedEffect(signLaunchReminderEnabled, appLanguage) {
        if (!signLaunchReminderEnabled) {
            showSignReminder = false
            return@LaunchedEffect
        }
        val today = currentLocalDateKey()
        val dismissToday = appSettingsRepository.signInLaunchReminderDismissToday.getValue()
        if (dismissToday && appSettingsRepository.signInLaunchReminderDismissedDate.getValue() == today) return@LaunchedEffect
        if (authRepository.currentUser() == null) return@LaunchedEffect
        if (!signRepository.isSignedToday()) {
            showSignReminder = true
        }
    }

    LaunchedEffect(showSignWebViewTrigger.value) {
        if (showSignWebViewTrigger.value) {
            showSignWebViewTrigger.value = false
            navigateToSignWebViewOrProfile(
                navigator = navigator,
                appSettingsRepository = appSettingsRepository,
                authRepository = authRepository,
                signRepository = signRepository,
                appTaskManager = appTaskManager,
                feedbackController = feedbackController,
            )
        }
    }
}

@Composable
private fun AppConfirmationHost(controller: AppConfirmationController) {
    var currentDelivery by remember { mutableStateOf<AppConfirmationDelivery?>(null) }
    val resolutionScope = rememberCoroutineScope()

    LaunchedEffect(controller) {
        controller.deliveries.collect { delivery ->
            currentDelivery = delivery
            try {
                delivery.awaitResult()
            } finally {
                if (currentDelivery?.id == delivery.id) currentDelivery = null
            }
        }
    }

    val delivery = currentDelivery ?: return
    val event = delivery.event
    fun resolve(result: AppConfirmationResult) {
        resolutionScope.launch { controller.resolve(delivery.id, result) }
    }

    AlertDialog(
        onDismissRequest = { resolve(AppConfirmationResult.Dismissed) },
        title = {
            Text(
                text = event.title,
                color = YamiboTheme.colors.textStrong,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = event.message,
                color = YamiboTheme.colors.textDark,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { resolve(AppConfirmationResult.Confirmed) }) {
                Text(event.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = { resolve(AppConfirmationResult.Dismissed) }) {
                Text(event.dismissLabel)
            }
        },
        containerColor = YamiboTheme.colors.creamSurface,
        properties = DialogProperties(
            dismissOnBackPress = event.dismissOnBackPress,
            dismissOnClickOutside = event.dismissOnClickOutside,
        ),
    )
}

@Composable
private fun AppFeedbackHost(
    controller: AppFeedbackController,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(controller) {
        controller.deliveries.collect { delivery ->
            val event = delivery.event
            if (!controller.isCurrent(delivery)) {
                controller.resolve(delivery, AppFeedbackResult.Dismissed)
                return@collect
            }
            val result = coroutineScope {
                val replacementJob = event.groupKey?.let { groupKey ->
                    launch {
                        controller.latestGroups
                            .map { it[groupKey] }
                            .first { it != delivery.id }
                        hostState.currentSnackbarData?.dismiss()
                    }
                }
                try {
                    hostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        withDismissAction = event.withDismissAction,
                        duration = when (event.duration) {
                            AppFeedbackDuration.Short -> SnackbarDuration.Short
                            AppFeedbackDuration.Long -> SnackbarDuration.Long
                            AppFeedbackDuration.Indefinite -> SnackbarDuration.Indefinite
                        },
                    )
                } finally {
                    replacementJob?.cancel()
                }
            }
            controller.resolve(
                delivery,
                when (result) {
                    SnackbarResult.ActionPerformed -> AppFeedbackResult.ActionPerformed
                    SnackbarResult.Dismissed -> AppFeedbackResult.Dismissed
                },
            )
        }
    }
    YamiboSnackbarHost(hostState = hostState, modifier = modifier)
}

private fun Modifier.blockPointerPassthrough(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                }
            }
        }
    }

private fun Modifier.drawOnlyWhen(shouldDraw: Boolean): Modifier =
    drawWithContent {
        if (shouldDraw) drawContent()
    }

@Composable
private fun LaunchUpdateAvailableDialog(
    release: AppUpdateRelease?,
    onDismiss: () -> Unit,
    onDownload: (AppUpdateRelease) -> Unit,
    onOpenReleasePage: (AppUpdateRelease) -> Unit,
) {
    if (release == null) return
    Dialog(onDismissRequest = onDismiss) {
        LaunchUpdateAvailableContent(
            release = release,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onOpenReleasePage = onOpenReleasePage,
        )
    }
}

@Composable
private fun LaunchUpdateAvailableContent(
    release: AppUpdateRelease,
    onDismiss: () -> Unit,
    onDownload: (AppUpdateRelease) -> Unit,
    onOpenReleasePage: (AppUpdateRelease) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppUpdatePromptContent(
        release = release,
        onPrimaryClick = {
            if (release.asset == null) {
                onOpenReleasePage(release)
            } else {
                onDownload(release)
            }
        },
        onManualClick = { onOpenReleasePage(release) },
        onLaterClick = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun LaunchSignReminderDialog(
    visible: Boolean,
    dismissTodayChecked: Boolean,
    onDismissTodayChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onGoSign: () -> Unit,
) {
    if (!visible) return
    val colors = YamiboTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.creamSurface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = i18n("你今天還沒簽到"),
                    color = colors.textStrong,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = i18n("今天尚未完成每日簽到。"),
                    color = colors.textDark.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismissTodayChange(!dismissTodayChecked) }
                        .padding(vertical = 2.dp),
                ) {
                    Checkbox(
                        checked = dismissTodayChecked,
                        onCheckedChange = onDismissTodayChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.brownDeep,
                            uncheckedColor = colors.brownPrimary.copy(alpha = 0.6f),
                            checkmarkColor = colors.textOnDeep,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = i18n("今日不再提醒"),
                        fontSize = 14.sp,
                        color = colors.textDark,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textStrong,
                        ),
                    ) {
                        Text(i18n("取消"))
                    }
                    Button(
                        onClick = onGoSign,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.brownDeep,
                            contentColor = colors.textOnDeep,
                        ),
                    ) {
                        Text(i18n("前往簽到"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChineseConversionModeSync() {
    val conversionRepository = LocalChineseConversionRepository.current
    val novelSettingsRepository = LocalNovelReaderSettingsRepository.current
    val option = novelSettingsRepository.chineseConversion.state()

    LaunchedEffect(option) {
        conversionRepository.setConversionMode(
            when (option) {
                ReaderChineseConversionOption.DEFAULT -> null
                ReaderChineseConversionOption.SIMPLIFIED -> ChineseConversionMode.Simplified
                ReaderChineseConversionOption.TRADITIONAL -> ChineseConversionMode.Traditional
            }
        )
    }
}

private fun navigateToSignWebViewOrProfile(
    navigator: ComposableNavigator,
    appSettingsRepository: AppSettingsRepository,
    authRepository: AuthRepository,
    signRepository: SignRepository,
    appTaskManager: AppTaskManager,
    feedbackController: AppFeedbackController,
) {
    if (authRepository.currentUser() == null) return
    val isDirect = appSettingsRepository.signInDirectWebView.getValue()
    if (isDirect) {
        val mode = appSettingsRepository.signInMode.getValue()
        val allowRepair = appSettingsRepository.signInAllowRepair.getValue()
        when (mode) {
            SignInMode.FULL_MANUAL -> {
                navigator.navigate(
                    ISignWebView(
                        semiAutomatic = false,
                        onResultObserved = {
                            appTaskManager.launch(AppTaskKey("sign:manual-complete")) {
                                authRepository.syncCookieFromWebView()
                                signRepository.markTodaySigned()
                                AppEventBus.emit(SignStatusChangedEvent)
                                signRepository.fetchPageInfo()
                                feedbackController.post(i18n("簽到成功"))
                            }
                        },
                        onLoadFailed = { reason ->
                            feedbackController.post(i18n("簽到頁載入失敗：{}", reason))
                        }
                    )
                )
            }
            SignInMode.SEMI_AUTOMATIC -> {
                navigator.navigate(
                    ISignWebView(
                        semiAutomatic = true,
                        onCfCleared = {
                            appTaskManager.launch(AppTaskKey("sign:auto")) {
                                feedbackController.post(i18n("開始自動簽到..."))
                                val result = signRepository.runAutoSign(allowRepair)
                                if (shouldEmitSignStatusChanged(result)) {
                                    AppEventBus.emit(SignStatusChangedEvent)
                                }
                                feedbackController.post(result.signActionFeedbackMessage())
                            }
                        },
                        onMaintenanceObserved = {
                            feedbackController.post(i18n("百合會維護中...現在不是簽到的好時機呢"))
                        },
                        onLoadFailed = { reason ->
                            feedbackController.post(i18n("簽到頁載入失敗：{}", reason))
                        }
                    )
                )
            }
        }
    } else {
        navigator.popToRoot()
        navigator.replace(IMainScreen(MainTab.Profile))
    }
}
