package me.thenano.yamibo.yamibo_app.components.systembars

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemBarsTest {
    @Test
    fun blackWhiteAndMidtoneChooseReadableIcons() {
        assertTrue(useDarkSystemBarIcons(Color.White))
        assertFalse(useDarkSystemBarIcons(Color.Black))
        // The old luminance > 0.5 rule incorrectly chose white on this gray.
        assertTrue(useDarkSystemBarIcons(Color(0xFF888888)))
    }

    @Test
    fun everyThemeSurfaceUsesTheHigherContrastIcons() {
        for (theme in YamiboColorScheme.all) {
            for (value in listOf(theme.brownDeep, theme.creamBackground, theme.navBarBg)) {
                val color = Color(value)
                val blackContrast = (color.luminance() + 0.05f) / 0.05f
                val whiteContrast = 1.05f / (color.luminance() + 0.05f)
                val chosen = if (useDarkSystemBarIcons(color)) blackContrast else whiteContrast
                assertTrue(chosen >= 4.5f, theme.name)
                assertEquals(maxOf(blackContrast, whiteContrast), chosen, theme.name)
            }
        }
    }
}
