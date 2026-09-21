package com.example.downloaderandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.core.DownloadForegroundService
import com.example.downloaderandroid.core.ExtractorProbeResult
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
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val PHASE2_RESTART_CHECKPOINT_ID =
            "phase2-restart-persistence-checkpoint"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DownloaderAndroidTheme {
                var preflightStatus by mutableStateOf("Executando testes da FASE 1.1…")
                var urlInput by mutableStateOf("")
                var bulkMode by mutableStateOf(false)
                var phase3Status by mutableStateOf("FASE 4.1 pronta para download em segundo plano.")
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
                                phase3Status = "🔄 FASE 4.1: permissão concedida; iniciando serviço…"
                                phase3Logs = "Permissão de notificações concedida.\nIniciando Foreground Service."
                            } else {
                                phase3Status = "⚠️ FASE 4.1: notificações não autorizadas; iniciando download mesmo assim."
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
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            label = {
                                Text(
                                    text = if (bulkMode) {
                                        "Cole um link por linha"
                                    } else {
                                        "Cole o link de uma música ou vídeo"
                                    }
                                },
                            },
                            singleLine = !bulkMode,
                            minLines = if (bulkMode) 6 else 1,
                            maxLines = if (bulkMode) 12 else 1,
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
                                        "❌ FASE 4.1: cole pelo menos uma URL, uma por linha."
                                    } else {
                                        "❌ FASE 4.1: cole uma URL antes de iniciar."
                                    }
                                    phase3Logs = phase3Status
                                    return@Button
                                }

                                scope.launch {
                                    phase3Status = "🔄 FASE 4.1: preparando " + requestedUrls.size + " download(s)…"
                                    phase3Logs = phase3Status

                                    val queueStore = DownloadQueueStore(applicationContext)
                                    val activeNow = NativeDownloadTaskRepository(
                                        applicationContext,
                                        NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
                                    ).current()

                                    val active = activeNow?.status in setOf(
                                        DownloadTaskStatus.DRAFT,
                                        DownloadTaskStatus.ANALYZING,
                                        DownloadTaskStatus.READY,
                                        DownloadTaskStatus.DOWNLOADING,
                                        DownloadTaskStatus.PROCESSING,
                                    )

                                    if (active) {
                                        var addedCount = 0
                                        requestedUrls.forEach { requestedUrl ->
                                            if (queueStore.add(requestedUrl) != null) {
                                                addedCount++
                                            }
                                        }
                                        queueCount = queueStore.list().size
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
                                    queueCount = queueStore.list().size

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
                                            "🔄 FASE 4.1: iniciando serviço em segundo plano…"
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

                    while (true) {
                        val active = activeRepository.current()
                        queueCount = DownloadQueueStore(applicationContext).list().size
                        when (active?.status) {
                            DownloadTaskStatus.DRAFT,
                            DownloadTaskStatus.ANALYZING,
                            DownloadTaskStatus.READY,
                            DownloadTaskStatus.DOWNLOADING,
                            DownloadTaskStatus.PROCESSING -> {
                                isDownloading = true
                                val detail = active.detail ?: "download em segundo plano…"
                                phase3Status = "🔄 FASE 4.1: $detail"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.COMPLETED -> {
                                isDownloading = false
                                val detail = active.detail ?: "Música salva na pasta Music/MusicasAndroid."
                                phase3Status = "✅ FASE 4.1: download concluído\n\n$detail"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.FAILED -> {
                                isDownloading = false
                                val detail = active.detail ?: "Falha sem detalhes."
                                phase3Status = "❌ FASE 4.1: download falhou"
                                phase3Logs = detail
                            }

                            DownloadTaskStatus.CANCELLED -> {
                                isDownloading = false
                                phase3Status = "⚠️ FASE 4.1: download cancelado."
                                phase3Logs = "Download cancelado."
                            }

                            null -> {
                                isDownloading = false
                            }
                        }
                        delay(750L)
                    }
                }

                LaunchedEffect("preflight") {
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

    private suspend fun startBackgroundDownload(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()

        if (scheme != "http" && scheme != "https") {
            return "❌ FASE 4.1: informe uma URL HTTP ou HTTPS válida."
        }

        val activeRepository = NativeDownloadTaskRepository(
            applicationContext,
            NativeDownloadTaskRepository.ACTIVE_DOWNLOAD_PREFERENCES_NAME,
        )
        val current = activeRepository.current()

        if (current?.status in setOf(
                DownloadTaskStatus.DRAFT,
                DownloadTaskStatus.ANALYZING,
                DownloadTaskStatus.READY,
                DownloadTaskStatus.DOWNLOADING,
                DownloadTaskStatus.PROCESSING,
            )
        ) {
            return "🔄 Já existe um download em andamento. Acompanhe-o pela notificação do MusicasAndroid."
        }

        val taskId = "phase4-" + UUID.randomUUID().toString()
        return try {
            DownloadForegroundService.start(
                context = applicationContext,
                url = url,
                taskId = taskId,
            )
            "🔄 FASE 4.1: download iniciado em segundo plano.\n\nVocê pode sair do aplicativo; o download continuará pelo serviço em primeiro plano.\n\nUma notificação será exibida durante a operação."
        } catch (error: Throwable) {
            Log.e("Phase4Background", "Não foi possível iniciar o serviço", error)
            "❌ FASE 4.1: ${error.javaClass.simpleName}: ${error.message ?: "falha ao iniciar o serviço"}"
        }
    }

    private suspend fun runPhase11Tests(): String {
        val extractorResult: ExtractorProbeResult = try {
            YtDlpExtractorEngine(applicationContext).probe("https://example.com/")
        } catch (error: Throwable) {
            Log.e("Phase1Probe", "Falha no ExtractorEngine", error)
            ExtractorProbeResult(false, "Falha inesperada: ${error.javaClass.simpleName}: ${error.message}")
        }
        if (!extractorResult.initialized) return "❌ ExtractorEngine falhou\n\n${extractorResult.message}"
        return try {
            withContext(Dispatchers.Default) { runFfmpegValidation() }
        } catch (error: Throwable) {
            Log.e("Phase1FFmpeg", "Falha fatal durante os testes do FFmpeg", error)
            "❌ ExtractorEngine OK\n\n❌ FFmpeg FALHOU AO EXECUTAR\n\n${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}\n\nTag do Logcat: Phase1FFmpeg"
        }
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

    private fun runFfmpegValidation(): String {
        val encoderSession = FFmpegKit.execute("-hide_banner -encoders")
        val encoderOutput = encoderSession.output ?: ""
        if (!ReturnCode.isSuccess(encoderSession.returnCode)) return "❌ ExtractorEngine OK\n\n❌ FFmpeg retornou erro ao listar encoders.\n\nVeja o Logcat."
        if (!encoderOutput.contains("libmp3lame", ignoreCase = true)) return "⚠️ ExtractorEngine OK\n\n⚠️ FFmpeg executado\n\n❌ libmp3lame NÃO encontrado"
        val testDirectory = File(cacheDir, "phase11-media-test").apply { mkdirs() }
        val wavFile = File(testDirectory, "input-test.wav")
        val mp3File = File(testDirectory, "output-test.mp3")
        wavFile.delete(); mp3File.delete()
        val wavSession = FFmpegKit.execute("-hide_banner -y -f lavfi -i sine=frequency=440:sample_rate=44100:duration=2 -c:a pcm_s16le \"${wavFile.absolutePath}\"")
        if (!ReturnCode.isSuccess(wavSession.returnCode) || !wavFile.exists() || wavFile.length() <= 0L) return "❌ ExtractorEngine OK\n\n✅ libmp3lame encontrado\n\n❌ Falha ao gerar arquivo de áudio de teste"
        val mp3Session = FFmpegKit.execute("-hide_banner -y -i \"${wavFile.absolutePath}\" -c:a libmp3lame -b:a 192k \"${mp3File.absolutePath}\"")
        if (!ReturnCode.isSuccess(mp3Session.returnCode)) return "❌ ExtractorEngine OK\n\n✅ libmp3lame encontrado\n\n❌ Conversão WAV → MP3 falhou"
        val fileExists = mp3File.exists()
        val fileSize = if (fileExists) mp3File.length() else 0L
        val formatValid = validateMp3Format(mp3File)
        val playbackValid = validateMp3Playback(mp3File)
        return if (fileExists && fileSize > 0 && mp3File.extension.equals("mp3", true) && formatValid && playbackValid) "✅ ExtractorEngine OK\n\n✅ FFmpeg executado\n\n✅ libmp3lame ENCONTRADO\n\n✅ WAV → MP3 convertido\n\n✅ MP3 válido e reproduzível\n\nTamanho: $fileSize bytes" else "⚠️ Conversão executada, mas validação incompleta\n\nArquivo existe: $fileExists\nTamanho: $fileSize bytes\nFormato válido: $formatValid\nReprodução válida: $playbackValid"
    }

    private fun validateMp3Format(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mime.contains("audio", true) && duration > 0
        } catch (error: Throwable) {
            Log.e("Phase1MP3", "Formato MP3 inválido", error); false
        } finally { runCatching { retriever?.release() } }
    }

    private fun validateMp3Playback(file: File): Boolean {
        var player: MediaPlayer? = null
        return try {
            player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.duration > 0
        } catch (error: Throwable) {
            Log.e("Phase1MP3", "MP3 não pôde ser preparado para reprodução", error); false
        } finally { runCatching { player?.release() } }
    }
}
