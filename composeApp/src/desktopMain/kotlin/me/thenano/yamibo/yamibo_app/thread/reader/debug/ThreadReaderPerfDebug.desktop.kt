package me.thenano.yamibo.yamibo_app.thread.reader.debug

import me.thenano.yamibo.yamibo_app.Logger

internal actual fun isThreadReaderPerfDebugEnabled() = java.lang.Boolean.getBoolean("yamibo.debug.readerPerf")
internal actual fun isThreadReaderReferencePlanningEnabled() = java.lang.Boolean.getBoolean("yamibo.debug.referencePlanning")
internal actual fun emitThreadReaderPerfLogLine(line: String) = Logger.d("ThreadReaderPerf", line)
