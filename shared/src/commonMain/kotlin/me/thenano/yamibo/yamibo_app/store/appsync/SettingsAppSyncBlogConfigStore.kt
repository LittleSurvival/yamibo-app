package me.thenano.yamibo.yamibo_app.store.appsync

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class SettingsAppSyncBlogConfigStore(
    private val store: SettingsStore,
) : AppSyncBlogConfigStore {
    override fun load(): StoredAppSyncBlogConfig? {
        if (!store.hasKey(BLOG_ID)) return null
        val blogId = store.getInt(BLOG_ID, 0).takeIf { it > 0 } ?: return null
        val blogName = store.getString(BLOG_NAME, "").takeIf(String::isNotBlank) ?: return null
        return StoredAppSyncBlogConfig(
            blogName = blogName,
            blogId = BlogId(blogId),
            classId = optionalInt(CLASS_ID)?.let(::BlogClassId),
            cloudContentUpdatedAtEpochMillis = optionalLong(CLOUD_UPDATED_AT),
            validatedAtEpochMillis = optionalLong(VALIDATED_AT),
            schemaVersion = optionalInt(SCHEMA_VERSION),
            fingerprint = optionalString(FINGERPRINT),
        )
    }

    override fun save(config: StoredAppSyncBlogConfig) {
        store.putString(BLOG_NAME, config.blogName)
        store.putInt(BLOG_ID, config.blogId.value)
        putOptionalInt(CLASS_ID, config.classId?.value)
        putOptionalLong(CLOUD_UPDATED_AT, config.cloudContentUpdatedAtEpochMillis)
        putOptionalLong(VALIDATED_AT, config.validatedAtEpochMillis)
        putOptionalInt(SCHEMA_VERSION, config.schemaVersion)
        putOptionalString(FINGERPRINT, config.fingerprint)
    }

    override fun clear() {
        KEYS.forEach(store::remove)
    }

    private fun optionalInt(key: String): Int? =
        if (store.hasKey(key)) store.getInt(key, 0) else null

    private fun optionalLong(key: String): Long? =
        optionalString(key)?.toLongOrNull()

    private fun optionalString(key: String): String? =
        if (store.hasKey(key)) store.getString(key, "").takeIf(String::isNotBlank) else null

    private fun putOptionalInt(key: String, value: Int?) {
        if (value == null) store.remove(key) else store.putInt(key, value)
    }

    private fun putOptionalLong(key: String, value: Long?) {
        putOptionalString(key, value?.toString())
    }

    private fun putOptionalString(key: String, value: String?) {
        if (value == null) store.remove(key) else store.putString(key, value)
    }

    private companion object {
        const val PREFIX = "appsync.cloudBlog."
        const val BLOG_NAME = "${PREFIX}name"
        const val BLOG_ID = "${PREFIX}id"
        const val CLASS_ID = "${PREFIX}classId"
        const val CLOUD_UPDATED_AT = "${PREFIX}cloudUpdatedAt"
        const val VALIDATED_AT = "${PREFIX}validatedAt"
        const val SCHEMA_VERSION = "${PREFIX}schemaVersion"
        const val FINGERPRINT = "${PREFIX}fingerprint"
        val KEYS = listOf(
            BLOG_NAME,
            BLOG_ID,
            CLASS_ID,
            CLOUD_UPDATED_AT,
            VALIDATED_AT,
            SCHEMA_VERSION,
            FINGERPRINT,
        )
    }
}
