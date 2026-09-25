package me.thenano.yamibo.yamibo_app

import YamiboIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.favorite.FavoritePage
import me.thenano.yamibo.yamibo_app.history.ReadHistoryPage
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.message.MessageCenterScreen
import me.thenano.yamibo.yamibo_app.message.MessageCenterTab
import me.thenano.yamibo.yamibo_app.navigation.*
import me.thenano.yamibo.yamibo_app.profile.ProfilePage
import me.thenano.yamibo.yamibo_app.updates.UpdatesPage
import me.thenano.yamibo.yamibo_app.components.systembars.SystemBarsEffect
import me.thenano.yamibo.yamibo_app.components.systembars.SystemBarsScope
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import kotlin.time.Duration.Companion.milliseconds

enum class MainTab(val icon: ImageVector) {
    Home(YamiboIcons.Home),
    History(YamiboIcons.History),
    Updates(YamiboIcons.New),
    Message(YamiboIcons.Message),
    Favorite(YamiboIcons.Explore),
    Profile(YamiboIcons.Profile)
}

@Serializable
private data class MainScreenRestorePayload(
    val initialTabName: String = MainTab.Home.name,
)
@RestorableScreenEntry
class IMainScreen(val initialTab: MainTab = MainTab.Home) : RestorableNavigatable {
    override val id = buildId(initialTab.name)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = MainScreenRestorePayload(initialTabName = initialTab.name),
    )

    @Composable
    override fun Content() {
        MainScreen(initialTab)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IMainScreen>(IMainScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<MainScreenRestorePayload>(payload)
            return IMainScreen(initialTab = MainTab.valueOf(data.initialTabName))
        }
    }
}

data class BottomNavItem(
    val tab: MainTab,
    val title: String,
    val icon: ImageVector,
    val showBadge: Boolean = false,
)

