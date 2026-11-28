package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.value.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n

@Composable
internal fun SmartComicDownloadDialog(
    chapters: List<ThreadSummary>,
    current: ThreadId,
    complete: Boolean,
    onDismiss: () -> Unit,
    onDownload: suspend (List<ThreadSummary>) -> Unit,
) {
    val colors = YamiboTheme.colors
    val scope = rememberCoroutineScope()
    var selected by remember(current) { mutableStateOf(setOf(current)) }
    val downloads = LocalDownloadRepository.current
    val availability by produceState<Map<ThreadId, Boolean>?>(null, chapters) { value = downloads.chapterAvailability(chapters) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val actions = ButtonDefaults.textButtonColors(contentColor = colors.brownPrimary,
        disabledContentColor = colors.textDark.copy(alpha = 0.45f))
    val checkColors = CheckboxDefaults.colors(checkedColor = colors.brownPrimary, checkmarkColor = colors.creamSurface,
        uncheckedColor = colors.textDark, disabledCheckedColor = colors.brownPrimary.copy(alpha = 0.45f),
        disabledUncheckedColor = colors.textDark.copy(alpha = 0.45f))

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = colors.creamSurface, titleContentColor = colors.textStrong, textContentColor = colors.textDark,
        title = { Text(i18n("下載漫畫")) },
        text = {
            Column {
                Text(if (complete) i18n("作品目錄共 {} 話", chapters.size)
                    else i18n("目前找到 {} 話，目錄尚未確認完整", chapters.size))
                availability?.let { Text(i18n("可完整離線閱讀 {} 話", it.values.count { saved -> saved })) }
                Row {
                    TextButton({ selected = setOf(current) }, enabled = !busy, colors = actions) { Text(i18n("本話")) }
                    TextButton({ selected = chapters.mapTo(mutableSetOf()) { it.tid } }, enabled = !busy, colors = actions) {
                        Text(if (complete) i18n("整部作品") else i18n("選取已知章節"))
                    }
                }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.tid == current }.coerceAtLeast(0))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), state = listState) {
                    items(chapters, key = { it.tid.value }) { chapter ->
                        val checked = chapter.tid in selected
                        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, enabled = !busy) {
                            selected = if (it) selected + chapter.tid else selected - chapter.tid
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, enabled = !busy, colors = checkColors)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(chapter.title, color = colors.textStrong)
                                availability?.get(chapter.tid)?.let { saved ->
                                    Text(if (saved) i18n("已下載") else i18n("部分已下載"), color = colors.textDark)
                                }
                            }
                        }
                    }
                }
                failure?.let { Text(it, color = colors.textStrong) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty(), colors = actions, onClick = {
                val requested = chapters.filter { it.tid in selected }
                busy = true
                failure = null
                scope.launch {
                    try {
                        onDownload(requested)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error.message ?: i18n("加入下載佇列失敗")
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (failure == null) i18n("下載 {} 話", selected.size) else i18n("重試")) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy, colors = actions) { Text(i18n("取消")) } },
    )
}
