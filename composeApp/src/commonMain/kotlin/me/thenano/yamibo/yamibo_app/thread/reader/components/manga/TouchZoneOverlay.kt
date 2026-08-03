package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout

/** Describes the action area for a touch zone region */
enum class TouchAction {
    PREV,
    NEXT,
    MENU,
}

private fun TouchAction.localizedLabel(): String = when (this) {
    TouchAction.PREV -> i18n("上一頁")
    TouchAction.NEXT -> i18n("下一頁")
    TouchAction.MENU -> i18n("選單")
}

/**
 * Touch zone preview overlay.
 * Shows colored regions indicating touch areas for navigation.
 * Automatically fades out on the user's next single click.
 */
@Composable
fun TouchZoneOverlay(
    visible: Boolean,
    layout: TouchZoneLayout,
    reverseTouchZones: Boolean,
    isRtl: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        when (layout) {
            TouchZoneLayout.L_SHAPE -> LShapeZoneLayout(reverseTouchZones, isRtl)
            TouchZoneLayout.KINDLE -> KindleZoneLayout(reverseTouchZones, isRtl)
            TouchZoneLayout.EDGE -> EdgeZoneLayout(reverseTouchZones, isRtl)
            TouchZoneLayout.LEFT_RIGHT -> LeftRightZoneLayout(reverseTouchZones, isRtl)
            TouchZoneLayout.DISABLED -> { /* No overlay */ }
        }
    }
}

/** Resolve the final action used by Manga Reader runtime and preview. */
fun getEffectiveMangaTouchAction(
    layout: TouchZoneLayout,
    xFraction: Float,
    yFraction: Float,
    reverseTouchZones: Boolean,
    isRtl: Boolean,
): TouchAction? = transformMangaTouchAction(
    action = getTouchAction(layout, xFraction, yFraction),
    reverseTouchZones = reverseTouchZones,
    isRtl = isRtl,
)

fun transformMangaTouchAction(
    action: TouchAction?,
    reverseTouchZones: Boolean,
    isRtl: Boolean,
): TouchAction? {
    var result = action
    if (reverseTouchZones) result = result.swapPreviousAndNext()
    if (isRtl) result = result.swapPreviousAndNext()
    return result
}

private fun TouchAction?.swapPreviousAndNext(): TouchAction? = when (this) {
    TouchAction.PREV -> TouchAction.NEXT
    TouchAction.NEXT -> TouchAction.PREV
    TouchAction.MENU, null -> this
}

/** Determine touch action based on layout and tap position (fraction: 0..1) */
fun getTouchAction(layout: TouchZoneLayout, xFraction: Float, yFraction: Float): TouchAction? {
    return when (layout) {
        TouchZoneLayout.L_SHAPE -> {
            when {
                yFraction < 0.20f -> TouchAction.PREV
                xFraction < 0.30f -> TouchAction.PREV
                yFraction > 0.80f -> TouchAction.NEXT
                xFraction > 0.70f -> TouchAction.NEXT
                else -> TouchAction.MENU
            }
        }
        TouchZoneLayout.KINDLE -> {
            when {
                yFraction < 0.35f -> TouchAction.MENU
                xFraction < 0.33f -> TouchAction.PREV
                else -> TouchAction.NEXT
            }
        }

        /** Same as DEFAULT */

        // Edge type: Left/right narrow edges (~15%) = NEXT, bottom strip (~15%) = PREV, center = MENU
        TouchZoneLayout.EDGE -> {
            when {
                yFraction > 0.85f -> TouchAction.PREV
                xFraction < 0.15f -> TouchAction.NEXT
                xFraction > 0.85f -> TouchAction.NEXT
                else -> TouchAction.MENU
            }
        }

        // Left-Right type: Left ~25% = PREV, right ~25% = NEXT, center ~50% = MENU
        TouchZoneLayout.LEFT_RIGHT -> {
            when {
                xFraction < 0.25f -> TouchAction.PREV
                xFraction > 0.75f -> TouchAction.NEXT
                else -> TouchAction.MENU
            }
        }

        TouchZoneLayout.DISABLED -> null
    }
}

/** Kindle : Top 35% = menu, bottom-left 33% = prev, bottom-right 67% = next */
@Composable
private fun KindleZoneLayout(reverseTouchZones: Boolean, isRtl: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        ZoneCell(TouchAction.MENU, reverseTouchZones, isRtl, Modifier.fillMaxWidth().weight(0.35f))
        Row(modifier = Modifier.weight(0.65f).fillMaxWidth()) {
            ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.weight(1f).fillMaxHeight())
            ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.weight(2f).fillMaxHeight())
        }
    }
}

/** L Shape : Top strip + left column = prev, bottom strip + right column = next, center = menu */
@Composable
private fun LShapeZoneLayout(reverseTouchZones: Boolean, isRtl: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top strip: full width PREV
        ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.fillMaxWidth().weight(0.2f))
        // Middle: left PREV | center MENU | right NEXT
        Row(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
            ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.weight(0.3f).fillMaxHeight())
            ZoneCell(TouchAction.MENU, reverseTouchZones, isRtl, Modifier.weight(0.4f).fillMaxHeight())
            ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.weight(0.3f).fillMaxHeight())
        }
        // Bottom: left PREV | right NEXT
        Row(modifier = Modifier.weight(0.2f).fillMaxWidth()) {
            ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.weight(0.3f).fillMaxHeight())
            ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.weight(0.7f).fillMaxHeight())
        }
    }
}

/** Edge : Left/right edges = next, bottom strip = prev, center = menu */
@Composable
private fun EdgeZoneLayout(reverseTouchZones: Boolean, isRtl: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Center = MENU (base layer)
        ZoneCell(TouchAction.MENU, reverseTouchZones, isRtl, Modifier.fillMaxSize())
        // Left edge = NEXT
        ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.fillMaxHeight().fillMaxWidth(0.15f).align(Alignment.CenterStart))
        // Right edge = NEXT
        ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.fillMaxHeight().fillMaxWidth(0.15f).align(Alignment.CenterEnd))
        // Bottom strip = PREV
        ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.fillMaxWidth().fillMaxHeight(0.15f).align(Alignment.BottomCenter))
    }
}

/** Left-Right : Left 25% = prev, center 50% = menu, right 25% = next */
@Composable
private fun LeftRightZoneLayout(reverseTouchZones: Boolean, isRtl: Boolean) {
    Row(modifier = Modifier.fillMaxSize()) {
        ZoneCell(TouchAction.PREV, reverseTouchZones, isRtl, Modifier.weight(0.25f).fillMaxHeight())
        ZoneCell(TouchAction.MENU, reverseTouchZones, isRtl, Modifier.weight(0.50f).fillMaxHeight())
        ZoneCell(TouchAction.NEXT, reverseTouchZones, isRtl, Modifier.weight(0.25f).fillMaxHeight())
    }
}

@Composable
private fun ZoneCell(
    action: TouchAction,
    reverseTouchZones: Boolean,
    isRtl: Boolean,
    modifier: Modifier = Modifier,
) {
    val effectiveAction = transformMangaTouchAction(action, reverseTouchZones, isRtl) ?: action
    val color = when (effectiveAction) {
        TouchAction.PREV -> Color(0x44FF9800)
        TouchAction.NEXT -> Color(0x4400BCD4)
        TouchAction.MENU -> Color(0x449C27B0)
    }
    Box(
        modifier = modifier.background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = effectiveAction.localizedLabel(),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
