package me.thenano.yamibo.yamibo_app.profile.support

import YamiboIcons
import me.thenano.yamibo.yamibo_app.components.controls.appVerticalScroll as verticalScroll
import me.thenano.yamibo.yamibo_app.thread.image.platformImageAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.profilePressScaleClickable
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest

private data class SupportLink(
    val titleSource: String,
    val description: String,
    val url: String,
    val iconUrl: String,
)

private val SupportLinks = listOf(
    SupportLink(
        titleSource = "Ko-fi",
        description = "ko-fi.com/thenano",
        url = "https://ko-fi.com/thenano",
        iconUrl = "https://storage.ko-fi.com/cdn/kofi2.png?v=3",
    ),
    SupportLink(
        titleSource = "愛發電",
        description = "afdian.com/a/littlesurvival0001",
        url = "https://afdian.com/a/littlesurvival0001",
        iconUrl = "https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.jpg",
    ),
)

@Composable
internal fun SupportAppDevelopmentScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.creamBackground),
    ) {
        YamiboTopBar(title = i18n("支持App開發"), onBack = navigator::pop)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = YamiboIcons.Heart,
                            contentDescription = null,
                            tint = colors.brownPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = i18n("支持App開發"),
                            color = colors.brownDeep,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Text(
                        text = i18n("如果這個 app 對你有幫助，可以透過以下方式支持開發與維護。"),
                        color = colors.textDark.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            SupportLinks.forEach { link ->
                SupportLinkCard(
                    link = link,
                    onClick = { uriHandler.openUri(link.url) },
                )
            }
        }
    }
}

@Composable
private fun SupportLinkCard(
    link: SupportLink,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val title = i18n(link.titleSource)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .profilePressScaleClickable(pressedScale = 0.985f, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(colors.creamBackground, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(transform = ::platformImageAnimation,
                    model = rememberImageRequest(link.iconUrl),
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = colors.brownPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                color = colors.brownPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = link.description,
                    color = colors.textDark.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
            Text(
                text = ">",
                color = colors.textDark.copy(alpha = 0.32f),
                fontSize = 14.sp,
                modifier = Modifier.widthIn(min = 16.dp),
            )
        }
    }
}
