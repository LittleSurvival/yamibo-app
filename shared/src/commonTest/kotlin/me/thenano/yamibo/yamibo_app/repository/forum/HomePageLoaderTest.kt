package me.thenano.yamibo.yamibo_app.repository.forum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

import io.github.littlesurvival.core.ParseResult
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.page.ForumCategory
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.SwiperImages
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.parse.HomePageParser
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.core.cache.DiskCache
import kotlin.test.*

class HomePageLoaderTest {
    private val valid = HomePage(
        swiperImages = emptyList(),
        categories = listOf(
            ForumCategory("我收藏的版块", emptyList()),
            ForumCategory("任意版塊分類", listOf(ForumSummary(ForumId(49), "文學區", "forum.php?fid=49"))),
        ),
    )

    @Test
    fun abnormalHtmlCannotBecomeCachedBlankSuccess() = runBlocking {
        val inputs = listOf(
            "",
            "<html><body></body></html>",
            "<html><script>var __noxExpire=30;</script></html>",
            "<html><body>Upstream temporarily unavailable</body></html>",
        )
        for (html in inputs) {
            // Characterize the actual pinned SDK parser, not just a fabricated DTO.
            val parsed = assertIs<ParseResult.Success<HomePage>>(HomePageParser().parse(html))
            val cache = FakeCache(valid)
            val result = HomePageLoader(cache).fetch({ YamiboResult.Success(parsed.value) }) {
                fail("Invalid response must not update favorites")
            }
            assertContains(assertIs<YamiboResult.Failure>(result).message(), "HOME_INVALID_CONTENT")
            assertEquals(valid, cache.value)
            assertEquals(0, cache.writes)
        }
    }

    @Test
    fun invalidLegacyCacheIsRemovedAndFailureRemainsVisible() = runBlocking {
        val cache = FakeCache(HomePage(emptyList(), emptyList()))
        val loader = HomePageLoader(cache)
        assertNull(loader.cached())
        assertEquals(listOf("main"), cache.removed)
        val failure = YamiboResult.Failure("offline")
        assertSame(failure, loader.fetch({ failure }) { fail("Must not sync") })
        assertNull(cache.value)
    }

    @Test
    fun retryAfterInvalidCacheStoresValidHomeAndUpdatesFavorites() = runBlocking {
        val cache = FakeCache(HomePage(emptyList(), emptyList()))
        val loader = HomePageLoader(cache)
        assertNull(loader.cached())
        var synchronized: HomePage? = null
        assertIs<YamiboResult.Success<HomePage>>(loader.fetch({ YamiboResult.Success(valid) }) { synchronized = it })
        assertEquals(valid, loader.cached())
        assertEquals(valid, synchronized)
        assertEquals(1, cache.writes)
    }

    @Test
    fun emptyCategoriesAndMalformedForumsAreNotUsable() = runBlocking {
        val forum = valid.categories.last().forums.single()
        val pages = listOf(
            HomePage(listOf(SwiperImages("private-banner")), emptyList()),
            valid.copy(categories = listOf(ForumCategory("分類", emptyList()))),
            valid.copy(categories = listOf(ForumCategory(" ", listOf(forum)))),
            valid.copy(categories = listOf(ForumCategory("分類", listOf(forum.copy(name = " "))))),
        )
        for (page in pages) {
            val cache = FakeCache(page)
            val loader = HomePageLoader(cache)
            assertNull(loader.cached())
            assertIs<YamiboResult.Failure>(loader.fetch({ YamiboResult.Success(page) }) { fail("Must not sync") })
        }
    }

    @Test
    fun diagnosticsContainCountsNotSourceData() = runBlocking {
        val page = HomePage(listOf(SwiperImages("secret-token")), listOf(ForumCategory("private-name", emptyList())))
        val error = assertIs<YamiboResult.Failure>(HomePageLoader(FakeCache()).fetch({ YamiboResult.Success(page) }) {})
        assertContains(error.message(), "categories=1, forums=0, banners=1")
        assertFalse(error.message().contains("secret-token"))
        assertFalse(error.message().contains("private-name"))
    }

    @Test
    fun networkAndAuthFailuresPreserveGoodCache() = runBlocking {
        for (failure in listOf(YamiboResult.Failure("offline"), YamiboResult.NotLoggedIn, YamiboResult.Maintenance)) {
            val cache = FakeCache(valid)
            val loader = HomePageLoader(cache)
            assertSame(valid, loader.cached())
            assertSame(failure, loader.fetch({ failure }) { fail("Must not sync") })
            assertSame(valid, cache.value)
            assertEquals(0, cache.writes)
            assertTrue(cache.removed.isEmpty())
        }
    }

    private class FakeCache(var value: HomePage? = null) : DiskCache<HomePage> {
        var writes = 0
        val removed = mutableListOf<String>()
        override fun get(key: String): HomePage? { assertEquals("main", key); return value }
        override fun set(key: String, value: HomePage) { assertEquals("main", key); writes++; this.value = value }
        override fun remove(key: String) { removed += key; value = null }
        override fun removeByPrefix(prefix: String) = error("Not needed")
        override fun clear() = error("Do not clear unrelated caches")
    }
}

class ForumSupplementalMetadataTest {
    @Test fun desktopRatingSelectsLabelledDailyColumnForCorrectCurrency() {
        assertEquals(17, parseRatingRemaining("""
            <form id="rateform"><table><tr><th>積分</th><th>分數</th><th>範圍</th><th>今日剩餘</th></tr>
            <tr><td>積分</td><td><input id="score1" value="0"></td><td>-5 ~ 5</td><td>17</td></tr>
            <tr><td>其他</td><td><input id="score2"></td><td>-2 ~ 2</td><td>99</td></tr></table></form>
        """))
    }

    @Test fun unbadgedKnownNavigationMeansReadAndMessageBodiesAreIgnored() {
        assertEquals(MessageTabUnread(false, false), parseMessageTabUnread("""
            <div class="dhnv"><a href="home.php?do=pm">我的消息</a><a href="home.php?do=notice">我的提醒</a></div>
            <a href="home.php?do=pm&amp;subop=view">關於小說(99)</a>
        """))
    }

    @Test fun ratingUsesDailyAllowanceNotMaximumSelectableScore() {
        assertEquals(17, parseRatingRemaining("""<table><tr><td><select id="rate1"><option>5</option></select></td><td id="rateday1">17</td></tr></table>"""))
        assertNull(parseRatingRemaining("""<select id="rate1"><option>5</option></select>"""))
    }

    @Test fun ratingHandlesCdataZeroAndUnrelatedNumbers() {
        assertEquals(0, parseRatingRemaining("""<root><![CDATA[<select id="rate1"></select><span id="rateday1">0</span>]]></root>"""))
        assertNull(parseRatingRemaining("今日剩餘：20 請先登入"))
        assertEquals(3, parseRatingRemaining("""<select id="rate1"></select><p>今日剩余评分：3</p>"""))
    }

    @Test fun unreadTabsRemainIndependent() {
        assertEquals(MessageTabUnread(false, true), parseMessageTabUnread("""
            <a href="home.php?mod=space&amp;do=pm">我的消息(0)</a>
            <a href="home.php?mod=space&amp;do=notice">我的提醒（2）</a>
        """))
        assertEquals(MessageTabUnread(null, true), parseMessageTabUnread("""
            <a href="home.php?do=notice" class="new">我的提醒</a>
        """))
        assertEquals(MessageTabUnread(), parseMessageTabUnread("請先登入"))
    }
}
