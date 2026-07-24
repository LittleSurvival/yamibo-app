package me.thenano.yamibo.yamibo_app.thread.reader.components.tag

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.TagValue
import io.github.littlesurvival.dto.value.ThreadId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalTagRepository
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadErrorContent
import me.thenano.yamibo.yamibo_app.thread.detail.tag.ITagDetailScreen

internal sealed interface TagListState {
    data object Loading : TagListState
    data object Success : TagListState
    data class Error(val message: String) : TagListState
}

@Composable
internal fun TagListScreen(
    tid: ThreadId,
    initialTags: List<TagValue>
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val tagRepository = LocalTagRepository.current
    val scope = rememberCoroutineScope()

    var tags by remember { mutableStateOf(initialTags) }
    var state by remember { mutableStateOf<TagListState>(TagListState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }

    fun loadTags(forceRefresh: Boolean = false) {
        if (!forceRefresh) state = TagListState.Loading
        else isRefreshing = true

        scope.launch {
            when (val result = tagRepository.fetchExtractTags(tid)) {
                is YamiboResult.Success -> {
                    tags = result.value.value
                    state = TagListState.Success
                }
                else -> {
                    state = if (!forceRefresh) {
                        TagListState.Error(i18n(result.message()))
                    } else {
                        TagListState.Success // keep old tags if refresh fails but don't show error box
                    }
                }
            }
            if (forceRefresh) isRefreshing = false
        }
    }

    LaunchedEffect(tid) {
        loadTags()
    }

    Scaffold(
        containerColor = colors.creamBackground,
        topBar = {
            YamiboTopBar(
                title = i18n("標籤列表"),
                titleFontSize = 18,
                onBack = { navigator.pop() },
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (val currentState = state) {
                is TagListState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.brownPrimary)
                    }
                }
                is TagListState.Error -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ThreadErrorContent(
                            message = currentState.message,
                            onRetry = { loadTags() }
                        )
                    }
                }
                is TagListState.Success -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { loadTags(forceRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (tags.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    i18n("沒有找到標籤"),
                                    color = colors.textDark.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                Text(
                                    i18n("💡 tips : 標籤頁面類似常規漫畫App, 推薦用於收藏漫畫"),
                                    color = colors.textDark.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    tags.forEach { tag ->
                                        TagChip(
                                            tag = tag,
                                            onClick = {
                                                navigator.navigate(
                                                    ITagDetailScreen(
                                                        tagId = tag.id,
                                                        title = tag.name
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    tag: TagValue,
    onClick: () -> Unit
) {
    val colors = YamiboTheme.colors

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🏷️",
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = tag.name,
                color = colors.brownPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
