package me.thenano.yamibo.yamibo_app.thread.reader.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

internal expect fun isThreadReaderPerfDebugEnabled(): Boolean

/** Debug-only reference planner switch; production defaults to the optimized planner. */
internal expect fun isThreadReaderReferencePlanningEnabled(): Boolean

internal expect fun emitThreadReaderPerfLogLine(line: String)

@Composable
internal fun DebugRecomposeProbe(tag: String, key: () -> String) {
    if (!isThreadReaderPerfDebugEnabled()) return

    val resolvedKey = key()
    val count = remember(tag, resolvedKey) { mutableIntStateOf(0) }
    DisposableEffect(tag, resolvedKey) {
        emitThreadReaderPerfLogLine("TR_PROF|enter|$tag|$resolvedKey")
        onDispose {
            emitThreadReaderPerfLogLine("TR_PROF|dispose|$tag|$resolvedKey|count=${count.intValue}")
        }
    }
    SideEffect {
        count.intValue += 1
        val current = count.intValue
        if (current <= 3 || current % 10 == 0) {
            emitThreadReaderPerfLogLine("TR_PROF|recompose|$tag|$resolvedKey|count=$current")
        }
    }
}

internal fun debugPerfLog(message: String) {
    if (isThreadReaderPerfDebugEnabled()) {
        emitThreadReaderPerfLogLine("TR_PROF|event|$message")
    }
}
