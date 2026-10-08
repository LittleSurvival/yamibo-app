package me.thenano.yamibo.yamibo_app.components.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.controls.YamiboPrimaryButton
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Bottom input bar for chat/comment style message submission.
 *
 * Use for PrivateMessage and other bottom-docked compose boxes. For a static
 * in-page textarea, use the same [YamiboPrimaryButton] but keep a feature-local
 * layout if the editor needs a fixed large height.
 *
 * @param value Current input value.
 * @param placeholder Hint text inside the field.
 * @param enabled Whether input and send button are available.
 * @param sending Whether a send request is in progress.
 * @param sendText Button label when not sending.
 * @param sendingText Button label while sending.
 * @param onValueChange Input change callback.
 * @param onSend Send action.
 */
@Composable
fun YamiboMessageInputBar(
    value: String,
    placeholder: String,
    enabled: Boolean,
    sending: Boolean,
    sendText: String,
    sendingText: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(color = colors.creamSurface, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder, fontSize = 14.sp) },
                maxLines = 4,
                singleLine = false,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textOnSurface,
                    unfocusedTextColor = colors.textOnSurface,
                    disabledTextColor = colors.textOnSurface.copy(alpha = 0.38f),
                    cursorColor = colors.brownDeep,
                    selectionColors = TextSelectionColors(
                        handleColor = colors.brownDeep,
                        backgroundColor = colors.brownPrimary.copy(alpha = 0.25f),
                    ),
                    focusedBorderColor = colors.brownDeep,
                    unfocusedBorderColor = colors.brownPrimary.copy(alpha = 0.35f),
                    disabledBorderColor = colors.brownPrimary.copy(alpha = 0.12f),
                    focusedPlaceholderColor = colors.textOnSurface.copy(alpha = 0.6f),
                    unfocusedPlaceholderColor = colors.textOnSurface.copy(alpha = 0.6f),
                    disabledPlaceholderColor = colors.textOnSurface.copy(alpha = 0.38f),
                    focusedContainerColor = colors.creamSurface,
                    unfocusedContainerColor = colors.creamSurface,
                    disabledContainerColor = colors.creamSurface,
                ),
            )
            YamiboPrimaryButton(
                text = sendText,
                busyText = sendingText,
                enabled = enabled,
                busy = sending,
                onClick = onSend,
            )
        }
    }
}
