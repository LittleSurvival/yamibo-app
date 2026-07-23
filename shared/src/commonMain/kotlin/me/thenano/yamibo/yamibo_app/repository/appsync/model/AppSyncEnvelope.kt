package me.thenano.yamibo.yamibo_app.repository.appsync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSyncEnvelope(
    val schemaVersion: Int = AppSyncFormat.CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMillis: Long,
    val sourceAppVersionCode: Int,
    val readingHistory: AppSyncReadingHistory = AppSyncReadingHistory(),
    val favorites: AppSyncFavorites = AppSyncFavorites(),
    val settings: List<AppSyncSetting> = emptyList(),
)

object AppSyncFormat {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val CODEC_VERSION = 1
    const val FRAME_PREFIX = "yamibo-app-sync:gzip-base64:"
    const val FULL_FRAME_PREFIX = "$FRAME_PREFIX$CODEC_VERSION:"

    const val MAX_RAW_TEXT_CHARS = 16 * 1024 * 1024
    const val MAX_COMPRESSED_BYTES = 12 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    const val MAX_HISTORY_ITEMS_PER_SECTION = 100_000
    const val MAX_FAVORITE_ENTITIES_PER_SECTION = 100_000
    const val MAX_SETTINGS = 2_000
}
