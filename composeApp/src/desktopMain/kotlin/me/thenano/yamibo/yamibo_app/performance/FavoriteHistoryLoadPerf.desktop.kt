package me.thenano.yamibo.yamibo_app.performance

import me.thenano.yamibo.yamibo_app.Logger

internal actual fun isFavoriteHistoryLoadPerfEnabled() = java.lang.Boolean.getBoolean("yamibo.debug.favoriteHistoryPerf")
internal actual fun emitFavoriteHistoryLoadPerfLine(line: String) = Logger.d("FavoriteHistoryPerf", line)
