package me.thenano.yamibo.yamibo_app.repository.settings

import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

enum class EffectiveReadingModeSource {
    Global,
    ThreadOverride,
    CatalogLongStrip,
}

data class EffectiveReadingMode(
    val mode: ReadingMode,
    val source: EffectiveReadingModeSource,
)

fun resolveEffectiveReadingMode(
    global: ReadingMode,
    catalogLongStrip: Boolean,
    threadOverride: ReadingMode?,
): EffectiveReadingMode {
    return when {
        catalogLongStrip -> EffectiveReadingMode(ReadingMode.SCROLL_CONTINUOUS, EffectiveReadingModeSource.CatalogLongStrip)
        threadOverride != null -> EffectiveReadingMode(threadOverride, EffectiveReadingModeSource.ThreadOverride)
        else -> EffectiveReadingMode(global, EffectiveReadingModeSource.Global)
    }
}

interface ImageReaderModeOverrideRepository {
    fun observeTagLongStrip(tagId: TagId): Flow<Boolean>
    fun isTagLongStripEnabled(tagId: TagId): Boolean
    fun setTagLongStrip(tagId: TagId, enabled: Boolean)

    fun observeRssLongStrip(subscriptionId: Long): Flow<Boolean>
    fun isRssLongStripEnabled(subscriptionId: Long): Boolean
    fun setRssLongStrip(subscriptionId: Long, enabled: Boolean)

    fun observeThreadMode(tid: ThreadId, authorId: UserId?): Flow<ReadingMode?>
    fun getThreadMode(tid: ThreadId, authorId: UserId?): ReadingMode?
    fun setThreadMode(tid: ThreadId, authorId: UserId?, mode: ReadingMode?)
}

class SettingsImageReaderModeOverrideRepository(
    private val store: SettingsStore,
) : ImageReaderModeOverrideRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val state = MutableStateFlow(loadState())

    override fun observeTagLongStrip(tagId: TagId): Flow<Boolean> =
        state.map { tagKey(tagId) in it.longStripTagKeys }

    override fun isTagLongStripEnabled(tagId: TagId): Boolean =
        tagKey(tagId) in state.value.longStripTagKeys

    override fun setTagLongStrip(tagId: TagId, enabled: Boolean) {
        update { current ->
            val key = tagKey(tagId)
            current.copy(
                longStripTagKeys = if (enabled) {
                    current.longStripTagKeys + key
                } else {
                    current.longStripTagKeys - key
                },
            )
        }
    }

    override fun observeRssLongStrip(subscriptionId: Long): Flow<Boolean> =
        state.map { rssKey(subscriptionId) in it.longStripRssKeys }

    override fun isRssLongStripEnabled(subscriptionId: Long): Boolean =
        rssKey(subscriptionId) in state.value.longStripRssKeys

    override fun setRssLongStrip(subscriptionId: Long, enabled: Boolean) {
        update { current ->
            val key = rssKey(subscriptionId)
            current.copy(
                longStripRssKeys = if (enabled) {
                    current.longStripRssKeys + key
                } else {
                    current.longStripRssKeys - key
                },
            )
        }
    }

    override fun observeThreadMode(tid: ThreadId, authorId: UserId?): Flow<ReadingMode?> =
        state.map { it.threadModes[threadKey(tid, authorId)]?.toReadingModeOrNull() }

    override fun getThreadMode(tid: ThreadId, authorId: UserId?): ReadingMode? =
        state.value.threadModes[threadKey(tid, authorId)]?.toReadingModeOrNull()

    override fun setThreadMode(tid: ThreadId, authorId: UserId?, mode: ReadingMode?) {
        update { current ->
            val key = threadKey(tid, authorId)
            current.copy(
                threadModes = if (mode == null) {
                    current.threadModes - key
                } else {
                    current.threadModes + (key to mode.name)
                },
            )
        }
    }

    private fun update(transform: (StoredOverrides) -> StoredOverrides) {
        val next = transform(state.value).normalized()
        store.putString(storageKey, json.encodeToString(next))
        state.value = next
    }

    private fun loadState(): StoredOverrides {
        val raw = store.getString(storageKey, "")
        if (raw.isBlank()) return StoredOverrides()
        return runCatching {
            json.decodeFromString<StoredOverrides>(raw).normalized()
        }
            .onFailure { Logger.d(TAG, "Failed to decode image reader mode overrides; using defaults", it) }
            .getOrDefault(StoredOverrides())
    }

    private fun String.toReadingModeOrNull(): ReadingMode? =
        ReadingMode.entries.firstOrNull { it.name == this }

    private fun StoredOverrides.normalized(): StoredOverrides =
        copy(
            longStripTagKeys = longStripTagKeys.distinct().sorted(),
            longStripRssKeys = longStripRssKeys.distinct().sorted(),
            threadModes = threadModes
                .filterValues { it.toReadingModeOrNull() != null }
                .entries
                .sortedBy { it.key }
                .associate { it.key to it.value },
        )

    private companion object {
        @Suppress("ConstPropertyName")
        const val storageKey = "imagereadermodeoverrides.v1"

        fun tagKey(tagId: TagId): String = tagId.value.toString()

        fun rssKey(subscriptionId: Long): String = subscriptionId.toString()

        fun threadKey(tid: ThreadId, authorId: UserId?): String =
            "${tid.value}:${authorId?.value ?: "all"}"
    }
}

private const val TAG = "ImageReaderModeOverride"

@Serializable
private data class StoredOverrides(
    val longStripTagKeys: List<String> = emptyList(),
    val longStripRssKeys: List<String> = emptyList(),
    val threadModes: Map<String, String> = emptyMap(),
)
