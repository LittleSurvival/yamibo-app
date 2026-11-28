@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package me.thenano.yamibo.yamibo_app.crash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.thenano.yamibo.yamibo_app.components.theme.contrastAgainst
import me.thenano.yamibo.yamibo_app.components.theme.ensureContrastOn
import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import kotlin.native.getUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/** Installed once during Swift app initialization, before starting Compose. */
fun installIOSCrashHook(record: (String, String) -> Unit) {
    val previous = getUnhandledExceptionHook()
    setUnhandledExceptionHook { error ->
        runCatching {
            record(error::class.simpleName ?: "Throwable", describeCrash(error))
        }
        if (previous != null) previous(error) else terminateWithUnhandledException(error)
    }
}

private fun describeCrash(error: Throwable): String = buildString {
    var cause: Throwable? = error
    repeat(8) { depth ->
        val current = cause ?: return@buildString
        if (depth > 0) append("Caused by: ")
        appendLine(current::class.qualifiedName ?: "Throwable")
        // Native stack entries describe code. Drop compiler source paths and omit exception
        // messages, which may contain request URLs, credentials or forum content.
        val stack = current.getStackTrace()
        for (frame in stack.take(80)) {
            if (length >= 48_000) {
                appendLine("[Stack truncated]")
                return@buildString
            }
            appendLine(frame.substringBefore('/').take(500).trimEnd())
        }
        if (stack.size > 80) appendLine("[Stack truncated]")
        cause = current.cause
    }
    if (cause != null) appendLine("[Cause chain truncated]")
}

/** A snapshot for SwiftUI without starting the Compose view controller. */
class IOSCrashReportPalette {
    private val scheme = AppSettingsRepository(IOSSettingsStore()).themeScheme.getValue().toScheme()
    private val surfaceColor = Color(scheme.creamSurface)
    private val textColor = Color(
        if (scheme === YamiboColorScheme.Default) scheme.brownDeep else scheme.textDark
    ).ensureContrastOn(surfaceColor, 4.5)
    private val primaryColor = Color(scheme.brownPrimary)

    val background = Color(scheme.creamBackground).toArgb()
    val surface = surfaceColor.toArgb()
    val deep = Color(scheme.brownDeep).toArgb()
    val foreground = textColor.toArgb()
    val action = if (primaryColor.contrastAgainst(surfaceColor) >= 4.5) primaryColor.toArgb() else foreground
    val onDeep = Color.White.ensureContrastOn(Color(scheme.brownDeep), 4.5).toArgb()
    val line = Color(scheme.brownLight).toArgb()
    val dark = Color.White.contrastAgainst(surfaceColor) > Color.Black.contrastAgainst(surfaceColor)
}
