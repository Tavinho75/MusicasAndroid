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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Foreground service that owns the download and its persistent notification. */
class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: NativeDownloadTaskRepository
    private var activeDownloadJob: Job? = null
    private var activeStartId = 0

    override fun onCreate() {
        super.onCreate()
        repository = NativeDownloadTaskRepository(applicationContext, NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (activeDownloadJob?.isActive == true) return START_STICKY
        val url = intent.getStringExtra(EXTRA_URL)?.trim()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (url.isNullOrBlank() || taskId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeStartId = startId
        startForegroundCompat(buildNotification("MusicasAndroid", "Preparando download…", null, true, null))
        activeDownloadJob = serviceScope.launch { runDownload(url, taskId, startId) }
        return START_STICKY
    }

    private suspend fun runDownload(url: String, taskId: String, startId: Int) {
        try {
            repository.clear()
            repository.create(DownloadTaskState(taskId, url, DownloadTaskStatus.DRAFT, "Download em segundo plano", "URL recebida pelo serviço."))
            repository.transition(DownloadTaskStatus.ANALYZING, detail = "Preparando yt-dlp em segundo plano.")
            updateNotification("Analisando link…", null, null)
            repository.transition(DownloadTaskStatus.READY, detail = "Motor pronto para iniciar o download.")
            repository.transition(DownloadTaskStatus.DOWNLOADING, detail = "Download em segundo plano.")
            updateNotification("Baixando áudio…", null, null)

            val result = YtDlpDownloadEngine(applicationContext).downloadBestAudio(url) { progress, etaSeconds, line ->
                val safeProgress = progress.coerceIn(0f, 100f)
                val progressText = formatProgress(safeProgress, etaSeconds)
                runCatching { repository.updateProgress(safeProgress, etaSeconds, line.trim().takeIf { it.isNotBlank() } ?: progressText) }
                updateNotification(progressText, safeProgress, etaSeconds)
            }

            if (result.success) {
                repository.transition(DownloadTaskStatus.PROCESSING, detail = "Download concluído; publicando música.")
                updateNotification("Finalizando música…", 100f, 0L)
                repository.transition(DownloadTaskStatus.COMPLETED, detail = result.message)
                showFinishedNotification("Download concluído", result.message)
            } else {
                repository.transition(DownloadTaskStatus.FAILED, detail = result.message)
                showFinishedNotification("Download falhou", result.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching {
                if (repository.current()?.status in setOf(DownloadTaskStatus.ANALYZING, DownloadTaskStatus.READY, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.PROCESSING)) {
                    repository.transition(DownloadTaskStatus.FAILED, detail = "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}")
                }
            }
            showFinishedNotification("Download falhou", "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}")
        } finally {
            if (activeStartId == startId) {
                activeDownloadJob = null
                stopForegroundCompat(remove = true)
                stopSelf(startId)
            }
        }
    }

    private fun updateNotification(text: String, progressPercent: Float?, etaSeconds: Long?) {
        getSystemService(NotificationManager::class.java).notify(
            FOREGROUND_NOTIFICATION_ID,
            buildNotification("MusicasAndroid", text, progressPercent, true, etaSeconds),
        )
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(remove)
        }
    }

    private fun showFinishedNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(FINISHED_NOTIFICATION_ID, buildNotification(title, text, 100f, false, 0L))
    }

    private fun buildNotification(title: String, text: String, progressPercent: Float?, ongoing: Boolean, etaSeconds: Long?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setProgress(100, progressPercent?.toInt()?.coerceIn(0, 100) ?: 0, progressPercent == null)
            .apply {
                if (etaSeconds != null && etaSeconds >= 0L && progressPercent != null) setSubText("ETA ${formatEta(etaSeconds)}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun formatProgress(progress: Float, etaSeconds: Long): String = String.format(Locale.getDefault(), "Baixando áudio… %.0f%% • ETA %s", progress, if (etaSeconds >= 0L) formatEta(etaSeconds) else "calculando…")

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remainingSeconds = safe % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds) else String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads de música", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Downloads de áudio em segundo plano."
                setShowBadge(false)
            }
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { repository.transition(DownloadTaskStatus.FAILED, detail = "Serviço em primeiro plano atingiu o limite de execução do Android.") }
        stopSelf(startId)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { activeDownloadJob?.cancel(); serviceScope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.example.downloaderandroid.action.START_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val CHANNEL_ID = "music_downloads_v5"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
        private const val FINISHED_NOTIFICATION_ID = 4102

        fun start(context: Context, url: String, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