@Composable
fun MainScreen(initialTab: MainTab = MainTab.Home) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val initialVisibleTab = remember(initialTab) {
        if (initialTab == MainTab.Message) MainTab.Profile else initialTab
    }
    var currentTab by rememberSaveable { mutableStateOf(initialVisibleTab) }
    var shouldKeepUpdatesTabAlive by rememberSaveable {
        mutableStateOf(initialVisibleTab == MainTab.Updates)
    }
    var reTapHistoryToken by remember { mutableIntStateOf(0) }
    var hasNewMessage by rememberSaveable { mutableStateOf(false) }
    val tabStateHolder = rememberSaveableStateHolder()
    val visibleTabs = remember {
        MainTab.entries.filterNot { it == MainTab.Message }
    }
    val statusBarColor = when (currentTab) {
        MainTab.Home -> colors.brownDeep
        else -> colors.creamBackground
    }
    SystemBarsEffect(
        statusBarColor = statusBarColor,
        navigationBarColor = colors.navBarBg,
    )
    DisposableEffect(currentTab) {
        val handler = {
            if (navigator.currentScreen is IMainScreen && currentTab != MainTab.Home) {
                currentTab = MainTab.Home
                true
            } else {
                false
            }
        }
        navigator.backHandlers.add(handler)
        onDispose { navigator.backHandlers.remove(handler) }
    }

    LaunchedEffect(Unit) {
        if (!shouldKeepUpdatesTabAlive) {
            delay(1200.milliseconds)
            shouldKeepUpdatesTabAlive = true
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == MainTab.Updates) {
            shouldKeepUpdatesTabAlive = true
        }
    }

    val updatesTabAlpha by animateFloatAsState(
        targetValue = if (currentTab == MainTab.Updates) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (currentTab == MainTab.Updates) 130 else 70,
            delayMillis = if (currentTab == MainTab.Updates) 70 else 0,
            easing = if (currentTab == MainTab.Updates) LinearOutSlowInEasing else FastOutLinearInEasing,
        ),
        label = "UpdatesTabFade",
    )

    Scaffold(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        containerColor = colors.creamBackground,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            MainScreenBottomBar(
                tabs = visibleTabs.map {
                    BottomNavItem(
                        tab = it,
                        title = it.titleText(),
                        icon = it.icon,
                        showBadge = it == MainTab.Profile && hasNewMessage,
                    )
                },
                currentTab = BottomNavItem(
                    tab = currentTab,
                    title = currentTab.titleText(),
                    icon = currentTab.icon,
                    showBadge = currentTab == MainTab.Profile && hasNewMessage,
                ),
                onTabSelected = { selected ->
                    val newTab = selected.tab
                    if (newTab == MainTab.History && currentTab == MainTab.History) {
                        reTapHistoryToken++
                    }
                    if (newTab == currentTab) {
                        return@MainScreenBottomBar
                    }
                    currentTab = newTab
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier =
                Modifier.padding(paddingValues)
                    .fillMaxSize()
                    .background(colors.creamBackground)
        ) {
            if (shouldKeepUpdatesTabAlive) {
                tabStateHolder.SaveableStateProvider(MainTab.Updates.name) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = updatesTabAlpha }
                            .zIndex(if (currentTab == MainTab.Updates) 1f else -1f)
                    ) {
                        SystemBarsScope(active = currentTab == MainTab.Updates) {
                            UpdatesPage()
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = currentTab.takeIf { it != MainTab.Updates },
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 130,
                            delayMillis = 70,
                            easing = LinearOutSlowInEasing,
                        )
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = 70,
                            delayMillis = 0,
                            easing = FastOutLinearInEasing,
                        )
                    )
                },
                label = "MainTabFade",
            ) { tab ->
                if (tab == null) {
                    Box(modifier = Modifier.fillMaxSize())
                    return@AnimatedContent
                }
                tabStateHolder.SaveableStateProvider(tab.name) {
                    SystemBarsScope(active = tab == currentTab) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (tab) {
                                MainTab.Home -> HomeScreenContent(
                                    onNewMessageStatusChange = { hasNewMessage = it },
                                )
                                MainTab.History -> ReadHistoryPage(reTapHistoryToken)
                                MainTab.Updates -> Unit
                                MainTab.Message -> MessageCenterScreen(
                                    initialTab = MessageCenterTab.PrivateMessages,
                                    mainTabTopBar = true,
                                    onPrivateMessageUnreadChange = { hasNewMessage = it },
                                )
                                MainTab.Favorite -> FavoritePage()
                                MainTab.Profile -> ProfilePage(
                                    hasNewMessage = hasNewMessage,
                                    onNewMessageStatusChange = { hasNewMessage = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MainTab.titleText(): String {
    return when (this) {
        MainTab.Home -> i18n("首頁")
        MainTab.History -> i18n("紀錄")
        MainTab.Updates -> i18n("更新")
        MainTab.Message -> i18n("我的消息")
        MainTab.Favorite -> i18n("收藏")
        MainTab.Profile -> i18n("我的")
    }
}

@Composable
fun MainScreenBottomBar(
    tabs: List<BottomNavItem>,
    currentTab: BottomNavItem,
    onTabSelected: (BottomNavItem) -> Unit
) {
    val colors = YamiboTheme.colors
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(56.dp)
                .background(colors.navBarBg)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab == currentTab
            val color by
            animateColorAsState(
                targetValue = if (selected) colors.navBarIconSelected else colors.navBarIconUnselected,
                animationSpec = tween(durationMillis = 120),
            )

            Column(
                modifier =
                    Modifier.weight(1f).clickable(
                        interactionSource =
                            remember { MutableInteractionSource() },
                        indication = null
                    ) { onTabSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                    if (tab.showBadge) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-2).dp)
                                .size(8.dp)
                                .background(colors.redAccent, CircleShape)
                        )
                    }
                }
                Text(text = tab.title, color = color, fontSize = 12.sp)
            }
        }
    }
}
