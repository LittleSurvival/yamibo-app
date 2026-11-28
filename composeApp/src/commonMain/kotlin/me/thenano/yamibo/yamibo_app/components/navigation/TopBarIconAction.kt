package me.thenano.yamibo.yamibo_app.components.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Standard icon action for [YamiboTopBar].
 *
 * Use this inside the `actions` slot when a header action maps to a single
 * icon, for example edit, home, search, delete, or overflow. It keeps touch
 * target and icon size aligned across top bars.
 *
 * @param icon Icon vector to draw.
 * @param contentDescription Accessibility label for the action.
 * @param onClick Action invoked when the icon is tapped.
 * @param iconSize Visual icon size in dp. The touch target remains 36dp.
 * @param iconModifier Modifier applied to the artwork inside the button.
 */
@Composable
fun YamiboTopBarIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    iconSize: Int = 24,
    iconModifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp).then(iconModifier),
        )
    }
}
