package me.thenano.yamibo.yamibo_app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey

class DownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(buildNotification(0, 1, "準備下載"))
        if (observer?.isActive != true) {
            val repository = AndroidDownloadRuntime.repository
            if (repository == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            observer = scope.launch {
                repository.queue.collectLatest { entries ->
                    val active = entries.filter {
                        it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading
                    }
                    if (active.isEmpty()) {
                        stopSelf()
                        return@collectLatest
                    }
                    val current = active.firstOrNull { it.status == DownloadStatus.Downloading }
                        ?: active.first()
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            completed = 0,
                            total = active.size,
                            text = current.notificationText(),
                        ),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(completed: Int, total: Int, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle("Yamibo 下載")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), total <= 0)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "下載",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "yamibo_downloads"
        private const val NOTIFICATION_ID = 42030

        fun startIntent(context: Context): Intent =
            Intent(context, DownloadForegroundService::class.java)
    }
}

private fun DownloadQueueEntry.notificationText(): String {
    val page = (key as? ThreadPageDownloadKey)?.page
    return if (page != null) "$title · 第 $page 頁" else title
}
