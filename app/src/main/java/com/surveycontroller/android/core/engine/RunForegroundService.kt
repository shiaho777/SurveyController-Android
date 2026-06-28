package com.surveycontroller.android.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 运行期前台服务：保证任务在后台/锁屏时不被系统杀死。
 */
class RunForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在执行问卷任务…"
        startForeground(NOTIFICATION_ID, buildNotification(text))
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SurveyController")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "survey_run"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_TEXT = "text"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "任务运行", NotificationManager.IMPORTANCE_LOW),
                    )
                }
            }
        }

        fun start(context: Context, text: String) {
            val intent = Intent(context, RunForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunForegroundService::class.java))
        }

        /** 任务完成/失败的结果通知（应用不在前台时尤其有用）。 */
        fun notifyResult(context: Context, title: String, text: String) {
            ensureChannel(context)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .build()
            try {
                androidx.core.app.NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, notif)
            } catch (e: SecurityException) {
                // 未授予通知权限时忽略
            }
        }
    }
}
