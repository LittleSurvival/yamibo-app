package me.thenano.yamibo.yamibo_app.profile.about

import YamiboIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.components.controls.YamiboVerticalScrollbar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.update.IAppUpdateScreen
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.ic_discord
import yamibo_app.composeapp.generated.resources.ic_github
import yamibo_app.composeapp.generated.resources.logo_about

private const val GitHubUrl = "https://github.com/LittleSurvival/yamibo-app"
private const val DiscordUrl = "https://discord.gg/3nhKpxM7Hc"
private const val QqUrl = "https://qm.qq.com/q/13i8doGGV0"

private data class CommunityLink(
    val title: String,
    val url: String,
    val icon: DrawableResource? = null,
    val imageVector: ImageVector? = null,
)

private val CommunityLinks = listOf(
    CommunityLink("Discord", DiscordUrl, Res.drawable.ic_discord),
    CommunityLink("QQ", QqUrl, imageVector = YamiboIcons.Qq),
    CommunityLink("GitHub", GitHubUrl, Res.drawable.ic_github),
)

@Composable
internal fun AboutScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    var showChangelog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.creamBackground),
    ) {
        YamiboTopBar(title = i18n("關於"), onBack = navigator::pop)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.logo_about),
                contentDescription = null,
                modifier = Modifier
                    .width(270.dp)
                    .height(76.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = AppVersion.displayName,
                color = colors.textDark.copy(alpha = 0.58f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = i18n("app dev by thenano"),
                color = colors.textDark.copy(alpha = 0.58f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))

            AboutSection {
                AboutInfoRow(
                    title = i18n("版本"),
                    value = AppVersion.displayName,
                )
                AboutDivider()
                AboutActionRow(
                    icon = YamiboIcons.Reload,
                    title = i18n("檢查更新"),
                    onClick = { navigator.navigate(IAppUpdateScreen()) },
                )
                AboutDivider()
                AboutActionRow(
                    icon = YamiboIcons.Book,
                    title = i18n("更新日誌"),
                    onClick = { showChangelog = true },
                )
            }

            Spacer(Modifier.height(14.dp))

            AboutSection {
                AboutHeaderRow(title = i18n("社群"))
                CommunityLinks.forEach { link ->
                    AboutDivider()
                    val imageVector = link.imageVector
                    if (imageVector != null) {
                        AboutActionRow(
                            icon = imageVector,
                            title = link.title,
                            onClick = { uriHandler.openUri(link.url) },
                        )
                    } else {
                        AboutResourceActionRow(
                            icon = painterResource(link.icon ?: return@forEach),
                            title = link.title,
                            onClick = { uriHandler.openUri(link.url) },
                        )
                    }
                }
            }
        }
    }

    if (showChangelog) {
        ChangelogDialog(onDismiss = { showChangelog = false })
    }
}

@Composable
private fun AboutSection(content: @Composable ColumnScope.() -> Unit) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun AboutHeaderRow(title: String) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, color = colors.textDark, fontSize = 15.sp)
    }
}

@Composable
private fun AboutInfoRow(
    title: String,
    value: String,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, color = colors.textDark, fontSize = 15.sp)
        Text(text = value, color = colors.textDark.copy(alpha = 0.58f), fontSize = 13.sp)
    }
}

@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AboutActionRowLayout(title = title, onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = colors.brownPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AboutResourceActionRow(
    icon: Painter,
    title: String,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AboutActionRowLayout(title = title, onClick = onClick) {
        Icon(
            painter = icon,
            contentDescription = title,
            tint = colors.brownPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AboutActionRowLayout(
    title: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(colors.creamBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = title,
            color = colors.textDark,
            fontSize = 15.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        )
        Text(">", color = colors.textDark.copy(alpha = 0.32f), fontSize = 14.sp)
    }
}

@Composable
private fun AboutDivider() {
    val colors = YamiboTheme.colors
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = colors.brownLight.copy(alpha = 0.15f),
    )
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    val colors = YamiboTheme.colors
    var changelog by remember { mutableStateOf<String?>(null) }
    var hasScrolledToBottom by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        changelog = loadCurrentChangelog()
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val v = scrollState.value
            val max = scrollState.maxValue
            val vp = scrollState.viewportSize
            vp > 0 && (max == 0 || v >= max)
        }.collect { reached ->
            if (reached) {
                hasScrolledToBottom = true
            }
        }
    }

    Dialog(
        onDismissRequest = { if (hasScrolledToBottom) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = hasScrolledToBottom,
            dismissOnClickOutside = hasScrolledToBottom
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = i18n("更新日誌"),
                    color = colors.textOnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val content = changelog
                        if (content == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = colors.brownPrimary,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = i18n("正在載入更新日誌..."),
                                    color = colors.textDark.copy(alpha = 0.68f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        } else {
                            ChangelogContent(content)
                        }
                    }
                    YamiboVerticalScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
                Button(
                    onClick = onDismiss,
                    enabled = hasScrolledToBottom,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brownDeep,
                        contentColor = colors.textOnDeep,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(i18n("關閉"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChangelogContent(text: String) {
    val colors = YamiboTheme.colors
    text.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            when {
                line.startsWith("#") -> Text(
                    text = line.trimStart('#').trim(),
                    color = colors.textOnSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                line.startsWith("-") -> Text(
                    text = "• ${line.removePrefix("-").trim()}",
                    color = colors.textDark.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                else -> Text(
                    text = line,
                    color = colors.textDark.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
}

private suspend fun loadCurrentChangelog(): String {
    val currentPath = "files/changelogs/${AppVersion.VersionCode}.changelog"
    val fallbackPath = "files/changelogs/1.changelog"
    return runCatching { Res.readBytes(currentPath).decodeToString() }
        .recoverCatching { Res.readBytes(fallbackPath).decodeToString() }
        .getOrElse { i18n("沒有找到目前版本的更新日誌。") }
}
