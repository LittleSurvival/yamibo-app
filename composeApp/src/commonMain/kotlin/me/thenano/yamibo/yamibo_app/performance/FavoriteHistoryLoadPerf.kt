package me.thenano.yamibo.yamibo_app.performance

import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal expect fun isFavoriteHistoryLoadPerfEnabled(): Boolean

internal expect fun emitFavoriteHistoryLoadPerfLine(line: String)

internal fun favoriteHistoryLoadPerf(
    screen: String,
    generation: Long,
    stage: String,
    details: String = "",
) {
    if (!isFavoriteHistoryLoadPerfEnabled()) return
    val suffix = details.takeIf(String::isNotBlank)?.let { "|$it" }.orEmpty()
    emitFavoriteHistoryLoadPerfLine(
        "FH_LOAD|$screen|generation=$generation|stage=$stage|at=${currentTimeMillis()}$suffix"
    )
}
