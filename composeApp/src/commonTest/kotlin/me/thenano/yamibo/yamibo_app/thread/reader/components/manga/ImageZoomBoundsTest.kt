package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageZoomBoundsTest {
    @Test fun portraitInWideViewportDoesNotPanItsLetterbox() {
        assertEquals(Offset(0f, 400f), imageZoomBounds(IntSize(1200, 800), Size(600f, 1000f), 2f))
        assertEquals(Offset(120f, 800f), imageZoomBounds(IntSize(1200, 800), Size(600f, 1000f), 3f))
    }

    @Test fun landscapeAndResizedViewportUseFittedImageSize() {
        assertEquals(Offset(300f, 0f), imageZoomBounds(IntSize(600, 1000), Size(1200f, 600f), 2f))
        assertEquals(Offset(160f, 300f), imageZoomBounds(IntSize(400, 600), Size(600f, 1000f), 2f))
    }

    @Test fun absentSizePreservesContainerFallbackAndUnitScaleIsCentered() {
        assertEquals(Offset(500f, 400f), imageZoomBounds(IntSize(1000, 800), null, 2f))
        assertEquals(Offset.Zero, imageZoomBounds(IntSize(1000, 800), Size(600f, 1000f), 1f))
        assertEquals(Offset.Zero, imageZoomBounds(IntSize.Zero, Size.Unspecified, 2f))
    }
}
