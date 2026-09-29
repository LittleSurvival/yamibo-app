package me.thenano.yamibo.yamibo_app

import java.util.logging.Level

actual object Logger {
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        java.util.logging.Logger.getLogger("yamibo.$tag").log(level, message, throwable)
    }
    actual fun v(tag: String, message: String, throwable: Throwable?) = log(Level.FINEST, tag, message, throwable)
    actual fun d(tag: String, message: String, throwable: Throwable?) = log(Level.FINE, tag, message, throwable)
    actual fun i(tag: String, message: String, throwable: Throwable?) = log(Level.INFO, tag, message, throwable)
    actual fun w(tag: String, message: String, throwable: Throwable?) = log(Level.WARNING, tag, message, throwable)
    actual fun e(tag: String, message: String, throwable: Throwable?) = log(Level.SEVERE, tag, message, throwable)
}
