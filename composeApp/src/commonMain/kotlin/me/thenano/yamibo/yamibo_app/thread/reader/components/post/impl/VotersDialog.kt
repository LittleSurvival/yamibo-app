package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import me.thenano.yamibo.yamibo_app.LocalForumErrorMessageResolver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.PollOptionId
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboPageNavigation
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen

@Composable
internal fun VotersDialog(
    onLoad: suspend (PollOptionId?, Int) -> YamiboResult<VotersPopoutScreen>,
    onDismiss: () -> Unit,
) {
    val errorMessages = LocalForumErrorMessageResolver.current
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    var request by remember { mutableStateOf<Pair<PollOptionId?, Int>>(null to 1) }
    var screen by remember { mutableStateOf<VotersPopoutScreen?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectorExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        loading = true
        error = null
        when (val result = onLoad(request.first, request.second)) {
            is YamiboResult.Success -> screen = result.value
            else -> error = i18n(errorMessages.resolve(result))
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = i18n("參與投票的會員"),
                color = colors.textStrong,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 520.dp)) {
                val current = screen
                if (current != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            onClick = { selectorExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = colors.creamBackground,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = current.pollOptions.firstOrNull { it.id == current.selectedPollOptionId }?.name.orEmpty(),
                                    modifier = Modifier.weight(1f),
                                    color = colors.textDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("⌄", color = colors.textDark, fontSize = 18.sp)
                            }
                        }
                        DropdownMenu(
                            expanded = selectorExpanded,
                            onDismissRequest = { selectorExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.82f),
                            containerColor = colors.creamSurface,
                        ) {
                            current.pollOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.name,
                                            color = if (option.id == current.selectedPollOptionId) colors.textStrong else colors.textDark,
                                            fontWeight = if (option.id == current.selectedPollOptionId) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        selectorExpanded = false
                                        request = option.id to 1
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                when {
                    loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.brownPrimary)
                    }

                    error != null -> Text(
                        text = error.orEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        color = MaterialTheme.colorScheme.error,
                    )

                    screen != null -> {
                        val voters = screen!!.voters
                        if (voters.isEmpty()) {
                            Text(
                                text = i18n("此選項暫無投票參與人"),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                color = colors.textDark.copy(alpha = 0.6f),
                            )
                        } else {
                            val gridHeight = (((voters.size + 1) / 2) * 48).coerceAtMost(288).dp
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxWidth().height(gridHeight),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(voters, key = { it.uid.value }) { voter ->
                                    Surface(
                                        onClick = {
                                            onDismiss()
                                            navigator.navigate(IUserSpaceScreen(voter.uid, voter.name))
                                        },
                                        color = androidx.compose.ui.graphics.Color.Transparent,
                                    ) {
                                        Text(
                                            text = voter.name,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                            color = colors.brownPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        screen!!.pageNav?.let { nav ->
                            YamiboPageNavigation(
                                pageNav = nav,
                                currentPage = nav.currentPage ?: request.second,
                                onPageChange = { page -> request = screen!!.selectedPollOptionId to page },
                            )
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
