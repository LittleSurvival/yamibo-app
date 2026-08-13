package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.profile.settings.backup.SmallBackupButton

internal enum class CloudAuthMode { Login, Register }

/** 网盘登录/注册对话框，供「本地资料备份」与「云端同步」页共用。 */
@Composable
internal fun CloudLoginDialog(
    working: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (username: String, password: String, mode: CloudAuthMode) -> Unit,
) {
    val colors = YamiboTheme.colors
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CloudAuthMode.Login) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamBackground,
        titleContentColor = colors.textDark,
        title = { Text(i18n("登入網盤帳戶"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    label = { Text(i18n("帳號")) },
                    enabled = !working,
                    colors = cloudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(i18n("密碼")) },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !working,
                    colors = cloudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = i18n("沒有帳戶？"),
                        fontSize = 12.sp,
                        color = colors.textDark.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallBackupButton(
                        text = if (mode == CloudAuthMode.Login) i18n("改用註冊") else i18n("改用登入"),
                        onClick = {
                            mode = if (mode == CloudAuthMode.Login) {
                                CloudAuthMode.Register
                            } else {
                                CloudAuthMode.Login
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            SmallBackupButton(
                text = if (working) {
                    i18n("處理中...")
                } else if (mode == CloudAuthMode.Login) {
                    i18n("登入")
                } else {
                    i18n("註冊")
                },
                onClick = { onSubmit(username, password, mode) },
            )
        },
        dismissButton = {
            SmallBackupButton(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

@Composable
private fun cloudTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = YamiboTheme.colors.brownDeep,
    unfocusedBorderColor = YamiboTheme.colors.brownPrimary.copy(alpha = 0.35f),
    focusedLabelColor = YamiboTheme.colors.brownDeep,
    cursorColor = YamiboTheme.colors.brownDeep,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
