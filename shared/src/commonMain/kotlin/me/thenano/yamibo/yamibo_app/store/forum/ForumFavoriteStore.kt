package me.thenano.yamibo.yamibo_app.store.forum

import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ForumFavoriteStore {
    val favorites: StateFlow<Map<ForumId, FavoriteId?>>

    suspend fun replaceMembership(forumIds: Set<ForumId>)
    suspend fun enrichFavoriteIds(favoriteIds: Map<ForumId, FavoriteId>)
    suspend fun upsert(forumId: ForumId, favoriteId: FavoriteId?)
    suspend fun remove(forumId: ForumId)
    suspend fun clear()
}

@Serializable
internal data class StoredForumFavorite(
    val forumId: Int,
    val favoriteId: Int? = null,
)

internal object ForumFavoriteStoreCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(favorites: Map<ForumId, FavoriteId?>): String = json.encodeToString(
        favorites.map { (forumId, favoriteId) ->
            StoredForumFavorite(forumId.value, favoriteId?.value)
        },
    )

    fun decode(value: String?): Map<ForumId, FavoriteId?> {
        if (value.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<StoredForumFavorite>>(value).associate { entry ->
                ForumId(entry.forumId) to entry.favoriteId?.let(::FavoriteId)
            }
        }.getOrDefault(emptyMap())
    }
}
