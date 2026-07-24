package me.thenano.yamibo.yamibo_app.updates.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateStatusCard
import me.thenano.yamibo.yamibo_app.favorite.updates.snapshotOrNull
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteUpdateInterval

@Composable
internal fun FavoriteUpdateHeader(
    runState: FavoriteUpdateRepository.RunState,
    onGlobalFavoriteUpdate: () -> Unit,
    onCancelFavoriteUpdate: (String) -> Unit,
    onInterruptFavoriteUpdate: (String) -> Unit,
    onResumeFavoriteUpdate: () -> Unit,
    favoriteUpdateInterval: FavoriteUpdateInterval,
    onFavoriteUpdateIntervalChange: (FavoriteUpdateInterval) -> Unit,
    favoriteUpdateHiddenRunId: String,
    onHideFavoriteUpdateStatus: (String) -> Unit,
    resultFilterActive: Boolean,
    resultFilterLabel: String,
    onShowResultFilter: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var showIntervalDialog by remember { mutableStateOf(false) }
    val running = (runState as? FavoriteUpdateRepository.RunState.Running)?.snapshot
    val interrupted = (runState as? FavoriteUpdateRepository.RunState.Interrupted)?.snapshot
    val snapshot = runState.snapshotOrNull()
    val statusVisible = snapshot != null &&
        (runState is FavoriteUpdateRepository.RunState.Running || favoriteUpdateHiddenRunId != snapshot.runId)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(i18n("收藏更新"), color = colors.textStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                YamiboActionChip(
                    i18n("結果: {}", resultFilterLabel),
                    onClick = onShowResultFilter,
                    selected = resultFilterActive,
                )
                YamiboActionChip(i18n("刷新週期: {}", favoriteUpdateInterval.localizedLabel()), onClick = { showIntervalDialog = true })
                when {
                    interrupted != null -> YamiboActionChip(i18n("繼續"), onClick = onResumeFavoriteUpdate)
                    running == null -> YamiboActionChip(i18n("全域刷新"), onClick = onGlobalFavoriteUpdate)
                }
            }
        }

        if (statusVisible) {
            FavoriteUpdateStatusCard(
                state = runState,
                modifier = Modifier.fillMaxWidth(),
                onCancel = onCancelFavoriteUpdate,
                onInterrupt = onInterruptFavoriteUpdate,
                onResume = onResumeFavoriteUpdate,
                onHide = { onHideFavoriteUpdateStatus(snapshot.runId) },
            )
        }
    }

    if (showIntervalDialog) {
        FavoriteUpdateIntervalDialog(
            selected = favoriteUpdateInterval,
            onDismiss = { showIntervalDialog = false },
            onSelect = onFavoriteUpdateIntervalChange,
        )
    }
}

@Composable
private fun FavoriteUpdateIntervalDialog(
    selected: FavoriteUpdateInterval,
    onDismiss: () -> Unit,
    onSelect: (FavoriteUpdateInterval) -> Unit,
) {
    YamiboSingleSelectDialog(
        title = i18n("刷新週期"),
        options = FavoriteUpdateInterval.entries,
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        label = { it.localizedLabel() },
        dismissOnSelect = true,
    )
}
