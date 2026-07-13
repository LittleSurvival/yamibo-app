package me.thenano.yamibo.yamibo_app.core.cache

import io.ktor.util.date.*
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.i18n.i18n
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

data class CacheStorageUsage(
    val key: String,
    val label: String,
    val bytes: Long,
)

data class CacheStorageBreakdown(
    val rootPath: String,
    val usages: List<CacheStorageUsage>,
)

class DiskCacheFactory(
    dbFactory: DatabaseFactory,
    val json: Json = Json { ignoreUnknownKeys = true },
    val cacheDirPath: String // Base cache directory from platform
) {
    val database = Database(dbFactory.createDriver())
    val fileSystem = FileSystem.SYSTEM
    val rootCacheDir = cacheDirPath.toPath() / "yamibo_cache"
    var backupStorageUsageProvider: (suspend () -> Long)? = null

    /**
     * Create a new DiskCache instance for a specific type <T>
     * @param namespace The separator name to prevent cache key collision
     * @param maxSize Maximum entries before LRU eviction triggers
     * @param expiration How much duration an entry lives (null for no time-based expiration, uses only LRU)
     */
    inline fun <reified T : Any> create(
        namespace: String,
        maxSize: Int = 100,
        expiration: Duration? = null
    ): DiskCache<T> {
        val serializer = try {
            kotlinx.serialization.serializer<T>()
        } catch (e: Exception) {
            Logger.e("", "DiskCacheFactory: Could not find serializer for ${T::class.simpleName}. Disk cache will be disabled, using L1 MemoryCache only.", e)
            null
        }

        return DiskCacheImpl(
            namespace = namespace,
            maxSize = maxSize,
            expirationMs = expiration?.inWholeMilliseconds,
            database = database,
            json = json,
            rootCacheDir = rootCacheDir,
            fileSystem = fileSystem,
            serializer = serializer
        )
    }

    suspend fun getCacheStorageBreakdown(): CacheStorageBreakdown = withContext(Dispatchers.IO) {
        val grouped = linkedMapOf(
            "images" to CacheStorageUsage("images", i18n("圖片"), 0L),
            "pages" to CacheStorageUsage("pages", i18n("帖子/論壇頁面"), 0L),
            "userspace" to CacheStorageUsage("userspace", i18n("用戶空間/日志"), 0L),
            "other" to CacheStorageUsage("other", i18n("其他"), 0L),
        )

        grouped["backup"] = CacheStorageUsage("backup", i18n("設定與收藏備份"), 0L)

        fun addUsage(key: String, bytes: Long) {
            if (bytes <= 0L) return
            val current = grouped[key] ?: grouped.getValue("other")
            grouped[current.key] = current.copy(bytes = current.bytes + bytes)
        }

        if (fileSystem.exists(rootCacheDir)) {
            fileSystem.list(rootCacheDir).forEach { child ->
                val key = child.name
                addUsage(cacheNamespaceGroup(key), calculateSize(child) ?: 0L)
            }
        }

        val cacheRoot = cacheDirPath.toPath()
        val coilDirs = listOf("image_cache", "coil_image_cache", "coil3_disk_cache")
        (coilDirs + listOf("images"))
            .map { cacheRoot / it }
            .filter { fileSystem.exists(it) }
            .forEach { addUsage("images", calculateSize(it) ?: 0L) }

        if (fileSystem.exists(cacheRoot)) {
            val knownTopLevel = (coilDirs + listOf("images", "yamibo_cache")).toSet()
            fileSystem.list(cacheRoot)
                .filter { it.name !in knownTopLevel }
                .forEach { addUsage("other", calculateSize(it) ?: 0L) }
        }

        backupStorageUsageProvider?.invoke()?.let { addUsage("backup", it) }

        CacheStorageBreakdown(
            rootPath = cacheDirPath,
            usages = grouped.values.filter { it.bytes > 0L },
        )
    }

    private fun calculateSize(path: okio.Path): Long? {
        if (!fileSystem.exists(path)) return 0L
        return try {
            val meta = fileSystem.metadata(path)
            when {
                meta.isRegularFile -> meta.size ?: 0L
                meta.isDirectory -> {
                    var total = 0L
                    for (child in fileSystem.listRecursively(path)) {
                        val childMeta = runCatching { fileSystem.metadata(child) }.getOrNull() ?: continue
                        if (childMeta.isRegularFile) {
                            total += childMeta.size ?: 0L
                        }
                    }
                    total
                }
                else -> 0L
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheNamespaceGroup(namespace: String): String {
        return when (namespace) {
            "forum_page",
            "thread_page",
            "tag_page",
            "novel_thread_cache" -> "pages"
            "user_space",
            "blog_page" -> "userspace"
            else -> "other"
        }
    }

    /** Clears all disk cache across all namespaces */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        try {
            database.diskCacheEntryQueries.clearAll()
            if (fileSystem.exists(rootCacheDir)) {
                fileSystem.deleteRecursively(rootCacheDir)
                fileSystem.createDirectories(rootCacheDir)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class DiskCacheImpl<T : Any>(
    private val namespace: String,
    private val maxSize: Int,
    private val expirationMs: Long?,
    database: Database,
    private val json: Json,
    rootCacheDir: okio.Path,
    private val fileSystem: FileSystem,
    private val serializer: KSerializer<T>?
) : DiskCache<T> {

    private val queries = database.diskCacheEntryQueries
    private val scope = CoroutineScope(Dispatchers.IO)
    private val namespaceDir = rootCacheDir / namespace
    
    private data class MemoryEntry<T>(val value: T, val createdAt: Long)
    private val memoryCache = mutableMapOf<String, MemoryEntry<T>>()

    init {
        if (serializer != null) {
            try {
                fileSystem.createDirectories(namespaceDir)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getFilePath(key: String) = namespaceDir / "$key.json"

    override fun set(key: String, value: T) {
        val now = getTimeMillis()
        
        // L1 Cache
        memoryCache.remove(key) // Ensure it moves to end of LinkedHashMap iteration order if already exists
        memoryCache[key] = MemoryEntry(value, now)
        if (memoryCache.size > maxSize) {
            memoryCache.keys.firstOrNull()?.let { memoryCache.remove(it) }
        }

        if (serializer == null) return

        val serializedValue = try {
            json.encodeToString(serializer, value)
        } catch (e: Exception) {
            Logger.e("DiskCacheImpl", "Failed to serialize value for key $key", e)
            return
        }

        // Write to File System synchronously
        val file = getFilePath(key)
        try {
            fileSystem.sink(file).buffer().use { sink ->
                sink.writeUtf8(serializedValue)
            }
        } catch (e: Exception) {
            Logger.e("DiskCacheImpl", "Failed to write value to file system for key $key", e)
            return
        }

        // Update DB Metadata
        try {
            queries.upsert(
                namespace = namespace,
                cacheKey = key,
                createdAt = now,
                lastAccessedAt = now
            )
            // Trigger LRU asynchronously
            maintainLRU()
        } catch (e: Exception) {
            Logger.e("DiskCacheImpl", "Failed to update DB metadata for key $key", e)
            // Cleanup file if DB failed
            try { fileSystem.delete(file) } catch (_: Exception) {}
        }
    }

    override fun get(key: String): T? {
        val now = getTimeMillis()
        
        // Try L1 Memory Cache
        val memEntry = memoryCache[key]
        if (memEntry != null) {
            if (expirationMs != null && now - memEntry.createdAt > expirationMs) {
                remove(key) // clear from both mem and disk
                return null
            }
            // Update LRU position
            memoryCache.remove(key)
            memoryCache[key] = memEntry
            return memEntry.value
        }

        if (serializer == null) return null

        // 2. Try L2 Disk Cache
        val entry = try {
            queries.get(namespace, key).executeAsOneOrNull()
        } catch (_: Exception) {
            null
        } ?: return null

        // Check Expiration
        if (expirationMs != null && now - entry.createdAt > expirationMs) {
            remove(key)
            return null
        }

        // Update Access Time async
        scope.launch {
            try {
                queries.updateAccessTime(
                    lastAccessedAt = now,
                    namespace = namespace,
                    cacheKey = key
                )
            } catch (_: Exception) {}
        }

        val file = getFilePath(key)
        val jsonString = try {
            if (fileSystem.exists(file)) {
                fileSystem.source(file).buffer().use { source ->
                    source.readUtf8()
                }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (jsonString == null) {
            remove(key) // File missing, clean up DB
            return null
        }

        return try {
            val decoded = json.decodeFromString(serializer, jsonString)
            // Populate L1 cache
            memoryCache[key] = MemoryEntry(decoded, entry.createdAt)
            if (memoryCache.size > maxSize) {
                memoryCache.keys.firstOrNull()?.let { memoryCache.remove(it) }
            }
            decoded
        } catch (e: Exception) {
            e.printStackTrace()
            remove(key)
            null
        }
    }

    override fun remove(key: String) {
        memoryCache.remove(key)
        if (serializer == null) return
        try {
            queries.delete(namespace, key)
            fileSystem.delete(getFilePath(key))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun removeByPrefix(prefix: String) {
        val memMatching = memoryCache.keys.filter { it.startsWith(prefix) }
        memMatching.forEach { memoryCache.remove(it) }
        
        if (serializer == null) return
        try {
            val matchingKeys = queries.getKeysByPrefix(namespace, "$prefix%").executeAsList()
            queries.deleteByPrefix(namespace, "$prefix%")
            matchingKeys.forEach { key ->
                try {
                    fileSystem.delete(getFilePath(key))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun clear() {
        memoryCache.clear()
        if (serializer == null) return
        try {
            queries.clearNamespace(namespace)
            if (fileSystem.exists(namespaceDir)) {
                fileSystem.deleteRecursively(namespaceDir)
                fileSystem.createDirectories(namespaceDir)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun maintainLRU() {
        if (serializer == null) return
        scope.launch {
            try {
                val targets = queries.getLRUTargets(namespace, maxSize.toLong()).executeAsList()
                if (targets.isNotEmpty()) {
                    // Start deleting excess entries from DB and file system
                    targets.forEach { targetKey ->
                        remove(targetKey)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
