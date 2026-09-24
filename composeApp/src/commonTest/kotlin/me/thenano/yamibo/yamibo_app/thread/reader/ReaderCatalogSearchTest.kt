package me.thenano.yamibo.yamibo_app.thread.reader

import me.thenano.yamibo.yamibo_app.thread.reader.components.CatalogQuery
import me.thenano.yamibo.yamibo_app.thread.reader.components.catalogFloorPage
import me.thenano.yamibo.yamibo_app.thread.reader.components.catalogMatchRanges
import me.thenano.yamibo.yamibo_app.thread.reader.components.parseCatalogQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderCatalogSearchTest {
    @Test
    fun floorCommandsRequireCompleteSyntax() {
        assertEquals(CatalogQuery.Empty, parseCatalogQuery("  "))
        assertEquals(CatalogQuery.Text("123"), parseCatalogQuery("123"))
        assertEquals(CatalogQuery.Floor(123), parseCatalogQuery(" 123# "))
        assertEquals(CatalogQuery.Text("Chapter #1"), parseCatalogQuery("Chapter #1"))
        assertEquals(CatalogQuery.Text("#"), parseCatalogQuery("#"))
        assertEquals(CatalogQuery.Floor(-1), parseCatalogQuery("-1#"))
        assertEquals(CatalogQuery.Floor(null), parseCatalogQuery("99999999999999999999999999#"))
    }

    @Test
    fun boundariesAreInclusiveAndPagesUseTwentyFloors() {
        assertNull(catalogFloorPage(0, 7))
        assertNull(catalogFloorPage(-1, 7))
        assertNull(catalogFloorPage(null, 7))
        assertEquals(1, catalogFloorPage(1, 7))
        assertEquals(1, catalogFloorPage(20, 7))
        assertEquals(2, catalogFloorPage(21, 7))
        assertEquals(7, catalogFloorPage(123, 7))
        assertEquals(7, catalogFloorPage(140, 7))
        assertNull(catalogFloorPage(141, 7))
        assertNull(catalogFloorPage(Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, catalogFloorPage(Int.MAX_VALUE.toLong() * 20, Int.MAX_VALUE))
        assertNull(catalogFloorPage(1, 0))
    }

    @Test
    fun highlightsUseOriginalOffsetsAndLiteralMatching() {
        assertEquals(listOf(0..1, 3..4), catalogMatchRanges("重逢與重逢", "重逢"))
        assertEquals(listOf(0..2, 4..6), catalogMatchRanges("AbC abc", "ABC"))
        assertEquals(listOf(1..2), catalogMatchRanges("x.*y", ".*"))
        assertEquals(emptyList(), catalogMatchRanges("title", ""))
        assertEquals(emptyList(), catalogMatchRanges("未載入", "重逢"))
    }
}
