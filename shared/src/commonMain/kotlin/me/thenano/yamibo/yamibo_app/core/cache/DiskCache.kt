package me.thenano.yamibo.yamibo_app.core.cache

/**
 * Interface representing a simple persistent file cache.
 */
interface DiskCache<T : Any> {
    /** Persist the value and, when provided, its lookup index in the same metadata transaction. */
    fun set(key: String, value: T, writeIndex: (() -> Unit)? = null)

    /** Retrieves value from the cache by key. May return null if missing or expired. */
    fun get(key: String): T?

    /** Removes a specific entry in the cache. */
    fun remove(key: String)

    /** Removes entries whose keys start with the given prefix. */
    fun removeByPrefix(prefix: String)

    /** Clears all entries in this cache namespace. */
    fun clear()
}
