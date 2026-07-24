package me.thenano.yamibo.yamibo_app.profile.settings.access

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalBackgroundAccessRepository
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator

@Composable
internal fun BackgroundAccessSetupScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalBackgroundAccessRepository.current
    val state by repository.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val notificationPermissionRequester = rememberBackgroundAccessNotificationPermissionRequester {
        coroutineScope.launch {
            repository.refresh()
        }
    }

    LaunchedEffect(Unit) {
        repository.refresh()
    }
    BackgroundAccessResumeRefreshEffect {
        coroutineScope.launch {
            repository.refresh()
        }
    }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("通知與背景同步"),
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
            Text(
                text = state.summary.localized(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textDark,
            )
            if (state.platformNote != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.platformNote?.localized().orEmpty(),
                    fontSize = 13.sp,
                    color = colors.textDark.copy(alpha = 0.7f),
                    lineHeight = 20.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { coroutineScope.launch { repository.refresh() } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brownPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(i18n("重新檢查"))
            }

            Spacer(Modifier.height(24.dp))

            state.items.forEachIndexed { index, item ->
                BackgroundAccessItemCard(
                    item = item,
                    onAction = { action ->
                        when (action) {
                            BackgroundAccessRepository.SetupAction.RequestNotificationPermission -> {
                                notificationPermissionRequester?.invoke()
                            }
                            else -> repository.runAction(action)
                        }
                    },
                )
                if (index != state.items.lastIndex) {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun BackgroundAccessItemCard(
    item: BackgroundAccessRepository.SetupItem,
    onAction: (BackgroundAccessRepository.SetupAction) -> Unit,
) {
    val colors = YamiboTheme.colors
    val (statusText, statusColor) = when (item.status) {
        BackgroundAccessRepository.SetupStatus.Granted -> i18n("已就緒") to colors.brownPrimary
        BackgroundAccessRepository.SetupStatus.Required -> i18n("必須處理") to Color(0xFFB4573B)
        BackgroundAccessRepository.SetupStatus.Recommended -> i18n("建議處理") to Color(0xFF8A6A2C)
        BackgroundAccessRepository.SetupStatus.Info -> i18n("說明") to colors.textDark.copy(alpha = 0.55f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.creamSurface, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title.localized(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textDark,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
            }
        }

        Text(
            text = item.subtitle.localized(),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.72f),
            lineHeight = 20.sp,
        )

        if (item.action != null && item.actionLabel != null) {
            Button(
                onClick = { onAction(item.action) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brownDeep,
                    contentColor = Color.White,
                ),
            ) {
                Text(item.actionLabel.localized())
            }
        }
    }
}

private fun BackgroundAccessRepository.I18nText.localized(): String =
    i18n(source, *args.toTypedArray())
