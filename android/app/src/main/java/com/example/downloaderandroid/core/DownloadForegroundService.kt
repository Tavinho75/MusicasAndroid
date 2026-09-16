package com.example.downloaderandroid.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.downloaderandroid.MainActivity
import com.example.downloaderandroid.state.DownloadTaskState
import com.example.downloaderandroid.state.DownloadTaskStatus
import com.example.downloaderandroid.state.NativeDownloadTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 4.1: keeps a real download alive when MainActivity is no longer
 * visible. The active download has its own persistent native state and a
 * foreground notification.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: NativeDownloadTaskRepository

    override fun onCreate() {
        super.onCreate()
        repository = NativeDownloadTaskRepository(
            applicationContext,
            NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_REDELIVER_INTENT

        val url = intent.getStringExtra(EXTRA_URL)?.trim()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)

        if (url.isNullOrBlank() || taskId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // The foreground notification is created before any network or yt-dlp work.
        startForegroundWithNotification("Preparando download…")

        serviceScope.launch {
            runDownload(url, taskId, startId)
        }

        // Do not tie the service lifetime to MainActivity. If Android recreates
        // the service after process pressure, the last start Intent is delivered
        // again so the download can be resumed/recovered from native state.
        return START_REDELIVER_INTENT
    }

    private suspend fun runDownload(url: String, taskId: String, startId: Int) {
        try {
            repository.clear()
            repository.create(
                DownloadTaskState(
                    id = taskId,
                    url = url,
                    status = DownloadTaskStatus.DRAFT,
                    title = "Download em segundo plano",
                    detail = "URL recebida pelo serviço.",
                )
            )
            repository.transition(
                DownloadTaskStatus.ANALYZING,
                detail = "Preparando yt-dlp em segundo plano.",
            )
            updateNotification("Analisando link…")
            repository.transition(
                DownloadTaskStatus.READY,
                detail = "Motor pronto para iniciar o download.",
            )
            repository.transition(
                DownloadTaskStatus.DOWNLOADING,
                detail = "Download em segundo plano.",
            )
            updateNotification("Baixando áudio…")

            val result = YtDlpDownloadEngine(applicationContext).downloadBestAudio(url)

            if (result.success) {
                repository.transition(
                    DownloadTaskStatus.PROCESSING,
                    detail = "Download concluído; publicando música.",
                )
                updateNotification("Finalizando música…")
                repository.transition(
                    DownloadTaskStatus.COMPLETED,
                    detail = result.message,
                )
                showFinishedNotification("Download concluído", result.message)
            } else {
                repository.transition(
                    DownloadTaskStatus.FAILED,
                    detail = result.message,
                )
                showFinishedNotification("Download falhou", result.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching {
                if (repository.current()?.status in setOf(
                        DownloadTaskStatus.ANALYZING,
                        DownloadTaskStatus.READY,
                        DownloadTaskStatus.DOWNLOADING,
                        DownloadTaskStatus.PROCESSING,
                    )
                ) {
                    repository.transition(
                        DownloadTaskStatus.FAILED,
                        detail = "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}",
                    )
                }
            }
            showFinishedNotification(
                "Download falhou",
                "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}",
            )
        } finally {
            // DETACH removes the service's foreground status but intentionally
            // leaves its notification posted. This prevents Android from
            // deleting the visible notification merely because the service is
            // stopping after completion/failure.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelf(startId)
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(
            title = "MusicasAndroid",
            text = text,
            ongoing = true,
            indeterminate = true,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                title = "MusicasAndroid",
                text = text,
                ongoing = true,
                indeterminate = true,
            ),
        )
    }

    private fun showFinishedNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                text = text,
                ongoing = false,
                indeterminate = false,
            ),
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        indeterminate: Boolean,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .setAutoCancel(!ongoing)
        .setProgress(0, 0, indeterminate)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads de música",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Downloads de áudio em segundo plano."
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.downloaderandroid.action.START_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TASK_ID = "extra_task_id"

        private const val CHANNEL_ID = "music_downloads_v2"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, url: String, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TASK_ID, taskId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
