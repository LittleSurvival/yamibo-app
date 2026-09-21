package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import me.thenano.yamibo.yamibo_app.core.cache.DiskCache
import me.thenano.yamibo.yamibo_app.i18n.i18n

/** Reject empty SDK successes before they can replace cached content or favorite membership. */
internal class HomePageLoader(private val cache: DiskCache<HomePage>) {
    fun cached(): HomePage? {
        val page = cache.get("main") ?: return null
        if (page.hasUsableForums()) return page
        cache.remove("main")
        return null
    }

    suspend fun fetch(
        request: suspend () -> YamiboResult<HomePage>,
        onValidated: suspend (HomePage) -> Unit,
    ): YamiboResult<HomePage> {
        val result = request()
        if (result !is YamiboResult.Success) return result
        val page = result.value
        if (!page.hasUsableForums()) {
            // Structural diagnostics only: never include response text, cookies or account data.
            return YamiboResult.Failure(
                i18n("首頁資料不完整，請重試；若持續發生，請回報下方診斷代碼。") +
                    "\n[HOME_INVALID_CONTENT] categories=${page.categories.size}, " +
                    "forums=${page.categories.sumOf { it.forums.size }}, banners=${page.swiperImages.size}",
            )
        }
        cache.set("main", page)
        onValidated(page)
        return result
    }
}

private fun HomePage.hasUsableForums(): Boolean = categories.any { category ->
    category.title.isNotBlank() && category.forums.any { it.fid.value > 0 && it.name.isNotBlank() }
}
