package me.thenano.yamibo.yamibo_app.home

import YamiboIcons
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.page.ForumCategory
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.SwiperImages
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalForumRepository
import me.thenano.yamibo.yamibo_app.event.AppEventBus
import me.thenano.yamibo.yamibo_app.event.events.LoginSuccessEvent
import me.thenano.yamibo.yamibo_app.forum.IForumScreen
import me.thenano.yamibo.yamibo_app.forum.search.ISearchScreen
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboDetailedErrorContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboHomeTopBar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import me.thenano.yamibo.yamibo_app.util.state
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.logo_homepage
import kotlin.time.Duration.Companion.milliseconds

/** Sealed state for the home page */
private sealed interface HomeState {
    data object Loading : HomeState
    data class Success(val page: HomePage) : HomeState
    data class Error(val message: String) : HomeState
}

/** Main Entry */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePageScreen(
    onNewMessageStatusChange: (Boolean) -> Unit = {},
) {
    val colors = YamiboTheme.colors
    val forumRepository = LocalForumRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current

    var state by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun mapFetchResultState() {
        val result = forumRepository.fetchHomePage()
        state =
            when (result) {
                is YamiboResult.Success -> HomeState.Success(result.value)
                else -> HomeState.Error(i18n(result.message()))
            }
    }

    /** Initial load: show cache immediately, then refresh once so message badge is current. */
    LaunchedEffect(Unit) {
        val cached = forumRepository.getCachedHomePage()
        if (cached != null) {
            state = HomeState.Success(cached)
            when (val result = forumRepository.fetchHomePage()) {
                is YamiboResult.Success -> state = HomeState.Success(result.value)
                else -> Unit
            }
        } else {
            mapFetchResultState()
        }
    }

    /** Event listening */
    LaunchedEffect(Unit) {
        AppEventBus.events.collect { event ->
            if (event == LoginSuccessEvent) {
                isRefreshing = true
                val result = forumRepository.fetchHomePage()
                if (result is YamiboResult.Success) {
                    state = HomeState.Success(result.value)
                }
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(state) {
        val page = (state as? HomeState.Success)?.page ?: return@LaunchedEffect
        onNewMessageStatusChange(page.hasNewMessage)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.creamBackground)) {
        when (val currentState = state) {
            is HomeState.Loading -> LoadingSkeleton()
            is HomeState.Error ->
                ErrorContent(
                    message = currentState.message,
                    onRetry = {
                        state = HomeState.Loading
                        scope.launch { mapFetchResultState() }
                    }
                )

            is HomeState.Success ->
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        scope.launch {
                            when (val result = forumRepository.fetchHomePage()) {
                                is YamiboResult.Success ->
                                    state = HomeState.Success(result.value)

                                else -> {
                                    feedbackController.post(
                                        message = i18n(result.message()),
                                        duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                                    )
                                }
                            }
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    HomeContent(homePage = currentState.page, onSearch = { navigator.navigate(ISearchScreen()) })
                }
        }

    }
}

/** Success Content */
@Composable
private fun HomeContent(homePage: HomePage, onSearch: () -> Unit) {
    val appSettingsRepo = LocalAppSettingsRepository.current
    val showSwiperImages = appSettingsRepo.showHomeSwiperImages.state()

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        /** header banner */
        item { HomeHeader(onSearch = onSearch) }

        if (showSwiperImages && homePage.swiperImages.isNotEmpty()) {
            item(key = "home_swiper") {
                HomeSwiper(images = homePage.swiperImages)
            }
        }

        /** categories */
        homePage.categories.forEachIndexed { index, category ->
            item(key = "cat_${category.title}") {
                CategorySection(category = category, initialExpanded = index < 3)
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun HomeSwiper(images: List<SwiperImages>) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { images.size })
    val page = pagerState.currentPage.coerceIn(0, images.lastIndex)
    val current = images.getOrNull(page) ?: return

    LaunchedEffect(images, pagerState) {
        if (images.size <= 1) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(3_000.milliseconds)
            if (!pagerState.isScrollInProgress) {
                val nextPage = (pagerState.currentPage + 1) % images.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.63f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.creamSurface)
                .clickable(
                    enabled = current.tId != null,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    current.tId?.let { tid ->
                        navigator.navigate(IThreadReaderScreen(tid = tid, title = i18n("首頁輪播圖")))
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val target = images.getOrNull(index) ?: current
                key(target.imageUrl) {
                    SubcomposeAsyncImage(
                        model = rememberImageRequest(target.imageUrl),
                        contentDescription = i18n("首頁輪播圖"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shimmer(0f, colors.brownLight),
                            )
                        },
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(colors.brownLight.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = i18n("圖片載入失敗"),
                                    color = colors.textDark.copy(alpha = 0.62f),
                                    fontSize = 13.sp,
                                )
                            }
                        },
                    )
                }
            }

            if (images.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    images.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(width = if (index == page) 18.dp else 6.dp, height = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (index == page) Color.White else Color.White.copy(alpha = 0.55f)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Header Banner */
@Composable
private fun HomeHeader(onSearch: () -> Unit) {
    YamiboHomeTopBar(
        logo = {
            Image(
                painter = painterResource(Res.drawable.logo_homepage),
                contentDescription = "logo_homepage",
                contentScale = ContentScale.FillHeight,
                modifier = Modifier.height(36.dp).offset(y = 2.dp)
            )
        },
    ) {
        IconButton(onClick = onSearch, modifier = Modifier.offset(y = 11.dp)) {
            Icon(
                imageVector = YamiboIcons.Search,
                contentDescription = i18n("搜尋"),
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

/** Category Section (collapsible) */
@Composable
private fun CategorySection(category: ForumCategory, initialExpanded: Boolean = true) {
    val colors = YamiboTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        /** category header bar */
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            onClick = { expanded = !expanded }
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors =
                                    listOf(
                                        colors.brownDeep,
                                        colors.brownPrimary.copy(
                                            alpha = 0.85f
                                        )
                                    )
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = category.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    val rotation by
                    animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = tween(300),
                        label = "chevron_rotation"
                    )
                    Text(
                        text = "▼",
                        modifier = Modifier.graphicsLayer { rotationZ = rotation },
                        color = colors.orangeAccent,
                        fontSize = 14.sp
                    )
                }
            }
        }

        /** forums list */
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
        ) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp)
            ) { category.forums.forEach { forum -> ForumCard(forum = forum) } }
        }
    }
}

