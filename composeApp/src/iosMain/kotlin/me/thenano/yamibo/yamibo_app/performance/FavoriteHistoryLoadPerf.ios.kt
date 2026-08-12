package me.thenano.yamibo.yamibo_app.performance

internal actual fun isFavoriteHistoryLoadPerfEnabled(): Boolean = false

internal actual fun emitFavoriteHistoryLoadPerfLine(line: String) = Unit
