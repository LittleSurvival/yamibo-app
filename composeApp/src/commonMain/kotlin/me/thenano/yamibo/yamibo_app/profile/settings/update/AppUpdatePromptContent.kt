package me.thenano.yamibo.yamibo_app.profile.settings.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.controls.YamiboVerticalScrollbar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.appupdate.AppUpdateRelease
import me.thenano.yamibo.yamibo_app.repository.appupdate.changelogContent
import me.thenano.yamibo.yamibo_app.repository.appupdate.fullVersionName
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.logo_about

@Composable
internal fun AppUpdatePromptContent(
    release: AppUpdateRelease,
    onPrimaryClick: () -> Unit,
    onManualClick: () -> Unit,
    onLaterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.creamSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = i18n("發現新版本"),
                color = colors.textStrong,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = release.fullVersionName(),
                color = colors.textDark.copy(alpha = 0.72f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Image(
                painter = painterResource(Res.drawable.logo_about),
                contentDescription = null,
                modifier = Modifier
                    .width(270.dp)
                    .height(76.dp),
                contentScale = ContentScale.Fit,
            )
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = release.changelogContent().ifBlank {
                            i18n("新版已可下載。你可以立即下載更新，或前往發布頁手動更新。")
                        },
                        color = colors.textDark.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                YamiboVerticalScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                )
            }
            Button(
                onClick = onPrimaryClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brownDeep,
                    contentColor = colors.textOnDeep,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(i18n("立即更新"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onManualClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.creamBackground,
                        contentColor = colors.textStrong,
                    ),
                    border = BorderStroke(1.dp, colors.brownLight.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(i18n("手動更新"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onLaterClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.creamBackground,
                        contentColor = colors.textDark,
                    ),
                    border = BorderStroke(1.dp, colors.brownLight.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(i18n("稍後"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
