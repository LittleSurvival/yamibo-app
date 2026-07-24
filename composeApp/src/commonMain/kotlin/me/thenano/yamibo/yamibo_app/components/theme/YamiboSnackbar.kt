package me.thenano.yamibo.yamibo_app.components.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun snackbarMessageMaxHeight(availableHeight: Dp): Dp =
    minOf(availableHeight * 0.4f, 280.dp)

@Composable
fun YamiboSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors

    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            BoxWithConstraints {
                Snackbar(
                    containerColor = colors.brownDeep,
                    contentColor = Color.White,
                    actionContentColor = colors.orangeAccent,
                    dismissActionContentColor = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    action = data.visuals.actionLabel?.let { label ->
                        {
                            TextButton(onClick = data::performAction) {
                                Text(text = label, color = colors.orangeAccent)
                            }
                        }
                    },
                    dismissAction = if (data.visuals.withDismissAction) {
                        {
                            IconButton(
                                onClick = data::dismiss,
                                modifier = Modifier.semantics { contentDescription = "Dismiss" },
                            ) {
                                Text(text = "×", color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = data.visuals.message,
                        modifier = Modifier
                            .heightIn(max = snackbarMessageMaxHeight(maxHeight))
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    )
}