/** Forum Card */
@Composable
private fun ForumCard(forum: ForumSummary) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by
    animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(150)
    )

    Card(
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 4.dp).scale(scale).clickable(
                interactionSource = interactionSource,
                indication = null
            ) { navigator.navigate(IForumScreen(forum.fid, forum.name)) },
        shape = RoundedCornerShape(16.dp),
        elevation =
            CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /** left accent bar */
            Box(
                modifier =
                    Modifier.width(4.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.orangeAccent, colors.brownPrimary)
                            )
                        )
            )
            Spacer(Modifier.width(14.dp))

            /** text content */
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = forum.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!forum.description.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = forum.description!!,
                        fontSize = 12.sp,
                        color = colors.brownPrimary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            /** today count badge */
            val today = forum.todayCount ?: 0
            if (today > 0) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.orangeAccent.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = i18n("今日 {}", today),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.brownDeep
                    )
                }
            }
        }
    }
}

/** Loading Skeleton */
@Composable
private fun LoadingSkeleton() {
    val colors = YamiboTheme.colors
    val shimmerColor = colors.brownLight

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val shimmerAnim = rememberInfiniteTransition(label = "shimmer")
        val shimmerX by
        shimmerAnim.animateFloat(
            initialValue = -widthPx,
            targetValue = widthPx * 2f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "shimmer_x"
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(colors.creamBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            /** header placeholder */
            item {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .shimmer(shimmerX, shimmerColor)
                )
            }

            /** category skeletons */
            items(4) {
                Column {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .shimmer(shimmerX, shimmerColor)
                    )
                    Spacer(Modifier.height(8.dp))
                    repeat(3) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(60.dp)
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .shimmer(shimmerX, shimmerColor)
                        )
                    }
                }
            }
        }
    }
}

/** Shimmer modifier */
private fun Modifier.shimmer(translateX: Float, baseColor: Color): Modifier =
    this.drawBehind {
        val brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        baseColor.copy(alpha = 0.25f),
                        baseColor.copy(alpha = 0.50f),
                        baseColor.copy(alpha = 0.25f),
                    ),
                start = Offset(translateX, 0f),
                end = Offset(translateX + size.width, size.height)
            )
        drawRect(brush)
    }

/** Error Content */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    val colors = YamiboTheme.colors
    YamiboDetailedErrorContent(
        message = message,
        onRetry = onRetry,
        titleColor = colors.brownDeep,
        retryContentColor = Color.White,
        retryHorizontalPadding = 32.dp,
    )
}
