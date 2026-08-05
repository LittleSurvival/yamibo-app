package me.thenano.yamibo.yamibo_app.profile.settings.sign

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.littlesurvival.YamiboClient
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata
import me.thenano.yamibo.yamibo_app.notification.dismissActiveSignReminder
import me.thenano.yamibo.yamibo_app.repository.AndroidAuthRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidSignRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

class SignReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success()
            }
        }

        val dbFactory = DatabaseFactory(applicationContext)
        val settingsStore = AndroidSettingsStore(applicationContext)
        val appSettingsRepository = AppSettingsRepository(settingsStore)
        val yamiboClient = YamiboClient()
        val authRepository = AndroidAuthRepository(
            AndroidCookieStore(applicationContext),
            AndroidUserStore(applicationContext),
            yamiboClient
        )
        val signRepository = AndroidSignRepository(
            dbFactory = dbFactory,
            authRepository = authRepository,
            appSettingsRepository = appSettingsRepository,
            yamiboClient = yamiboClient
        )

        if (!authRepository.isLoggedIn() || authRepository.currentUser() == null) {
            return Result.success()
        }

        val isTest = inputData.getBoolean(KEY_IS_TEST, false)
        val initialSignedToday = signRepository.getKnownSignedToday()
        if (!shouldPostSignReminder(isTest, initialSignedToday)) {
            if (shouldDismissSignReminder(initialSignedToday)) {
                dismissActiveSignReminder(applicationContext)
            }
            return Result.success()
        }

        ensureChannel(applicationContext)

        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_FROM_NOTIFICATION_SIGN_IN, true)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_GO_SIGN,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(applicationContext, SignReminderActionReceiver::class.java).apply {
            action = SignReminderActionReceiver.ACTION_MUTE_REMINDERS
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            REQUEST_CODE_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle("百合會每日簽到")
            .setContentText("今天尚未完成簽到，點擊前往完成簽到任務吧！")
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(AndroidNotificationMetadata.SMALL_ICON_RES_ID, "前往簽到", openAppPendingIntent)
            .addAction(0, "不再提醒", mutePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val finalSignedToday = signRepository.getKnownSignedToday()
        if (!shouldPostSignReminder(isTest, finalSignedToday)) {
            if (shouldDismissSignReminder(finalSignedToday)) {
                dismissActiveSignReminder(applicationContext)
            }
            return Result.success()
        }

        try {
            val notificationManager = NotificationManagerCompat.from(applicationContext)
            notificationManager.notify(AndroidNotificationMetadata.SIGN_REMINDER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Logger.e("SignReminderWorker", "Permission might have been revoked just before notify", e)
        }

        return Result.success()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "每日簽到提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = AndroidNotificationMetadata.SIGN_REMINDER_CHANNEL_ID
        const val NOTIFICATION_ID = AndroidNotificationMetadata.SIGN_REMINDER_NOTIFICATION_ID
        const val KEY_IS_TEST = "is_test"
        private const val REQUEST_CODE_GO_SIGN = 1041
        private const val REQUEST_CODE_MUTE = 1042
    }
}

internal fun shouldPostSignReminder(
    isTest: Boolean,
    knownSignedToday: Boolean?,
): Boolean = isTest || knownSignedToday != true

internal fun shouldDismissSignReminder(knownSignedToday: Boolean?): Boolean =
    knownSignedToday == true
