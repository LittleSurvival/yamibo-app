package me.thenano.yamibo.yamibo_app.components.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class YamiboSnackbarTest {
    @Test
    fun compactWindowUsesFortyPercentOfAvailableHeight() {
        assertEquals(160.dp, snackbarMessageMaxHeight(400.dp))
    }

    @Test
    fun largeWindowCapsMessageRegionAtTwoHundredEightyDp() {
        assertEquals(280.dp, snackbarMessageMaxHeight(1_200.dp))
    }
}
