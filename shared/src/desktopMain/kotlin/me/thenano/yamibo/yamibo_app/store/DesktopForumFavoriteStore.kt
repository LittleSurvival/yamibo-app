package me.thenano.yamibo.yamibo_app.store

import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStoreCodec
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class DesktopForumFavoriteStore(private val settings: SettingsStore) : ForumFavoriteStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow(ForumFavoriteStoreCodec.decode(settings.getString(KEY, "")))
    override val favorites = state.asStateFlow()
    override suspend fun replaceMembership(forumIds: Set<ForumId>) = mutate { old -> forumIds.associateWith { old[it] } }
    override suspend fun enrichFavoriteIds(favoriteIds: Map<ForumId, FavoriteId>) = mutate { old ->
        old.mapValues { (id, existing) -> favoriteIds[id] ?: existing }
    }
    override suspend fun upsert(forumId: ForumId, favoriteId: FavoriteId?) = mutate { it + (forumId to favoriteId) }
    override suspend fun remove(forumId: ForumId) = mutate { it - forumId }
    override suspend fun clear() = mutate { emptyMap() }
    private suspend fun mutate(block: (Map<ForumId, FavoriteId?>) -> Map<ForumId, FavoriteId?>) = mutex.withLock {
        val updated = block(state.value)
        settings.putString(KEY, ForumFavoriteStoreCodec.encode(updated))
        state.value = updated
    }
    private companion object { const val KEY = "forum-favorites" }
}
