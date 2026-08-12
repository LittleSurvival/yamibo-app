package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.i18n.i18n

internal data class FavoriteSyncFailureMessages(
    val notLoggedIn: String,
    val maintenance: String,
)

internal fun favoriteSyncActionFailureMessages(): FavoriteSyncFailureMessages =
    FavoriteSyncFailureMessages(
        notLoggedIn = i18n("目前未登入百合會，無法同步收藏。"),
        maintenance = i18n("百合會目前維護中，請稍後再試。"),
    )

internal fun favoriteSyncDeleteFailureMessages(): FavoriteSyncFailureMessages =
    FavoriteSyncFailureMessages(
        notLoggedIn = i18n("目前未登入百合會，無法同步刪除網站收藏。"),
        maintenance = i18n("百合會目前維護中，請稍後再試。"),
    )

internal fun YamiboResult<*>.favoriteSyncFailureMessage(
    messages: FavoriteSyncFailureMessages,
): String = when (this) {
    is YamiboResult.NotLoggedIn -> messages.notLoggedIn
    is YamiboResult.NoPermission -> message()
    is YamiboResult.Maintenance -> messages.maintenance
    is YamiboResult.WafChallenge -> message()
    is YamiboResult.Failure -> truncateFavoriteMessage(message())
    is YamiboResult.Success -> error("Success result has no failure message")
}

internal data class FavoriteUpdateFailureMessages(
    val notLoggedIn: (String) -> String,
    val maintenance: (String) -> String,
)

internal fun favoriteUpdateFailureMessages(): FavoriteUpdateFailureMessages =
    FavoriteUpdateFailureMessages(
        notLoggedIn = { itemTitle -> i18n("登入狀態已失效，無法檢查 {}", itemTitle) },
        maintenance = { itemTitle -> i18n("百合會維護中，無法檢查 {}", itemTitle) },
    )

internal fun YamiboResult<*>.favoriteUpdateFailureReason(
    itemTitle: String,
    messages: FavoriteUpdateFailureMessages = favoriteUpdateFailureMessages(),
): String = when (this) {
    is YamiboResult.NotLoggedIn -> messages.notLoggedIn(itemTitle)
    is YamiboResult.NoPermission -> message()
    is YamiboResult.Maintenance -> messages.maintenance(itemTitle)
    is YamiboResult.WafChallenge -> message()
    is YamiboResult.Failure -> message()
    is YamiboResult.Success -> error("Success result has no failure reason")
}

internal fun truncateFavoriteMessage(message: String, maxChars: Int = 100): String {
    val normalized = message
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "..."
}
