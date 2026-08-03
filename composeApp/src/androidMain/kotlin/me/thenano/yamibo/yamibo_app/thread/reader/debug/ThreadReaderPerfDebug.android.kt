package me.thenano.yamibo.yamibo_app.thread.reader.debug

import android.util.Log

internal actual fun isThreadReaderPerfDebugEnabled(): Boolean =
    Log.isLoggable("TR_PROF", Log.DEBUG)

internal actual fun isThreadReaderReferencePlanningEnabled(): Boolean =
    Log.isLoggable("TR_PLAN_REF", Log.DEBUG)

internal actual fun emitThreadReaderPerfLogLine(line: String) {
    Log.d("TR_PROF", line)
}
