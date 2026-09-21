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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.example.downloaderandroid.DownloadHistoryActivity
import com.example.downloaderandroid.state.DownloadHistoryStore
import com.example.downloaderandroid.state.DownloadQueueStore
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

/**
 * Serviço em primeiro plano que é dono do download e da sua notificação.
 *
 * Responsabilidades deste arquivo:
 * - conduzir a fila persistente (um dono único do avanço/encerramento);
 * - manter a notificação sempre coerente com o serviço em primeiro plano;
 * - reconciliar estado órfão após morte do processo;
 * - persistir progresso e notificação com frequência limitada.
 */
class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: NativeDownloadTaskRepository
    private lateinit var historyStore: DownloadHistoryStore
    private lateinit var queueStore: DownloadQueueStore
    private var activeDownloadJob: Job? = null
    private var activeStartId = 0
    private var activeTaskId: String? = null
    private var inProgressQueueItemId: String? = null
    private val progressThrottle = ProgressThrottle()

    override fun onCreate() {
        super.onCreate()
        repository = NativeDownloadTaskRepository(
            applicationContext,
            NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
        )
        historyStore = DownloadHistoryStore(applicationContext)
        queueStore = DownloadQueueStore(applicationContext)
        createNotificationChannel()

        // Pastas temporárias de tarefas interrompidas ou de versões anteriores.
        serviceScope.launch { DownloadStorage.cleanupStale(applicationContext) }
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
                activeState.status in ACTIVE_STATUSES

        val url = if (isRestartAfterProcessDeath) activeState?.url else requestedUrl
        val taskId = if (isRestartAfterProcessDeath) activeState?.id else requestedTaskId

        if (url.isNullOrBlank() || taskId.isNullOrBlank()) {
            if (intent == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        activeStartId = startId
        activeTaskId = taskId
        progressThrottle.reset()

        // Re-publica a notificação imediatamente sempre que o Android recria o
        // serviço, evitando um estado persistido sem serviço em primeiro plano.
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
        var handledByQueue = false
        var cancelRequested = false
        val flowStartedAt = SystemClock.elapsedRealtime()
        try {
            if (resumeExisting) {
                repository.current()?.let { state ->
                    repository.updateProgress(
                        state.progressPercent ?: 0f,
                        state.etaSeconds ?: -1L,
                        "Serviço retomado pelo Android; continuando o download.",
                    )
                }
            } else {
                repository.clear()
                repository.create(
                    DownloadTaskState(
                        taskId,
                        url,
                        DownloadTaskStatus.DRAFT,
                        "Download em segundo plano",
                        "URL recebida pelo serviço.",
                    )
                )
            }

            repository.transition(DownloadTaskStatus.ANALYZING, detail = "Preparando yt-dlp em segundo plano.")
            updateNotification("Analisando link…", null, null)

            val engine = YtDlpDownloadEngine(applicationContext)
            val resolvedTitle = engine.resolveTitle(url)
            if (!resolvedTitle.isNullOrBlank()) {
                repository.current()?.let { state ->
                    repository.save(
                        state.copy(
                            title = resolvedTitle,
                            detail = "Música identificada; preparando download.",
                        )
                    )
                }
            }

            repository.transition(DownloadTaskStatus.READY, detail = "Motor pronto para iniciar o download.")
            repository.transition(DownloadTaskStatus.DOWNLOADING, detail = "Download em segundo plano.")
            updateNotification(
                if (!resolvedTitle.isNullOrBlank()) "Baixando: " + resolvedTitle else "Baixando áudio…",
            )

            val result = engine.downloadBestAudio(
                url = url,
                taskId = taskId,
                onProgress = { progress, etaSeconds, line ->
                    val safeProgress = progress.coerceIn(0f, 100f)
                    if (progressThrottle.shouldPublish(safeProgress)) {
                        val progressText = formatProgress(safeProgress, etaSeconds)
                        runCatching {
                            repository.updateProgress(
                                safeProgress,
                                etaSeconds,
                                line.trim().takeIf { it.isNotBlank() } ?: progressText,
                            )
                        }
                        updateNotification(progressText, safeProgress, etaSeconds)
                    }
                },
                onTitle = { title ->
                    repository.current()?.let { state ->
                        if (state.title != title) repository.save(state.copy(title = title))
                    }
                    updateNotification("Baixando: " + title)
                },
            )

            if (result.success) {
                repository.transition(DownloadTaskStatus.PROCESSING, detail = "Download concluído; publicando música.")
                updateNotification("Finalizando música…", 100f, 0L)
                val finalState = repository.transition(DownloadTaskStatus.COMPLETED, detail = result.message).copy(
                    title = result.title ?: repository.current()?.title ?: "Download concluído",
                    fileSizeBytes = result.fileSizeBytes,
                    folder = result.folder,
                )
                repository.save(finalState)
                historyStore.add(finalState)
            } else {
                val failedState = repository.transition(DownloadTaskStatus.FAILED, detail = result.message)
                historyStore.add(failedState)
                showFinishedNotification("Download falhou", result.message)
            }
        } catch (cancelled: CancellationException) {
            // O cancelamento é tratado por requestCancel, que registra o estado
            // e conduz a fila. Avançar a fila aqui também duplicaria o trabalho
            // e poderia marcar a próxima música como cancelada.
            cancelRequested = true
            throw cancelled
        } catch (error: Throwable) {
            val detail = "${error.javaClass.simpleName}: ${error.message ?: "erro inesperado"}"
            runCatching {
                if (repository.current()?.status in ACTIVE_STATUSES) {
                    historyStore.add(repository.transition(DownloadTaskStatus.FAILED, detail = detail))
                }
            }
            val failedTitle = repository.current()?.title
            showFinishedNotification(
                "Download falhou",
                if (!failedTitle.isNullOrBlank()) failedTitle + " — " + detail else detail,
            )
        } finally {
            Log.i(
                YtDlpDownloadEngine.TIMING_TAG,
                "fluxoCompleto=${SystemClock.elapsedRealtime() - flowStartedAt}ms cancelado=$cancelRequested",
            )

            if (activeStartId == startId && activeTaskId == taskId) {
                activeDownloadJob = null
                if (!cancelRequested) {
                    handledByQueue = true
                    concludeCurrentTask(startId)
                }
            }
        }

        // Garante que interrupções comuns (sem exceção) também liberem a fila.
        if (!handledByQueue && !cancelRequested) concludeCurrentTask(startId)
    }

    /**
     * Dono único do fluxo "próximo da fila ou encerrar serviço".
     *
     * Só encerra o serviço se o startId ainda for o ativo, evitando que um
     * cancelamento apague a notificação de um download que já começou.
     */
    private fun concludeCurrentTask(startId: Int) {
        if (activeStartId != startId) return

        // O item só sai da fila agora, com o download já em estado terminal.
        // Se o processo tivesse morrido antes, ele continuaria na fila.
        finishQueueItem()

        val next = queueStore.list().firstOrNull()
        if (next != null) {
            inProgressQueueItemId = next.id
            queueStore.markInProgress(next.id)
            val nextTaskId = "phase4-" + java.util.UUID.randomUUID().toString()
            repository.clear()
            updateNotification("Próximo download da fila…")
            start(context = applicationContext, url = next.url, taskId = nextTaskId)
            return
        }

        showFinishedNotification("MusicasAndroid", "Downloads concluídos")
        stopForegroundCompat(remove = true)
        activeDownloadJob = null
        activeTaskId = null
        stopSelf(startId)
    }

    private fun requestCancel(startId: Int) {
        // Mata exatamente os processos desta tarefa; sem adivinhação de índices.
        activeTaskId?.let(::destroyDownloadProcesses)

        serviceScope.launch {
            val job = activeDownloadJob
            activeDownloadJob = null
            job?.cancel()
            job?.join()

            runCatching {
                val current = repository.current()
                if (current != null && current.status in ACTIVE_STATUSES) {
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

            // Se um download da fila já assumiu (startId diferente), não
            // interrompe a notificação dele.
            if (activeStartId != startId) return@launch

            finishQueueItem()

            val next = queueStore.list().firstOrNull()
            if (next != null) {
                inProgressQueueItemId = next.id
                queueStore.markInProgress(next.id)
                repository.clear()
                updateNotification("Próximo download da fila…")
                start(
                    context = applicationContext,
                    url = next.url,
                    taskId = "phase4-" + java.util.UUID.randomUUID().toString(),
                )
            } else {
                stopForegroundCompat(remove = true)
                activeTaskId = null
                stopSelf(startId)
            }
        }
    }

    /** Retira da fila o item que acabou de chegar a um estado terminal. */
    private fun finishQueueItem() {
        val itemId = inProgressQueueItemId ?: return
        inProgressQueueItemId = null
        runCatching { queueStore.remove(itemId) }
    }

    private fun destroyDownloadProcesses(taskId: String) {
        val processIds = DownloadProcessRegistry.activeIdsFor(taskId)
            .ifEmpty { DownloadProcessRegistry.snapshot() }

        processIds.forEach { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }

    /**
     * Atualiza a notificação em primeiro plano.
     *
     * Não aplica throttle aqui: quem chama já decide a frequência (o callback de
     * progresso usa [progressThrottle], as demais chamadas são pontuais).
     */
    private fun updateNotification(
        text: String,
        progressPercent: Float? = null,
        etaSeconds: Long? = null,
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(
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
            @Suppress("DEPRECATION")
            stopForeground(remove)
        }
    }

    private fun showFinishedNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(FINISHED_NOTIFICATION_ID, buildNotification(title, text, 100f, false, 0L))
    }

    private fun buildNotification(
        title: String,
        text: String,
        progressPercent: Float?,
        ongoing: Boolean,
        etaSeconds: Long?,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
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
            if (etaSeconds != null && etaSeconds >= 0L && progressPercent != null) {
                setSubText("ETA ${formatEta(etaSeconds)}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, DownloadHistoryActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun formatProgress(progress: Float, etaSeconds: Long): String = String.format(
        Locale.getDefault(),
        "Baixando áudio… %.0f%% • ETA %s",
        progress,
        if (etaSeconds >= 0L) formatEta(etaSeconds) else "calculando…",
    )

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remainingSeconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads de música", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Downloads de áudio em segundo plano."
                setShowBadge(false)
            }
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Não encerra nem cancela o serviço quando a tarefa sai dos recentes:
        // o download continua pertencendo a este serviço.
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeTaskId?.let(::destroyDownloadProcesses)
        runCatching {
            repository.transition(
                DownloadTaskStatus.FAILED,
                detail = "Serviço em primeiro plano atingiu o limite de execução do Android.",
            )
        }
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeTaskId?.let(::destroyDownloadProcesses)
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

        private val ACTIVE_STATUSES = setOf(
            DownloadTaskStatus.DRAFT,
            DownloadTaskStatus.ANALYZING,
            DownloadTaskStatus.READY,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.PROCESSING,
        )

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
