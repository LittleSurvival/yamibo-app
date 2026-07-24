package me.thenano.yamibo.yamibo_app.store

import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStoreCodec
import platform.Foundation.NSUserDefaults

class IOSForumFavoriteStore : ForumFavoriteStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val mutex = Mutex()
    private val state = MutableStateFlow(ForumFavoriteStoreCodec.decode(defaults.stringForKey(KEY_FAVORITES)))

    override val favorites: StateFlow<Map<ForumId, FavoriteId?>> = state

    override suspend fun replaceMembership(forumIds: Set<ForumId>) {
        mutate { current -> forumIds.associateWith { current[it] } }
    }

    override suspend fun enrichFavoriteIds(favoriteIds: Map<ForumId, FavoriteId>) {
        mutate { current -> current.mapValues { (forumId, existingId) -> favoriteIds[forumId] ?: existingId } }
    }

    override suspend fun upsert(forumId: ForumId, favoriteId: FavoriteId?) {
        mutate { current -> current + (forumId to favoriteId) }
    }

    override suspend fun remove(forumId: ForumId) {
        mutate { current -> current - forumId }
    }

    override suspend fun clear() = mutex.withLock {
        state.value = emptyMap()
        defaults.removeObjectForKey(KEY_FAVORITES)
    }

    private suspend fun mutate(transform: (Map<ForumId, FavoriteId?>) -> Map<ForumId, FavoriteId?>) = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        defaults.setObject(ForumFavoriteStoreCodec.encode(updated), forKey = KEY_FAVORITES)
    }

    private companion object {
        const val KEY_FAVORITES = "forum_favorite_store.favorites"
    }
}
