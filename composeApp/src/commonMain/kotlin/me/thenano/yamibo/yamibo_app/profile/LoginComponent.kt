package me.thenano.yamibo.yamibo_app.profile

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import io.github.littlesurvival.YamiboLevels
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.event.AppEventBus
import me.thenano.yamibo.yamibo_app.event.events.LoginSuccessEvent
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.*
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import me.thenano.yamibo.yamibo_app.webview.PlatformWebViewScreen

@RestorableScreenEntry
class ILoginScreen : RestorableNavigatable {
    override val id: String = buildId("login")
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = emptyRestoreSnapshot(restoreDecoder)

    @Composable
    override fun Content() {
        LoginScreen()
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<ILoginScreen>(ILoginScreen::class) {
        override fun decode(payload: String): RestorableNavigatable = ILoginScreen()
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun LoginScreen() {
    val navigator = LocalNavigator.current
    val authRepo = LocalAuthRepository.current

    PlatformWebViewScreen(
        initialUrl = YamiboRoute.Login.build(),
        initialTitle = i18n("登入頁面"),
        showNavigation = false,
        useBackIcon = true,
        onPageFinished = { authRepo.syncCookieFromWebView() },
    )

    LaunchedEffect(Unit) {
        authRepo.startLoginDetect(
            onSuccess = {
                val status = authRepo.fetchStatus()
                if (status is YamiboResult.Success) {
                    AppEventBus.emit(LoginSuccessEvent)
                }
                navigator.pop()
            },
            onTimeOut = {
                navigator.pop()
            }
        )
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun UserProfileCard(
    userInfo: ProfilePage? = UserStore.Preview,
    isLoading: Boolean = false,
    onRefresh: suspend () -> Unit = {},
    onLogout: suspend () -> Unit = {},
    @Suppress("ModifierParameter") modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = colors.creamSurface,
                contentColor = colors.textDark
            ),
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.1f))
    ) {
        Box {
            AnimatedContent(
                targetState = userInfo,
                transitionSpec = {
                    (fadeIn(tween(400)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(400)))
                        .togetherWith(fadeOut(tween(200)) + scaleOut(targetScale = 1.05f))
                },
                label = "user_profile_transition"
            ) { state ->
                if (state == null) {
                    LoginContent()
                } else {
                    UserInfoContent(state, { onRefresh() }, { onLogout() })
                }
            }
            /**
             * IMPORTANT: Use fully-qualified androidx.compose.animation.AnimatedVisibility.
             *
             * If import is auto-optimized or replaced with implicit receiver version, Kotlin may
             * resolve to a LayoutScope extension overload and fail with: "cannot be called in this
             * context with an implicit receiver".
             *
             * @suppress Do NOT clean up this path.
             */
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.matchParentSize()
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(colors.creamSurface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = colors.orangeAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

/** Not Logged In */
@Composable
private fun LoginContent() {
    val navigator = LocalNavigator.current
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        AnimatedYamiboButton(
            text = i18n("登入"),
            onClick = { navigator.navigate(ILoginScreen()) },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
    }
}

/** Logged In */
@Composable
private fun UserInfoContent(
    user: ProfilePage,
    onRefresh: suspend () -> Unit,
    onLogout: suspend () -> Unit,
) {
    val navigator = LocalNavigator.current
    val colors = YamiboTheme.colors
    val currentLevel = YamiboLevels.getLevel(user.totalPoints)
    val nextLevel = YamiboLevels.nextLevel(user.totalPoints)
    val targetPoint = nextLevel?.lowestPoint
    val progress = when {
        targetPoint == null -> 1f
        targetPoint <= 0 -> 0f
        else -> (user.totalPoints.toFloat() / targetPoint.toFloat()).coerceIn(0f, 1f)
    }
    val pointsToNext = YamiboLevels.pointToNextLevel(user.totalPoints)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .profilePressScaleClickable(pressedScale = 0.985f) {
                navigator.navigate(IUserSpaceScreen(user.uid, user.username))
            }
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedYamiboChip(
                label = i18n("刷新"),
                onClick = { onRefresh() },
                containerColor = YamiboTheme.colors.brownPrimary.copy(alpha = 0.1f),
                contentColor = YamiboTheme.colors.textOnSurface
            )
            Spacer(Modifier.width(12.dp))
            AnimatedYamiboChip(
                label = i18n("登出"),
                onClick = { onLogout() },
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarPlaceholder(
                avatarUrl = user.avatarUrl ?: "",
                modifier = Modifier.size(72.dp)
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "UID: ${user.uid.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = YamiboTheme.colors.brownPrimary
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoChip(
                        label = user.userGroup.ifBlank { currentLevel.levelName },
                        modifier = Modifier.weight(0.85f, fill = false)
                    )
                    InfoChip(
                        label = i18n(
                            "總積分 {}",
                            user.totalPoints.toString(),
                        ),
                        modifier = Modifier.weight(1.45f, fill = false)
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        LevelProgressBar(
            progress = progress,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = targetPoint?.let {
                    i18n("{} / {} 積分", user.totalPoints.toString(), it.toString())
                } ?: i18n("{} 積分", user.totalPoints.toString()),
                color = colors.textDark,
                fontSize = 10.sp,
            )
            pointsToNext?.let { remaining ->
                Spacer(Modifier.width(12.dp))
                Text(
                    text = i18n(
                        "距離{}還有 {} 積分",
                        nextLevel?.levelName ?: i18n("下一等級"),
                        remaining.toString()
                    ),
                    color = colors.textDark.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LevelProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(10.dp)
            .clip(shape)
            .background(colors.brownPrimary.copy(alpha = 0.08f)),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .padding(2.dp)
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            colors.orangeAccent.copy(alpha = 0.46f),
                            colors.orangeAccent.copy(alpha = 0.78f)
                        )
                    )
                )
        )
    }
}

/** Avatar */
@Composable
private fun AvatarPlaceholder(
    avatarUrl: String,
    modifier: Modifier = Modifier,
) {
    SubcomposeAsyncImage(
        model = rememberImageRequest(url = avatarUrl),
        contentDescription = null,
        modifier = modifier.clip(CircleShape),
        contentScale = ContentScale.Crop,
        loading = {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        error = {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    )
}

@Composable
private fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = YamiboTheme.colors.orangeAccent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, YamiboTheme.colors.orangeAccent.copy(alpha = 0.34f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 12.sp,
            color = YamiboTheme.colors.textOnSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AnimatedYamiboButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by
    animateFloatAsState(
        targetValue = if (isPressed) 0.94f else if (isHovered) 1.02f else 1f,
        animationSpec = tween(durationMillis = 150)
    )

    val backgroundColor = YamiboTheme.colors.brownDeep
    val contentColor = Color.White

    Surface(
        onClick = onClick,
        modifier =
            modifier.scale(scale),
        shape = RoundedCornerShape(50),
        color = backgroundColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun AnimatedYamiboChip(
    label: String,
    onClick: suspend () -> Unit,
    containerColor: Color,
    contentColor: Color
) {
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by
    animateFloatAsState(
        targetValue = if (isPressed) 0.92f else if (isHovered) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150)
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier =
            Modifier.scale(scale).clickable(
                interactionSource = interactionSource,
                indication = null
            ) { scope.launch { onClick() } }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
