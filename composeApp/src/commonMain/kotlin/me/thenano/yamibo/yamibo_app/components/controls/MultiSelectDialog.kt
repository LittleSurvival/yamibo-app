package me.thenano.yamibo.yamibo_app.components.controls

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Standard Yamibo multi-select popout dialog.
 *
 * Use when the user can select multiple independent categories while keeping
 * the same visual language as [YamiboSingleSelectDialog]. Tapping an option
 * toggles it in-place; tapping outside the popout or pressing the confirm chip
 * commits the current temporary selection and closes the dialog.
 *
 * @param title Dialog title.
 * @param options Ordered option list to display.
 * @param selected Current selected values when the dialog opens.
 * @param onConfirm Receives the final selected values on outside dismiss or
 * confirm button.
 * @param onCancel Optional discard action. When supplied, a cancel chip is
 * shown to the right of the confirm chip.
 * @param label Converts an option to display text.
 * @param modifier Modifier applied to the option list.
 * @param selectedText Text shown at the right side of each selected row.
 * @param confirmText Text shown on the confirm chip.
 * @param optionEnabled Whether an option can be selected.
 * @param toggleSelection Returns the new temporary selection after an option
 * row is tapped. Use this to implement exclusive "All" rows.
 */
@Composable
fun <T> YamiboMultiSelectDialog(
    title: String,
    options: List<T>,
    selected: Set<T>,
    onConfirm: (Set<T>) -> Unit,
    onCancel: (() -> Unit)? = null,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    selectedText: String = i18n("已選擇"),
    confirmText: String = i18n("確定"),
    optionEnabled: (T) -> Boolean = { true },
    toggleSelection: (option: T, current: Set<T>) -> Set<T> = { option, current ->
        if (option in current) current - option else current + option
    },
) {
    val colors = YamiboTheme.colors
    fun enabledSelection(values: Set<T>): Set<T> = values.filterTo(mutableSetOf(), optionEnabled)
    var draftSelection by remember(options, selected) { mutableStateOf(enabledSelection(selected)) }
    AlertDialog(
        onDismissRequest = { onConfirm(enabledSelection(draftSelection)) },
        title = {
            Text(title, color = colors.textStrong, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(options) { option ->
                    val enabled = optionEnabled(option)
                    val isSelected = enabled && option in draftSelection
                    YamiboSingleSelectRow(
                        label = label(option),
                        selected = isSelected,
                        selectedText = selectedText,
                        onClick = {
                            if (enabled) {
                                draftSelection = enabledSelection(toggleSelection(option, draftSelection))
                            }
                        },
                        enabled = enabled,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onCancel != null) {
                    YamiboActionChip(i18n("取消"), onClick = onCancel)
                }
                YamiboActionChip(confirmText, onClick = { onConfirm(enabledSelection(draftSelection)) })
            }
        },
        dismissButton = {},
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
    )
}
