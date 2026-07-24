package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.core.YamiboResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteResultMessagesTest {
    @Test
    fun favoriteSyncFailureMessagePreservesPerActionText() {
        val messages = FavoriteSyncFailureMessages(
            notLoggedIn = "login required",
            maintenance = "maintenance",
        )

        assertEquals("login required", YamiboResult.NotLoggedIn.favoriteSyncFailureMessage(messages))
        assertEquals("denied", YamiboResult.NoPermission("denied").favoriteSyncFailureMessage(messages))
        assertEquals("maintenance", YamiboResult.Maintenance.favoriteSyncFailureMessage(messages))
        assertEquals("network failed", YamiboResult.Failure("network failed").favoriteSyncFailureMessage(messages))
    }

    @Test
    fun favoriteSyncFailureMessageNormalizesAndTruncatesFailures() {
        val messages = FavoriteSyncFailureMessages(
            notLoggedIn = "login required",
            maintenance = "maintenance",
        )
        val longReason = "a".repeat(101)

        assertEquals("line one line two", YamiboResult.Failure(" line one\nline two ").favoriteSyncFailureMessage(messages))
        assertEquals("a".repeat(100) + "...", YamiboResult.Failure(longReason).favoriteSyncFailureMessage(messages))
    }

    @Test
    fun favoriteUpdateFailureReasonKeepsContextualMessages() {
        val messages = FavoriteUpdateFailureMessages(
            notLoggedIn = { title -> "login:$title" },
            maintenance = { title -> "maintenance:$title" },
        )

        assertEquals("login:item", YamiboResult.NotLoggedIn.favoriteUpdateFailureReason("item", messages))
        assertEquals("denied", YamiboResult.NoPermission("denied").favoriteUpdateFailureReason("item", messages))
        assertEquals("maintenance:item", YamiboResult.Maintenance.favoriteUpdateFailureReason("item", messages))
        assertEquals("network failed", YamiboResult.Failure("network failed").favoriteUpdateFailureReason("item", messages))
    }
}
