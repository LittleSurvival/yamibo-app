package me.thenano.yamibo.yamibo_app.thread.reader.components.thread

import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock

/** All screen-wide inputs that may change single-page geometry or semantic page identity. */
internal data class SinglePagePlanningGenerationKey(
    val viewportWidthPx: Int,
    val readableHeightPx: Int,
    val verticalPaddingPx: Int,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: String,
    val contentWidthFraction: Float,
    val fontSize: Int,
    val lineSpacing: Float,
    val readerFontId: String,
    val textMeasurerIdentity: Int,
    val localeEngineId: String,
    val paginationStrategy: ThreadReaderPaginationStrategy,
    val convertedContentVersion: Int,
    val isNovelThread: Boolean,
    val showRegularFirstPostTagBanner: Boolean,
    val showNovelFirstPostTagBanner: Boolean,
)

internal data class SinglePageNormalizedBlocksKey(
    val postId: Long,
    val convertedContent: String,
)

internal data class SinglePagePostPlanKey(
    val postId: Long,
    val convertedContent: String,
    val emptyContentAnchorBlockId: String?,
    val imageGeometry: List<Pair<String, Float?>>,
    val footerMeasurements: List<Pair<String, Int?>>,
)

internal class BoundedLruCache<K, V>(
    private val maxEntries: Int,
) {
    private val values = mutableMapOf<K, V>()
    private val accessOrder = ArrayDeque<K>()

    init {
        require(maxEntries > 0)
    }

    val size: Int get() = values.size

    fun get(key: K): V? {
        val value = values[key] ?: return null
        accessOrder.remove(key)
        accessOrder.addLast(key)
        return value
    }

    fun put(key: K, value: V) {
        if (values.containsKey(key)) accessOrder.remove(key)
        values[key] = value
        accessOrder.addLast(key)
        while (accessOrder.size > maxEntries) {
            values.remove(accessOrder.removeFirst())
        }
    }

    fun clear() {
        values.clear()
        accessOrder.clear()
    }
}

/**
 * ThreadReader-screen-lifetime cache. It contains immutable parsing/planning artifacts only;
 * callers keep UI state, source indexes, painters, callbacks, and repositories outside it.
 */
internal class ThreadReaderSinglePagePlanningCache(
    normalizedBlockCapacity: Int = 64,
    postPlanCapacity: Int = 64,
) {
    private val normalizedBlocks = BoundedLruCache<SinglePageNormalizedBlocksKey, List<HtmlBlock>>(
        normalizedBlockCapacity,
    )
    private val postPlans = BoundedLruCache<SinglePagePostPlanKey, List<ThreadReaderPlannedPage>>(
        postPlanCapacity,
    )
    private var generationKey: SinglePagePlanningGenerationKey? = null

    internal val normalizedBlockCacheSize: Int get() = normalizedBlocks.size
    internal val postPlanCacheSize: Int get() = postPlans.size
    internal val currentGenerationKey: SinglePagePlanningGenerationKey? get() = generationKey

    /** Returns true when a new generation replaced and cleared every prior artifact. */
    fun ensureGeneration(key: SinglePagePlanningGenerationKey): Boolean {
        if (generationKey == key) return false
        generationKey = key
        normalizedBlocks.clear()
        postPlans.clear()
        return true
    }

    fun normalizedBlocks(
        key: SinglePageNormalizedBlocksKey,
        metrics: ThreadReaderPlanningMetrics? = null,
        build: () -> List<HtmlBlock>,
    ): List<HtmlBlock> {
        normalizedBlocks.get(key)?.let { cached ->
            metrics?.let { it.normalizedBlockCacheHits++ }
            return cached
        }
        metrics?.let { it.normalizedBlockBuilds++ }
        return build().also { normalizedBlocks.put(key, it) }
    }

    fun postPlan(
        key: SinglePagePostPlanKey,
        metrics: ThreadReaderPlanningMetrics? = null,
        build: () -> List<ThreadReaderPlannedPage>,
    ): List<ThreadReaderPlannedPage> {
        postPlans.get(key)?.let { cached ->
            metrics?.let { it.postPlanCacheHits++ }
            return cached
        }
        return build().also { postPlans.put(key, it) }
    }

    fun clear() {
        generationKey = null
        normalizedBlocks.clear()
        postPlans.clear()
    }
}
