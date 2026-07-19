package me.thenano.yamibo.yamibo_app.repository.userspace

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.core.cache.DiskCache

internal fun <T : Any> YamiboResult<T>.cacheSuccess(
    cache: DiskCache<T>,
    key: String,
): YamiboResult<T> {
    if (this is YamiboResult.Success) {
        cache.set(key, value)
    }
    return this
}
