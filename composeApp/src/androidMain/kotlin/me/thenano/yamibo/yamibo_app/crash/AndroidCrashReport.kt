package me.thenano.yamibo.yamibo_app.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.favorite.sync.AndroidAppForegroundTracker
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class AndroidCrashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
        }
        if (processName != applicationInfo.processName) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashLock = Any()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            synchronized(crashLock) {
                try {
                    AndroidCrashReport.record(this, thread, error)
                    if (AndroidAppForegroundTracker.isForeground()) {
                        startActivity(Intent(this, CrashReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (_: Throwable) {
                    // The system handler must run even if storage or activity launch fails.
                } finally {
                    if (previous != null) previous.uncaughtException(thread, error)
                    else {
                        Process.killProcess(Process.myPid())
                        exitProcess(10)
                    }
                }
            }
        }
    }
}

internal object AndroidCrashReport {
    private const val REPORT = "latest-crash.txt"
    private const val VIEWED = "viewed-crash.txt"
    private const val LOG_TAG = "YamiboCrash"
    private const val LOG_MARKER = "Capturing uncaught exception"
    private const val LOG_UNAVAILABLE = "Logcat unavailable."
    private const val MAX_FRAMES = 100
    private const val MAX_CAUSES = 8
    private const val REPORT_BYTES = 64 * 1024
    private const val RESERVED_LOG_BYTES = 12 * 1024
    private const val LOG_HEADING = "\nRecent process logcat (selected tags and events)\n"

    fun readPending(context: Context): String? =
        runCatching { File(context.noBackupFilesDir, REPORT).readText() }.getOrNull()
            ?.takeUnless { it.substringBefore('\n') == viewedId(context) }

    private fun viewedId(context: Context): String? =
        runCatching { File(context.noBackupFilesDir, VIEWED).readText() }.getOrNull()

    fun hasPending(context: Context): Boolean {
        val latest = File(context.noBackupFilesDir, REPORT)
        if (!latest.exists()) return false
        val id = runCatching { latest.bufferedReader().use { it.readLine() } }.getOrNull() ?: return false
        return id != viewedId(context)
    }

    fun markViewed(context: Context, report: String) {
        runCatching { write(context, VIEWED, report.substringBefore('\n')) }
    }

    private fun write(context: Context, name: String, text: String) {
        val destination = File(context.noBackupFilesDir, name)
        val temporary = File(destination.parentFile, "$name.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Os.rename(temporary.path, destination.path)
        } finally {
            temporary.delete()
        }
    }

    fun record(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val pid = Process.myPid()
        val header = buildString {
            appendLine("Crash ID: " + UUID.randomUUID())
            appendLine("Time: " + Date(now))
            appendLine("App: " + AppVersion.displayName + " (" + AppVersion.VersionCode + ")")
            appendLine("Android API: " + Build.VERSION.SDK_INT)
            appendLine("Process: " + context.applicationInfo.processName)
            appendLine("PID: $pid")
            appendLine("Thread: " + if (thread == Looper.getMainLooper().thread) "main" else "background #" + thread.id)
            appendLine()
            var cause: Throwable? = error
            for (depth in 0 until MAX_CAUSES) {
                val current = cause ?: break
                if (depth > 0) append("Caused by: ")
                append(current.javaClass.name)
                val detail = current.message?.takeIf { it.length <= 300 }
                if (detail != null && safeExceptionDetails.any { it.matches(detail) }) append(": $detail")
                appendLine()
                val stack = current.stackTrace
                stack.take(MAX_FRAMES).forEach { appendLine("    at " + it.toString().take(400)) }
                if (stack.size > MAX_FRAMES) appendLine("    …")
                cause = current.cause
            }
            if (cause != null) appendLine("    …")
        }
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val maxHeader = REPORT_BYTES - RESERVED_LOG_BYTES - LOG_HEADING.length
        val boundedHeader = if (headerBytes.size <= maxHeader) header else {
            val marker = "[Stack truncated to reserve space for logcat]\n"
            var end = maxHeader - marker.length
            while (end > 0 && headerBytes[end - 1] != '\n'.code.toByte()) end--
            String(headerBytes, 0, end, Charsets.UTF_8) + marker
        }
        val prefix = boundedHeader + LOG_HEADING
        // Keep the exception even if the process dies while starting or reading logcat.
        write(context, REPORT, prefix + LOG_UNAVAILABLE)
        val log = runCatching {
            Log.i(LOG_TAG, LOG_MARKER)
            captureLogcat(pid)
        }.getOrNull()
        if (log != null) runCatching {
            val budget = REPORT_BYTES - prefix.toByteArray(Charsets.UTF_8).size
            val logBytes = log.toByteArray(Charsets.UTF_8)
            val recent = if (logBytes.size <= budget) log else {
                val marker = "[Earlier logcat entries truncated at 64 KiB report limit]\n"
                var start = logBytes.size - budget + marker.length
                while (start < logBytes.size && logBytes[start - 1] != '\n'.code.toByte()) start++
                marker + String(logBytes, start, logBytes.size - start, Charsets.UTF_8)
            }
            write(context, REPORT, prefix + recent)
        }
    }

    // Exception messages may contain request bodies, cookies, URLs or user content.
    private val safeExceptionDetails = listOf(
        Regex("""Index -?\d+ out of bounds for length \d+"""),
        Regex("""Index: -?\d+, Size: \d+"""),
        Regex("""(offset|start|end)\(-?\d+\) (is out of bounds|should be in range) \[?-?\d+(?:, |\.\.)-?\d+\]?\.?"""),
        Regex("""Can't represent a size of \d+ in Constraints"""),
        Regex("""Can't downgrade database from version \d+ to \d+"""),
    )

    private val logLine = Regex("""^\s*(\d+)\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^: ]+)\s*:\s?(.*)$""")
    private val skippedFrames = Regex("""^Skipped (\d{1,8}) frames!.*""")
    private val imageEvents = listOf("Sharing image failed", "Image request returned")
    private val readerEvents = listOf("enter", "dispose", "recompose", "event")
    private val loggerTag = Regex("""Yamibo_(?:VERBOSE|DEBUG|INFO|WARN|ERROR)\(([A-Za-z0-9_.-]{1,80})\)""")
    private val backExit = Regex("""Double Tapped\(Interval=\d{1,4}\) , Exit\.""")
    private val navigationEvents = setOf(
        "snapshot_start", "snapshot_skip", "snapshot_built", "snapshot_saved",
        "restore_start", "restore_item_decoded", "restore_item_failed", "restore_finished",
    )
    private val readerFailures = listOf(
        "Image geometry unavailable", "Failed to update reader cover", "Failed to persist reading history",
        "Failed to persist catalog reading history", "Failed to persist chapter progress",
        "Failed to build reading history snapshot", "Failed to build chapter progress updates",
        "Failed to load reader page", "Failed to load initial reader page",
    )

    private fun captureLogcat(pid: Int): String {
        val now = System.currentTimeMillis()
        val since = now - minOf(60_000L, SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val child = AtomicReference<java.lang.Process?>()
        val read = FutureTask<String> {
            val process = ProcessBuilder(
                "/system/bin/logcat", "-b", "main", "-b", "system", "-b", "crash",
                "-d", "-t", "240", "--pid=$pid", "-v", "threadtime", "-v", "epoch",
                "*:V",
            ).redirectErrorStream(true).start()
            child.set(process)
            try {
                if (Thread.currentThread().isInterrupted) return@FutureTask ""
                process.inputStream.bufferedReader().useLines { lines ->
                    val entries = lines.take(1024).mapNotNull { line ->
                        val fields = logLine.matchEntire(line) ?: return@mapNotNull null
                        val (seconds, millis, processId, tid, level, tag, message) = fields.destructured
                        val timestamp = seconds.toLong() * 1000 + millis.toLong()
                        if (processId != pid.toString() || timestamp !in since..System.currentTimeMillis()) return@mapNotNull null
                        val event = when (tag) {
                            LOG_TAG -> if (message == LOG_MARKER) LOG_MARKER else null
                            "Choreographer" -> skippedFrames.matchEntire(message)?.let { "Skipped " + it.groupValues[1] + " frames" }
                            "OpenGLRenderer", "HWUI" -> if (message.startsWith("Davey!")) "Slow frame" else null
                            "AndroidRuntime" -> if (message.startsWith("FATAL EXCEPTION:")) "FATAL EXCEPTION" else null
                            "TR_PROF" -> readerEvents.firstOrNull { message.startsWith("TR_PROF|$it|") }
                            "FH_LOAD" -> if (message.startsWith("FH_LOAD|")) "Load stage" else null
                            "YamiboImageAction" -> imageEvents.firstOrNull { message.startsWith(it) }
                            else -> {
                                val source = loggerTag.matchEntire(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                                when (source) {
                                    "AndroidBackHandler" -> message.takeIf(backExit::matches)
                                    "NavigationRestore" -> message.substringBefore(' ').takeIf(navigationEvents::contains)
                                    "ThreadReaderScreen" -> readerFailures.firstOrNull(message::startsWith)
                                    else -> null
                                } ?: "[message withheld]"
                            }
                        }
                        if (event == null) return@mapNotNull null
                        "$seconds.$millis $processId/$tid $level $tag: $event"
                    }.toList()
                    when {
                        entries.isEmpty() -> "No matching process logcat entries available."
                        entries.size > 120 -> "[Earlier entries truncated]\n" + entries.takeLast(120).joinToString("\n")
                        else -> entries.joinToString("\n")
                    }
                }
            } finally {
                process.destroy()
            }
        }
        Thread(read, "crash-logcat").apply { isDaemon = true }.start()
        return try {
            read.get(1000, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            "Logcat unavailable (capture timed out)."
        } finally {
            read.cancel(true)
            child.get()?.destroy()
        }
    }
}
