package com.tiktokdownloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tiktokdownloader.R
import com.tiktokdownloader.ui.MainActivity
import com.tiktokdownloader.util.VideoDownloader
import kotlinx.coroutines.*

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var videoDownloader: VideoDownloader

    override fun onCreate() {
        super.onCreate()
        videoDownloader = VideoDownloader(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_VIDEO_URL) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "tiktok_video"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_downloading))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                videoDownloader.downloadVideo(url, fileName) { progress ->
                    updateNotification(progress)
                }
                showCompleteNotification()
            } catch (e: Exception) {
                showErrorNotification(e.message ?: "Unknown error")
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun updateNotification(progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_downloading))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setContentText("$progress%")
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompleteNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_complete))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ダウンロード失敗")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
    }
}
