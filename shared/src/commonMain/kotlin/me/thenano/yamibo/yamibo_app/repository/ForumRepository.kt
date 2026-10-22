package me.thenano.yamibo.yamibo_app.repository

import com.fleeksoft.ksoup.Ksoup
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.fetch.FetchFactory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FilterType
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.OrderType
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import kotlinx.coroutines.flow.StateFlow

interface ForumRepository {
    val favoriteForums: StateFlow<Map<ForumId, FavoriteId?>>

    data class ForumCacheKey(
        val fid: Int,
        val page: Int,
        val filterTypeId: Int?,
        val orderFilter: String?,
        val orderBy: String?,
    ) {
        fun toCacheKey(): String = listOf(
            fid.toString(),
            page.toString(),
            filterTypeId?.toString() ?: "all",
            orderFilter ?: "default",
            orderBy ?: "default",
        ).joinToString("_")

        companion object {
            fun keyPrefix(fid: Int): String = "${fid}_"
        }
    }

    suspend fun fetchHomePage(): YamiboResult<HomePage>
    suspend fun fetchForum(
        fid: ForumId,
        page: Int = 1,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    ): YamiboResult<ForumPage>
    suspend fun fetchSearch(
        query: String,
        forumId: ForumId? = null,
        formHash: FormHash
    ): YamiboResult<SearchPage>

    suspend fun fetchSearchById(
        query: String,
        searchId: SearchId,
        page: Int = 1
    ): YamiboResult<SearchPage>

    suspend fun addFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<AddFavoriteResult>
    suspend fun removeFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<String>

    fun getCachedHomePage(): HomePage?
    fun getCachedForumPage(
        fid: ForumId,
        page: Int = 1,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    ): ForumPage?
    fun setCachedForumPage(
        fid: ForumId,
        page: Int,
        forumPage: ForumPage,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    )
    fun clearCachedForum(fid: ForumId)
}

/** Optional metadata absent from yamibo-api DTOs. Missing data is unknown, never zero. */
data class MessageTabUnread(val messages: Boolean? = null, val notices: Boolean? = null)

object ForumSupplementalMetadata {
    suspend fun ratingRemaining(tid: ThreadId, pid: PostId, cookie: String): Int? =
        fetchHtml(YamiboRoute.RatePopout(tid, pid).build(), cookie, desktop = true)?.let(::parseRatingRemaining)

    suspend fun messageUnread(cookie: String): MessageTabUnread? = fetchHtml(
        YamiboRoute.UserSpace.Notification(YamiboRoute.UserSpace.NotificationType.MyMessage, 1).build(),
        cookie,
    )?.let(::parseMessageTabUnread)

    private suspend fun fetchHtml(url: String, cookie: String, desktop: Boolean = false): String? = withContext(Dispatchers.Default) {
        if (cookie.isBlank()) return@withContext null
        val client = HttpClientFactory.create()
        try {
            val response = client.get(url) {
                if (desktop) url {
                    parameters.remove("mobile")
                    parameters.append("mobile", "no")
                }
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.UserAgent, (if (desktop) FetchFactory.Device.DESKTOP else FetchFactory.Device.MOBILE).userAgent)
                header(HttpHeaders.Referrer, YamiboRoute.Home.build())
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }
}

internal fun parseRatingRemaining(html: String): Int? {
    val body = html.substringAfter("<![CDATA[", html).substringBefore("]]>")
    val doc = Ksoup.parse(body)
    // Desktop Discuz exposes a labelled allowance column; the mobile template omits it.
    // Match the score1 row, since other credit currencies have independent allowances.
    val rate = doc.selectFirst("#rate1, #score1") ?: return null
    val row = rate.closest("tr")
    val table = row?.closest("table")
    val dailyColumn = table?.select("th")?.indexOfFirst {
        Regex("(?:今日|今天|本日).*(?:剩[餘余]|[餘余]額)|(?:left today|today.?s remaining)", RegexOption.IGNORE_CASE)
            .containsMatchIn(it.text())
    } ?: -1
    if (dailyColumn >= 0) {
        row?.children()?.getOrNull(dailyColumn)?.text()?.trim()?.toIntOrNull()
            ?.takeIf { it >= 0 }?.let { return it }
    }
    val explicit = row?.selectFirst("[id^=rateday]") ?: doc.selectFirst("#rateday1")
    explicit?.text()?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
    val text = row?.text() ?: doc.text()
    return Regex("(?:今日|今天|本日)(?:剩[餘余]|[餘余]額|还可|還可)(?:評分|评分)?[：:\\s]*([0-9]+)")
        .find(text)?.groupValues?.get(1)?.toIntOrNull()
}

internal fun parseMessageTabUnread(html: String): MessageTabUnread {
    val doc = Ksoup.parse(html)
    fun unread(action: String): Boolean? {
        val links = doc.select("a[href]").filter {
            Regex("(?:[?&])do=$action(?:&|$)").containsMatchIn(it.attr("href")) &&
                (it.closest(".dhnv") != null || it.text().startsWith("我的消息") || it.text().startsWith("我的提醒"))
        }
        if (links.isEmpty()) return null
        // Counts and explicit 'new' markers are evidence; an unadorned link is not.
        val states = links.mapNotNull { link ->
            val count = Regex("[（(]\\s*(\\d+)\\s*[）)]").find(link.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: link.selectFirst(".num, .count, .badge, .unread")?.text()?.trim()?.toIntOrNull()
            when {
                count != null -> count > 0
                link.hasClass("new") || link.hasClass("new_ntc") || link.selectFirst(".new, .new_ntc") != null -> true
                link.closest(".dhnv") != null -> false
                else -> null
            }
        }
        return states.takeIf { it.isNotEmpty() }?.any { it }
    }
    return MessageTabUnread(unread("pm"), unread("notice"))
}
