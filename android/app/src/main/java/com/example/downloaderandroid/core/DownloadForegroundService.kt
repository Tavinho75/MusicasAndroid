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
import com.yausername.youtubedl_android.YoutubeDL
import com.example.downloaderandroid.DownloadHistoryActivity
import com.example.downloaderandroid.state.DownloadHistoryStore
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
    private lateinit var historyStore: DownloadHistoryStore
    private var activeDownloadJob: Job? = null
    private var activeStartId = 0

    override fun onCreate() {
        super.onCreate()
        repository = NativeDownloadTaskRepository(applicationContext, NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME)
        historyStore = DownloadHistoryStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestCancel(startId)
            return START_NOT_STICKY
        }

        if (activeDownloadJob?.isActive == true) return START_STICKY

        val activeState = repository.current()
        val requestedUrl = intent?.getStringExtra(EXTRA_URL)?.trim()
        val requestedTaskId = intent?.getStringExtra(EXTRA_TASK_ID)

        val isRestartAfterProcessDeath =
            intent == null &&
                activeState != null &&
                activeState.status in setOf(
                    DownloadTaskStatus.DRAFT,
                    DownloadTaskStatus.ANALYZING,
                    DownloadTaskStatus.READY,
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.PROCESSING,
                )

        val url = if (isRestartAfterProcessDeath) activeState?.url else requestedUrl
        val taskId = if (isRestartAfterProcessDeath) activeState?.id else requestedTaskId

        if (url.isNullOrBlank() || taskId.isNullOrBlank()) {
            if (intent == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        activeStartId = startId

        // Re-publish the foreground notification immediately whenever Android
        // recreates this sticky service. This prevents a stale persisted task
        // from existing without a visible foreground-service notification.
        startForegroundCompat(
            buildNotification(
                "MusicasAndroid",
                if (isRestartAfterProcessDeath) "Retomando download em segundo plano…" else "Preparando download…",
                activeState?.progressPercent,
                true,
                activeState?.etaSeconds,
            )
        )

        activeDownloadJob = serviceScope.launch {
            runDownload(url, taskId, startId, resumeExisting = isRestartAfterProcessDeath)
        }
        return START_STICKY
    }

    private suspend fun runDownload(url: String, taskId: String, startId: Int, resumeExisting: Boolean = false) {
        try {
            if (!resumeExisting) {
                repository.clear()
                repository.create(DownloadTaskState(taskId, url, DownloadTaskStatus.DRAFT, "Download em segundo plano", "URL recebida pelo serviço."))
            } else {
                repository.current()?.let { state ->
                    repository.updateProgress(
                        state.progressPercent ?: 0f,
                        state.etaSeconds ?: -1L,
                        "Serviço retomado pelo Android; continuando o download.",
                    )
                }
            }
            repository.transition(DownloadTaskStatus.ANALYZING, detail = "Preparando yt-dlp em segundo plano.")
            updateNotification("Analisando link…", null, null)
            repository.transition(DownloadTaskStatus.READY, detail = "Motor pronto para iniciar o download.")
            repository.transition(DownloadTaskStatus.DOWNLOADING, detail = "Download em segundo plano.")
            updateNotification("Baixando áudio…", null, null)

            val result = YtDlpDownloadEngine(applicationContext).downloadBestAudio(url, taskId) { progress, etaSeconds, line ->
                val safeProgress = progress.coerceIn(0f, 100f)
                val progressText = formatProgress(safeProgress, etaSeconds)
                runCatching { repository.updateProgress(safeProgress, etaSeconds, line.trim().takeIf { it.isNotBlank() } ?: progressText) }
                updateNotification(progressText, safeProgress, etaSeconds)
            }

            if (result.success) {
                val completed = repository.transition(DownloadTaskStatus.PROCESSING, detail = "Download concluído; publicando música.")
                updateNotification("Finalizando música…", 100f, 0L)
                val finalState = repository.transition(DownloadTaskStatus.COMPLETED, detail = result.message)
                historyStore.add(finalState.copy(title = finalState.title ?: result.message))
                showFinishedNotification("Download concluído", result.message)
            } else {
                val failedState = repository.transition(DownloadTaskStatus.FAILED, detail = result.message)
                historyStore.add(failedState)
                showFinishedNotification("Download falhou", result.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}"
            runCatching {
                if (repository.current()?.status in setOf(DownloadTaskStatus.ANALYZING, DownloadTaskStatus.READY, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.PROCESSING)) {
                    historyStore.add(repository.transition(DownloadTaskStatus.FAILED, detail = detail))
                }
            }
            showFinishedNotification("Download falhou", detail)
        } finally {
            if (activeStartId == startId) {
                activeDownloadJob = null
                stopForegroundCompat(remove = true)
                stopSelf(startId)
            }
        }
    }

    private fun requestCancel(startId: Int) {
        val state = repository.current()
        val taskId = state?.id

        if (taskId != null) {
            destroyDownloadProcesses(taskId)
        }

        serviceScope.launch {
            activeDownloadJob?.cancel()
            activeDownloadJob?.join()

            runCatching {
                val current = repository.current()
                if (current != null && current.status in setOf(
                        DownloadTaskStatus.DRAFT,
                        DownloadTaskStatus.ANALYZING,
                        DownloadTaskStatus.READY,
                        DownloadTaskStatus.DOWNLOADING,
                        DownloadTaskStatus.PROCESSING,
                    )
                ) {
                    historyStore.add(
                        repository.transition(
                            DownloadTaskStatus.CANCELLED,
                            detail = "Download cancelado pelo usuário.",
                        )
                    )
                }
                repository.clear()
            }

            showFinishedNotification("Download cancelado", "O download foi interrompido. Você já pode iniciar outro.")
            stopForegroundCompat(remove = true)
            stopSelf(startId)
        }
    }

    private fun destroyDownloadProcesses(taskId: String) {
        repeat(4) { attemptIndex ->
            runCatching {
                YoutubeDL.getInstance().destroyProcessById("phase4-$taskId-$attemptIndex")
            }
        }
    }

    private fun updateNotification(text: String, progressPercent: Float?, etaSeconds: Long?) {
        getSystemService(NotificationManager::class.java).notify(FOREGROUND_NOTIFICATION_ID, buildNotification("MusicasAndroid", text, progressPercent, true, etaSeconds))
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        else { @Suppress("DEPRECATION") stopForeground(remove) }
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
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, DownloadHistoryActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads de música", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Downloads de áudio em segundo plano."
            setShowBadge(false)
        })
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop or cancel the foreground service when the app task is
        // removed from recents. The download remains owned by this service.
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.current()?.id?.let(::destroyDownloadProcesses)
        runCatching { repository.transition(DownloadTaskStatus.FAILED, detail = "Serviço em primeiro plano atingiu o limite de execução do Android.") }
        stopSelf(startId)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        repository.current()?.id?.let(::destroyDownloadProcesses)
        activeDownloadJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.downloaderandroid.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.downloaderandroid.action.CANCEL_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val CHANNEL_ID = "music_downloads_v5"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
        private const val FINISHED_NOTIFICATION_ID = 4102

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

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
