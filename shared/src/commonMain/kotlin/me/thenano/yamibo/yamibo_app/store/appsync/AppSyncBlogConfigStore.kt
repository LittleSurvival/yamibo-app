package me.thenano.yamibo.yamibo_app.store.appsync

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId

data class StoredAppSyncBlogConfig(
    val blogName: String,
    val blogId: BlogId,
    val classId: BlogClassId? = null,
    val cloudContentUpdatedAtEpochMillis: Long? = null,
    val validatedAtEpochMillis: Long? = null,
    val schemaVersion: Int? = null,
    val fingerprint: String? = null,
)

interface AppSyncBlogConfigStore {
    fun load(): StoredAppSyncBlogConfig?
    fun save(config: StoredAppSyncBlogConfig)
    fun clear()
}
