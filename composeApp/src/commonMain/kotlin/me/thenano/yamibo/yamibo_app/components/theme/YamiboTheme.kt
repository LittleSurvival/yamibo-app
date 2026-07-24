package me.thenano.yamibo.yamibo_app.components.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme
import me.thenano.yamibo.yamibo_app.util.state

/** Compose-layer color wrapper. Converts repo Long hex → Compose Color. */
@Immutable
data class YamiboColors(
    val brownDeep: Color,
    val brownPrimary: Color,
    val brownLight: Color,
    val creamBackground: Color,
    val creamSurface: Color,
    val orangeAccent: Color,
    val textDark: Color,
    val textStrong: Color,
    val textOnBackground: Color,
    val textOnSurface: Color,
    val textOnTint: Color,
    val textOnDeep: Color,
    val textOnDeepHigh: Color,
    val textOnPrimary: Color,
    val textOnAccent: Color,
    val htmlTextDark: Color,
    val redAccent: Color,
    val pinnedBg: Color,
    val announceBg: Color,
    val navBarBg: Color,
    val navBarIconSelected: Color,
    val navBarIconUnselected: Color,
)

/** Central theme object. Access colors via `YamiboTheme.colors`. */
object YamiboTheme {
    val colors: YamiboColors
        @Composable
        get() {
            val schemeEnum = LocalAppSettingsRepository.current.themeScheme.state()
            val scheme = schemeEnum.toScheme()
            val brownDeep = Color(scheme.brownDeep)
            val creamBackground = Color(scheme.creamBackground)
            val creamSurface = Color(scheme.creamSurface)
            val brownPrimary = Color(scheme.brownPrimary).ensureContrastOn(creamSurface, 3.0)
            val textDark = Color(scheme.textDark)
                .ensureContrastOn(creamBackground, 4.5)
                .ensureContrastOn(creamSurface, 4.5)
            val navBarBg = Color(scheme.navBarBg)
            val isDefault = scheme === YamiboColorScheme.Default
            
            return YamiboColors(
                brownDeep = brownDeep,
                brownPrimary = brownPrimary,
                brownLight = Color(scheme.brownLight),
                creamBackground = creamBackground,
                creamSurface = creamSurface,
                orangeAccent = Color(scheme.orangeAccent),
                textDark = textDark,
                textStrong = if (isDefault) {
                    brownDeep
                } else {
                    textDark
                },
                textOnBackground = if (isDefault) {
                    brownDeep
                } else {
                    textDark.bestReadableOn(creamBackground)
                },
                textOnSurface = if (isDefault) {
                    brownDeep
                } else {
                    textDark.bestReadableOn(creamSurface)
                },
                textOnTint = if (isDefault) {
                    brownDeep
                } else {
                    textDark
                },
                textOnDeep = if (isDefault) {
                    creamBackground
                } else {
                    textDark.bestReadableOn(brownDeep)
                },
                textOnDeepHigh = if (isDefault) {
                    Color.White
                } else {
                    textDark.bestReadableOn(brownDeep)
                },
                textOnPrimary = if (isDefault) {
                    Color.White
                } else {
                    textDark.bestReadableOn(brownPrimary)
                },
                textOnAccent = if (isDefault) {
                    Color.White
                } else {
                    textDark.bestReadableOn(Color(scheme.orangeAccent))
                },
                htmlTextDark = Color(scheme.htmlTextDark).ensureContrastOn(creamBackground, 4.5),
                redAccent = if (isDefault) {
                    Color(scheme.redAccent)
                } else {
                    Color(scheme.redAccent).ensureContrastOn(creamSurface, 3.0)
                },
                pinnedBg = Color(scheme.pinnedBg),
                announceBg = Color(scheme.announceBg),
                navBarBg = navBarBg,
                navBarIconSelected = Color(scheme.navBarIconSelected).ensureContrastOn(navBarBg, 3.0),
                navBarIconUnselected = Color(scheme.navBarIconUnselected),
            )
        }
}

private fun Color.bestReadableOn(background: Color): Color {
    val candidates = listOf(this, Color.White, Color.Black, Color(0xFFF8F4EE), Color(0xFF111111))
    return candidates.maxBy { it.contrastAgainst(background) }
}

private fun Color.ensureContrastOn(background: Color, minimumRatio: Double): Color =
    if (contrastAgainst(background) >= minimumRatio) this else bestReadableOn(background)
