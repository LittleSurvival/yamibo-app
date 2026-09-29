package me.thenano.yamibo.yamibo_app.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

/** Process-owned jobs survive a hidden window. One job per key, with wall-clock wake recovery. */
internal class DesktopJobs(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val onFailure: (String) -> Unit,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val periods = mutableMapOf<String, Long>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val running = active.asStateFlow()

    @Synchronized fun start(key: String, action: suspend () -> Unit) {
        if (jobs[key]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure(key) }
        }
        jobs[key] = job
        active.value = jobs.keys.toSet()
        // Also clean up cancellation before a lazy/dispatched body ever starts.
        job.invokeOnCompletion { finished(key, job) }
        job.start()
    }

    @Synchronized fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        periods.remove(key)
        active.value = jobs.keys.toSet()
    }

    @Synchronized private fun finished(key: String, job: Job) {
        if (jobs[key] === job) {
            jobs.remove(key)
            periods.remove(key)
            active.value = jobs.keys.toSet()
        }
    }

    @Synchronized fun schedule(key: String, intervalMillis: Long?, action: suspend () -> Unit) {
        if (intervalMillis == null || intervalMillis <= 0) { cancel(key); return }
        if (periods[key] == intervalMillis && jobs[key]?.isActive == true) return
        cancel(key)
        periods[key] = intervalMillis
        start(key) {
            val settingKey = "desktop.next.$key"
            var next = settings.getString(settingKey, "").toLongOrNull()
                ?.takeIf { it <= System.currentTimeMillis() + intervalMillis }
                ?: (System.currentTimeMillis() + intervalMillis)
            settings.putString(settingKey, next.toString())
            while (currentCoroutineContext().isActive) {
                val remaining = next - System.currentTimeMillis()
                if (remaining > 0) { delay(remaining.coerceAtMost(30_000)); continue }
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure(key) }
                next = System.currentTimeMillis() + intervalMillis
                settings.putString(settingKey, next.toString())
            }
        }
    }
}
