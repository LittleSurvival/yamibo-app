package me.thenano.yamibo.yamibo_app.components.controls

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.controls.AppLazyColumn as LazyColumn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Standard Yamibo single-select popout dialog.
 *
 * Use for app-wide one-of-many choices such as Favorite layout/sort, Forum
 * order/filter, update intervals, reading-history filters, and settings
 * selectors. It intentionally mirrors the Favorite page's "排列:" option
 * dialog: cream surface, rounded selected row, brown text, and an inline
 * selected marker instead of Material radio buttons.
 *
 * Pass nullable values when the first option is an app-side default, for
 * example `listOf(null) + apiOptions`, and render the null label in [label].
 * Set [dismissOnSelect] only for selectors where choosing an item should close
 * the popout immediately.
 *
 * @param title Dialog title.
 * @param options Ordered option list to display.
 * @param selected Current selected value.
 * @param onDismiss Called when the popout is dismissed.
 * @param onSelect Called when an option row is tapped.
 * @param label Converts an option to display text.
 * @param modifier Modifier applied to the option list.
 * @param dismissOnSelect Whether tapping an option also closes the dialog.
 * @param selectedText Text shown at the right side of the selected row.
 * @param footer Optional bottom action row; defaults to a close chip.
 */
@Composable
fun <T> YamiboSingleSelectDialog(
    title: String,
    options: List<T>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    dismissOnSelect: Boolean = false,
    selectedText: String = i18n("已選擇"),
    footer: @Composable (() -> Unit)? = {
        YamiboActionChip(i18n("關閉"), onDismiss)
    },
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = colors.textStrong, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(options) { option ->
                    YamiboSingleSelectRow(
                        label = label(option),
                        selected = option == selected,
                        selectedText = selectedText,
                        onClick = {
                            onSelect(option)
                            if (dismissOnSelect) onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (footer != null) {
                footer()
            }
        },
        dismissButton = {},
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
    )
}
