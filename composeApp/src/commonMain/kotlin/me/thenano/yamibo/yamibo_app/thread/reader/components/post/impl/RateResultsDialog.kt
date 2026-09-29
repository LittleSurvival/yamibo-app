package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import androidx.compose.foundation.layout.Arrangement
import me.thenano.yamibo.yamibo_app.components.controls.AppLazyColumn as LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n

@Composable
internal fun RateResultsDialog(
    onLoad: suspend () -> YamiboResult<RateResultPopoutPage>,
    onDismiss: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var page by remember { mutableStateOf<RateResultPopoutPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(onLoad) {
        when (val result = onLoad()) {
            is YamiboResult.Success -> page = result.value
            else -> error = i18n(result.message())
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = i18n("全部評分"),
                color = colors.textStrong,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 520.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.brownPrimary)
                    }

                    error != null -> Text(
                        text = error.orEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        color = MaterialTheme.colorScheme.error,
                    )

                    page != null -> {
                        val result = page!!
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(i18n("參與人數 {}", result.rates.size), color = colors.textDark, fontSize = 13.sp)
                            result.totalScore?.let {
                                Text("${i18n("積分")} ${if (it >= 0) "+" else ""}$it", color = colors.redAccent, fontSize = 13.sp)
                            }
                            Text(i18n("理由"), color = colors.textDark, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = colors.brownLight.copy(alpha = 0.5f))
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(result.rates) { rate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = rate.userName,
                                        modifier = Modifier.weight(0.42f),
                                        color = colors.textDark.copy(alpha = 0.7f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${if (rate.score >= 0) "+" else ""}${rate.score}",
                                        modifier = Modifier.weight(0.18f),
                                        color = colors.redAccent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = rate.reason.orEmpty(),
                                        modifier = Modifier.weight(0.4f),
                                        color = colors.textDark.copy(alpha = 0.7f),
                                        fontSize = 13.sp,
                                    )
                                }
                                HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(i18n("關閉"), color = colors.brownPrimary) }
        },
        containerColor = colors.creamSurface,
    )
}
