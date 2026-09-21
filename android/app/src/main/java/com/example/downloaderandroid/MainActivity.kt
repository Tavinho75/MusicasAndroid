package com.example.downloaderandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.core.DownloadForegroundService
import com.example.downloaderandroid.core.DownloadProcessRegistry
import com.example.downloaderandroid.core.ExtractorProbeResult
import com.example.downloaderandroid.core.SealCompatibleDownloaderBackend
import com.example.downloaderandroid.core.YtDlpExtractorEngine
import com.example.downloaderandroid.state.DownloadQueueStore
import com.example.downloaderandroid.state.DownloadTaskStatus
import com.example.downloaderandroid.state.DownloadTaskState
import com.example.downloaderandroid.state.NativeDownloadTaskRepository
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val PHASE2_RESTART_CHECKPOINT_ID =
            "phase2-restart-persistence-checkpoint"

        /** Estados em que um download está em andamento do ponto de vista do usuário. */
        private val ACTIVE_DOWNLOAD_STATUSES = setOf(
            DownloadTaskStatus.DRAFT,
            DownloadTaskStatus.ANALYZING,
            DownloadTaskStatus.READY,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.PROCESSING,
        )

        /**
         * Tempo sem nenhuma atualização de estado e sem processo do yt-dlp ativo
         * a partir do qual o download é considerado interrompido pelo sistema.
         */
        private const val ORPHAN_TIMEOUT_MILLIS = 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DownloaderAndroidTheme {
                var preflightStatus by mutableStateOf(
                    if (BuildConfig.DEBUG) "Executando testes da FASE 1.1…" else ""
                )
                var urlInput by mutableStateOf("")
                var bulkMode by mutableStateOf(false)
                var phase3Status by mutableStateOf("Pronto para download em segundo plano.")
                var phase3Logs by mutableStateOf("")
                var showLogs by mutableStateOf(false)
                var isDownloading by mutableStateOf(false)
                var queueCount by mutableStateOf(0)
                var pendingNotificationUrl by mutableStateOf<String?>(null)
                val scope = rememberCoroutineScope()

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val requestedUrl = pendingNotificationUrl
                    pendingNotificationUrl = null

                    if (requestedUrl != null) {
                        scope.launch {
                            if (granted) {
                                phase3Status = "🔄 Permissão concedida; iniciando serviço…"
                                phase3Logs = "Permissão de notificações concedida.\nIniciando Foreground Service."
                            } else {
                                phase3Status = "⚠️ Notificações não autorizadas; iniciando download mesmo assim."
                                phase3Logs = "POST_NOTIFICATIONS não foi concedida. O download continuará, mas a notificação pode ficar oculta."
                            }
                            val result = startBackgroundDownload(requestedUrl)
                            phase3Status = result
                            phase3Logs = result
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("FASE 1.1 + FASE 2 + FASE 3 + FASE 4 + FASE 5 + FASE 6", textAlign = TextAlign.Center)

                        Button(
                            onClick = { showLogs = !showLogs },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(if (showLogs) "Ocultar logs das fases" else "Mostrar logs das fases")
                        }

                        if (showLogs) {
                            Text(
                                text = preflightStatus,
                                modifier = Modifier.padding(top = 12.dp),
                                textAlign = TextAlign.Start
                            )
                            if (phase3Logs.isNotBlank()) {
                                Text(
                                    text = "\n--- FASE 4.1 ---\n$phase3Logs",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                        }
                                    )
                                }
                            }
                        ) {
                            Text("Ativar / configurar notificações")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                startActivity(
                                    Intent(this@MainActivity, DownloadHistoryActivity::class.java)
                                )
                            }
                        ) {
                            Text("Abrir histórico de downloads")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                startActivity(
                                    Intent(this@MainActivity, YouTubeAuthActivity::class.java)
                                )
                            }
                        ) {
                            Text("Entrar no YouTube")
                        }

                        Text(
                            text = "Faça login antes de testar downloads autenticados.",
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "FASE 4.1 — Download em segundo plano",
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Downloads em massa",
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = bulkMode,
                                onCheckedChange = { bulkMode = it },
                            )
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { newValue: String -> urlInput = newValue },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(if (bulkMode) 180.dp else 56.dp),
                            label = {
                                Text(
                                    if (bulkMode) {
                                        "Cole um link por linha"
                                    } else {
                                        "Cole o link de uma música ou vídeo"
                                    }
                                )
                            },
                            singleLine = !bulkMode,
                            maxLines = if (bulkMode) 12 else 1,
                            minLines = if (bulkMode) 6 else 1,
                        )

                        if (isDownloading || queueCount > 0) {
                            Text(
                                text = if (queueCount == 0) {
                                    "Fila: download atual em andamento"
                                } else {
                                    "Fila: $queueCount download(s) aguardando"
                                },
                                modifier = Modifier.padding(top = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = {
                                val requestedUrls = if (bulkMode) {
                                    urlInput
                                        .lineSequence()
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .toList()
                                } else {
                                    listOf(urlInput.trim()).filter { it.isNotBlank() }
                                }

                                if (requestedUrls.isEmpty()) {
                                    phase3Status = if (bulkMode) {
                                        "❌ Cole pelo menos uma URL, uma por linha."
                                    } else {
                                        "❌ Cole uma URL antes de iniciar."
                                    }
                                    phase3Logs = phase3Status
                                    return@Button
                                }

                                scope.launch {
                                    phase3Status = "🔄 Preparando " + requestedUrls.size + " download(s)…"
                                    phase3Logs = phase3Status

                                    val queueStore = DownloadQueueStore(applicationContext)
                                    val activeNow = NativeDownloadTaskRepository(
                                        applicationContext,
                                        NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
                                    ).current()

                                     // Um estado órfão não conta como download ativo:
                                     // nesse caso o novo link inicia normalmente.
                                     val active = activeNow?.status in ACTIVE_DOWNLOAD_STATUSES &&
                                         !isOrphanDownload(activeNow)

                                     if (active) {
                                        var addedCount = 0
                                        requestedUrls.forEach { requestedUrl ->
                                            if (queueStore.add(requestedUrl) != null) {
                                                addedCount++
                                            }
                                        }
                                        queueCount = queueStore.pending().size
                                        phase3Status = if (addedCount > 0) {
                                            "➕ " + addedCount + " download(s) adicionado(s) à fila."
                                        } else {
                                            "⚠️ Nenhum link novo foi adicionado; eles já estavam na fila."
                                        }
                                        phase3Logs = "O download atual continuará normalmente e a fila será processada automaticamente."
                                        return@launch
                                    }

                                    // Start the first link immediately and put the remaining
                                    // links into the persistent FIFO queue.
                                    val firstUrl = requestedUrls.first()
                                    var queuedCount = 0
                                    requestedUrls.drop(1).forEach { queuedUrl ->
                                        if (queueStore.add(queuedUrl) != null) {
                                            queuedCount++
                                        }
                                    }
                                    queueCount = queueStore.pending().size

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        pendingNotificationUrl = firstUrl
                                        phase3Status = if (requestedUrls.size > 1) {
                                            "🔔 Primeiro download preparado; " + queuedCount + " aguardando na fila. O Android vai pedir permissão para a notificação."
                                        } else {
                                            "🔔 O Android vai pedir permissão para mostrar a notificação do download."
                                        }
                                        phase3Logs = phase3Status
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        phase3Status = if (requestedUrls.size > 1) {
                                            "🔄 Iniciando primeiro download; " + queuedCount + " na fila…"
                                        } else {
                                            "🔄 Iniciando serviço em segundo plano…"
                                        }
                                        val result = startBackgroundDownload(firstUrl)
                                        phase3Status = result
                                        phase3Logs = result
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp),
                            enabled = true
                        ) {
                            Text(
                                when {
                                    bulkMode && isDownloading -> "Adicionar downloads à fila"
                                    bulkMode -> "Baixar links"
                                    isDownloading -> "Adicionar à fila"
                                    else -> "Iniciar download"
                                }
                            )
                        }

                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(top = 16.dp)
                            )

                            Button(
                                onClick = {
                                    phase3Status = "⏹️ Cancelando e liberando o download…"
                                    phase3Logs = "Se o download travou, esta opção encerra o processo yt-dlp e libera o aplicativo para um novo download."
                                    DownloadForegroundService.cancel(applicationContext)
                                },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Text("Cancelar / destravar download")
                            }
                        }

                        Text(
                            text = phase3Status,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    val activeRepository = NativeDownloadTaskRepository(
                        applicationContext,
                        NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
                    )

                    // Um download cujo processo morreu (encerrado pelo sistema) não
                    // pode deixar o aplicativo preso em "download em andamento",
                    // recusando novos downloads indefinidamente. Só consideramos
                    // órfão quando não existe nenhum processo ativo E o estado
                    // parou de ser atualizado: um download real atualiza o estado
                    // a cada segundo.
                    while (true) {
                        val active = activeRepository.current()
                        queueCount = DownloadQueueStore(applicationContext).pending().size

                        if (isOrphanDownload(active)) {
                            runCatching {
                                activeRepository.transition(
                                    DownloadTaskStatus.FAILED,
                                    detail = "Download interrompido pelo sistema; nenhum processo ativo.",
                                )
                            }
                        }

                        when (active?.status) {
                            DownloadTaskStatus.DRAFT,
                            DownloadTaskStatus.ANALYZING,
                            DownloadTaskStatus.READY,
                            DownloadTaskStatus.DOWNLOADING,
                            DownloadTaskStatus.PROCESSING -> {
                                isDownloading = true
                                val detail = active.detail ?: "download em segundo plano…"
                                phase3Status = "🔄 $detail"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.COMPLETED -> {
                                isDownloading = false
                                val detail = active.detail ?: "Música salva na pasta Music/MusicasAndroid."
                                phase3Status = "✅ Download concluído\n\n$detail"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.FAILED -> {
                                isDownloading = false
                                val detail = active.detail ?: "Falha sem detalhes."
                                phase3Status = "❌ Download falhou"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.CANCELLED -> {
                                isDownloading = false
                                phase3Status = "⚠️ Download cancelado."
                                phase3Logs = "Download cancelado."
                            }

                            null -> {
                                isDownloading = false
                            }
                        }
                        delay(750L)
                    }
                }

                LaunchedEffect("backend-warmup") {
                    // Desempacota os binários nativos fora do caminho do usuário:
                    // a primeira música do dia não paga essa inicialização.
                    withContext(Dispatchers.IO) {
                        SealCompatibleDownloaderBackend.warmUp(applicationContext)
                    }
                }

                LaunchedEffect("preflight") {
                    // O arnês de validação das fases roda somente em compilações de
                    // desenvolvimento. Em release ele executava ffmpeg, gerava um
                    // WAV de teste e escrevia no estado de downloads a cada abertura.
                    if (!BuildConfig.DEBUG) return@LaunchedEffect

                    val phase11 = runPhase11Tests()

                    preflightStatus = if (phase11.startsWith("❌")) {
                        phase11
                    } else {
                        val restartRepository =
                            NativeDownloadTaskRepository(applicationContext)

                        val hasRestartCheckpoint =
                            restartRepository.current()?.id ==
                                PHASE2_RESTART_CHECKPOINT_ID

                        if (hasRestartCheckpoint) {
                            phase11 + "\n\n" +
                                runPhase2RestartPersistenceValidation()
                        } else {
                            val nativeState =
                                runPhase2NativeStateValidation()

                            phase11 + "\n\n" +
                                nativeState + "\n\n" +
                                runPhase2RestartPersistenceValidation()
                        }
                    }
                }
            }
        }
    }

    /**
     * Indica que o estado persistido representa um download que já não existe:
     * nenhum processo do yt-dlp ativo e nenhuma atualização de progresso recente.
     */
    private fun isOrphanDownload(state: DownloadTaskState?): Boolean {
        if (state == null || state.status !in ACTIVE_DOWNLOAD_STATUSES) return false
        if (DownloadProcessRegistry.hasActiveProcesses()) return false
        return System.currentTimeMillis() - state.updatedAtEpochMillis > ORPHAN_TIMEOUT_MILLIS
    }

    private suspend fun startBackgroundDownload(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()

        if (scheme != "http" && scheme != "https") {
            return "❌ Informe uma URL HTTP ou HTTPS válida."
        }

        val activeRepository = NativeDownloadTaskRepository(
            applicationContext,
            NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
        )
        val current = activeRepository.current()

        // Um estado órfão (processo morto pelo sistema) não pode bloquear um novo
        // download: nesse caso seguimos em frente e o serviço sobrescreve o estado.
        if (current?.status in ACTIVE_DOWNLOAD_STATUSES && !isOrphanDownload(current)) {
            return "🔄 Já existe um download em andamento. Acompanhe-o pela notificação do MusicasAndroid."
        }

        val taskId = "phase4-" + UUID.randomUUID().toString()
        return try {
            DownloadForegroundService.start(
                context = applicationContext,
                url = url,
                taskId = taskId,
            )
            "🔄 Download iniciado em segundo plano.\n\nVocê pode sair do aplicativo; o download continuará pelo serviço em primeiro plano.\n\nUma notificação será exibida durante a operação."
        } catch (error: Throwable) {
            Log.e("Phase4Background", "Não foi possível iniciar o serviço", error)
            "❌ ${error.javaClass.simpleName}: ${error.message ?: "falha ao iniciar o serviço"}"
        }
    }

    private suspend fun runPhase11Tests(): String {
        // A validação de conversão WAV -> MP3 usava o FFmpegKit, que foi removido
        // do projeto (era um segundo build completo de FFmpeg embarcado apenas
        // para o teste). O fluxo real converte com o FFmpeg do youtubedl-android.
        val extractorResult: ExtractorProbeResult = try {
            YtDlpExtractorEngine(applicationContext).probe("https://example.com/")
        } catch (error: Throwable) {
            Log.e("Phase1Probe", "Falha no ExtractorEngine", error)
            ExtractorProbeResult(false, "Falha inesperada: ${error.javaClass.simpleName}: ${error.message}")
        }

        if (!extractorResult.initialized) return "❌ ExtractorEngine falhou\n\n${extractorResult.message}"

        return "✅ ExtractorEngine OK\n\n" +
            "Backend yt-dlp inicializado com sucesso.\n" +
            "Conversão de áudio é feita pelo FFmpeg do youtubedl-android."
    }

    private fun runPhase2NativeStateValidation(): String {
        val repository = NativeDownloadTaskRepository(applicationContext)
        return try {
            repository.clear()
            val draft = DownloadTaskState("phase2-native-lifecycle-validation", "https://example.com/", DownloadTaskStatus.DRAFT, "Validação de ciclo nativo", "Tarefa criada pelo repositório Android.", 1000L)
            repository.create(draft)
            val draftRestored = repository.current() == draft
            val analyzing = repository.transition(DownloadTaskStatus.ANALYZING, "DRAFT -> ANALYZING", 2000L)
            val analyzingRestored = repository.current() == analyzing
            val ready = repository.transition(DownloadTaskStatus.READY, "ANALYZING -> READY", 3000L)
            val readyRestored = repository.current() == ready
            val downloading = repository.transition(DownloadTaskStatus.DOWNLOADING, "READY -> DOWNLOADING", 4000L)
            val downloadingRestored = repository.current() == downloading
            val processing = repository.transition(DownloadTaskStatus.PROCESSING, "DOWNLOADING -> PROCESSING", 5000L)
            val processingRestored = repository.current() == processing
            val completed = repository.transition(DownloadTaskStatus.COMPLETED, "PROCESSING -> COMPLETED", 6000L)
            val completedRestored = repository.current() == completed
            val terminalTransitionRejected = try {
                repository.transition(DownloadTaskStatus.DOWNLOADING, updatedAtEpochMillis = 7000L)
                false
            } catch (_: IllegalStateException) { true }
            repository.clear()
            val clearedCorrectly = repository.current() == null
            if (draftRestored && analyzingRestored && readyRestored && downloadingRestored && processingRestored && completedRestored && terminalTransitionRejected && clearedCorrectly) "✅ FASE 2: ciclo nativo completo + persistência OK" else "❌ FASE 2: falha na validação do ciclo nativo"
        } catch (error: Throwable) {
            Log.e("Phase2State", "Falha na validação do ciclo nativo", error)
            "❌ FASE 2: ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
        } finally { runCatching { repository.clear() } }
    }

    private fun runPhase2RestartPersistenceValidation(): String {
        val repository = NativeDownloadTaskRepository(applicationContext)
        val checkpointId = PHASE2_RESTART_CHECKPOINT_ID
        return try {
            val existing = repository.current()
            if (existing?.id == checkpointId) {
                val expected = DownloadTaskState(checkpointId, "https://example.com/restart-validation", DownloadTaskStatus.READY, "Checkpoint de persistência", "Validar após reiniciar o aplicativo.", 42000L)
                val restoredCorrectly = existing == expected
                repository.clear()
                if (restoredCorrectly) "✅ FASE 2: persistência após reinício do app OK" else "❌ FASE 2: checkpoint restaurado com dados diferentes"
            } else {
                repository.clear()
                repository.create(DownloadTaskState(checkpointId, "https://example.com/restart-validation", DownloadTaskStatus.DRAFT, "Checkpoint de persistência", "Preparando checkpoint de persistência.", 40000L))
                repository.transition(DownloadTaskStatus.ANALYZING, "Preparando checkpoint de persistência.", 41000L)
                val checkpoint = repository.transition(DownloadTaskStatus.READY, "Validar após reiniciar o aplicativo.", 42000L)
                if (repository.current() == checkpoint) "🔄 FASE 2: checkpoint salvo. Feche completamente o app e abra novamente para validar a persistência." else "❌ FASE 2: não foi possível salvar o checkpoint de reinício"
            }
        } catch (error: Throwable) {
            Log.e("Phase2Restart", "Falha no teste de persistência após reinício", error)
            "❌ FASE 2: ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
        }
    }
}
