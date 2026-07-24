package me.thenano.yamibo.yamibo_app.download

import android.content.Context
import androidx.core.content.ContextCompat
import me.thenano.yamibo.yamibo_app.repository.download.DownloadBackgroundController
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus

class AndroidDownloadBackgroundController(
    context: Context,
) : DownloadBackgroundController {
    private val appContext = context.applicationContext

    override fun onQueueChanged(entries: List<DownloadQueueEntry>) {
        val hasWork = entries.any {
            it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading
        }
        if (hasWork) {
            ContextCompat.startForegroundService(
                appContext,
                DownloadForegroundService.startIntent(appContext),
            )
        }
    }
}

object AndroidDownloadRuntime {
    @Volatile
    var repository: DownloadRepository? = null
}
