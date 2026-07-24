package me.thenano.yamibo.yamibo_app.store

import android.content.Context
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStoreCodec

class AndroidForumFavoriteStore(context: Context) : ForumFavoriteStore {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val state = MutableStateFlow(ForumFavoriteStoreCodec.decode(prefs.getString(KEY_FAVORITES, null)))

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
        prefs.edit().remove(KEY_FAVORITES).apply()
        Unit
    }

    private suspend fun mutate(transform: (Map<ForumId, FavoriteId?>) -> Map<ForumId, FavoriteId?>) = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        prefs.edit().putString(KEY_FAVORITES, ForumFavoriteStoreCodec.encode(updated)).apply()
        Unit
    }

    private companion object {
        const val PREF_NAME = "forum_favorite_store"
        const val KEY_FAVORITES = "favorites"
    }
}
