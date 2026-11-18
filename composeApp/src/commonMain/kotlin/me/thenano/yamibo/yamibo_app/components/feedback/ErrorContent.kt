package me.thenano.yamibo.yamibo_app.components.feedback

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Standard page-level error card with a retry affordance.
 *
 * Use this when a full page cannot load and the user should be able to retry
 * the same fetch. For inline failures or per-card failures, prefer a smaller
 * feature-local component.
 *
 * @param message Error text to show under the title.
 * @param onRetry Called when the retry button is tapped.
 * @param modifier Root modifier, usually `Modifier.fillMaxSize()`.
 */
@Composable
fun YamiboErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    YamiboDetailedErrorContent(
        message = message,
        onRetry = onRetry,
        titleColor = colors.textStrong,
        retryContentColor = colors.textOnDeepHigh,
        retryHorizontalPadding = 22.dp,
        modifier = modifier,
    )
}

@Composable
internal fun YamiboDetailedErrorContent(
    message: String,
    onRetry: () -> Unit,
    titleColor: Color,
    retryContentColor: Color,
    retryHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val resolved = forumErrorMessage(message)
    val presentation = remember(resolved) { errorPresentation(resolved) }
    var expanded by remember(resolved) { mutableStateOf(false) }
    var copied by remember(resolved) { mutableStateOf(false) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val summary = when (presentation.kind) {
        ErrorKind.Network -> i18n("暫時無法連線，請檢查網路設定後重試。")
        ErrorKind.Server -> i18n("伺服器暫時無法完成請求，請稍後重試。")
        ErrorKind.Technical -> i18n("載入失敗，請稍後重試；若持續發生，請回報診斷詳情。")
        ErrorKind.Message -> presentation.message
    }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(colors.creamBackground)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val availableHeight = maxHeight
        Card(
            modifier = Modifier.widthIn(max = 560.dp).heightIn(max = availableHeight),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(6.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Measure the action first; only the message/details may scroll or shrink.
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = i18n("載入失敗"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(summary, color = colors.brownPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                    presentation.diagnostics?.let { diagnostics ->
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) i18n("收起診斷詳情") else i18n("查看診斷詳情"),
                                color = colors.brownPrimary,
                            )
                        }
                        if (expanded) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(diagnostics))
                                copied = true
                            }) {
                                Text(
                                    if (copied) i18n("已複製診斷詳情") else i18n("複製診斷詳情"),
                                    color = colors.brownPrimary,
                                )
                            }
                            SelectionContainer {
                                Text(
                                    text = diagnostics,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colors.brownPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(50),
                    color = colors.brownDeep,
                    contentColor = retryContentColor,
                ) {
                    Text(
                        text = i18n("重試"),
                        modifier = Modifier.padding(horizontal = retryHorizontalPadding, vertical = 12.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
