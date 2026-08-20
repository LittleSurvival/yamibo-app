package me.thenano.yamibo.yamibo_app.performance

import android.util.Log

internal actual fun isFavoriteHistoryLoadPerfEnabled(): Boolean =
    Log.isLoggable("FH_LOAD", Log.DEBUG)

internal actual fun emitFavoriteHistoryLoadPerfLine(line: String) {
    Log.d("FH_LOAD", line)
}
