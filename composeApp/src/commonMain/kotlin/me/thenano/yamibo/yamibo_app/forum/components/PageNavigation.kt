package me.thenano.yamibo.yamibo_app.forum.components

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.littlesurvival.dto.model.PageNav
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/** Page Navigation bar with prev/next and popup page picker */
@Composable
fun PageNavigation(pageNav: PageNav, onPageChange: (Int) -> Unit) {
    val colors = YamiboTheme.colors
    val current = pageNav.currentPage ?: 1
    val total = pageNav.totalPages ?: 1
    var showPagePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /** prev button */
            PageButton(
                text = i18n("上一頁"),
                enabled = pageNav.prevUrl != null,
                onClick = { if (current > 1) onPageChange(current - 1) }
            )

            /** current page display / picker trigger */
            Surface(
                onClick = { showPagePicker = true },
                shape = RoundedCornerShape(12.dp),
                color = colors.brownDeep,
            ) {
                Text(
                    text = i18n("第{}頁", current),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = colors.textOnDeepHigh,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            /** next button */
            PageButton(
                text = i18n("下一頁"),
                enabled = pageNav.nextUrl != null,
                onClick = { if (current < total) onPageChange(current + 1) }
            )
        }
    }

    /** Popup page picker dialog */
    if (showPagePicker) {
        PagePickerDialog(
            currentPage = current,
            totalPages = total,
            onPageSelected = { page ->
                //supress just for ignore ide clean up code warning, it's work fine.
                showPagePicker = false
                onPageChange(page)
            },
            onDismiss = {
                showPagePicker = false
            }
        )
    }
}

/** Prev/Next button */
@Composable
private fun PageButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) colors.creamSurface else colors.brownLight.copy(alpha = 0.3f),
        shadowElevation = if (enabled) 2.dp else 0.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (enabled) colors.textOnSurface else colors.brownLight,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

/** Dialog popup for page selection */
@Composable
internal fun PagePickerDialog(
    currentPage: Int,
    totalPages: Int,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = YamiboTheme.colors
    var pageInput by remember(currentPage, totalPages) { mutableStateOf(currentPage.toString()) }
    val nearbyPages = remember(currentPage, totalPages) {
        buildList {
            add(1)
            for (page in (currentPage - 2)..(currentPage + 2)) {
                if (page in 1..totalPages) add(page)
            }
            add(totalPages)
        }.distinct().sorted()
    }

    fun submitInput() {
        val page = pageInput.toIntOrNull()?.coerceIn(1, totalPages) ?: return
        onPageSelected(page)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = i18n("選擇頁面"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textDark
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    text = i18n("目前第 {} / {} 頁", currentPage, totalPages),
                    color = colors.textDark.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    nearbyPages.forEachIndexed { index, page ->
                        if (index > 0 && page - nearbyPages[index - 1] > 1) {
                            Text(
                                text = "...",
                                modifier = Modifier.weight(0.6f),
                                textAlign = TextAlign.Center,
                                color = colors.textDark.copy(alpha = 0.45f),
                            )
                        }
                        PageNumberButton(
                            page = page,
                            active = page == currentPage,
                            onPageSelected = onPageSelected,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { value ->
                        pageInput = value.filter(Char::isDigit).take(6)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(i18n("輸入頁碼")) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submitInput() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textDark,
                        unfocusedTextColor = colors.textDark,
                        cursorColor = colors.brownDeep,
                        focusedBorderColor = colors.brownDeep,
                        unfocusedBorderColor = colors.brownPrimary.copy(alpha = 0.35f),
                        focusedLabelColor = colors.brownDeep,
                        unfocusedLabelColor = colors.brownPrimary.copy(alpha = 0.7f),
                        focusedContainerColor = colors.creamSurface,
                        unfocusedContainerColor = colors.creamSurface,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = i18n("取消"), color = colors.brownPrimary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = ::submitInput,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.brownDeep),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(i18n("跳轉"), color = colors.textOnDeepHigh)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageNumberButton(
    page: Int,
    active: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = { onPageSelected(page) },
        shape = RoundedCornerShape(10.dp),
        color = if (active) colors.brownDeep else colors.creamBackground,
        modifier = modifier,
    ) {
        Text(
            text = "$page",
            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = if (active) colors.textOnDeepHigh else colors.textDark,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}
