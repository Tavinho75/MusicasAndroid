package com.example.downloaderandroid

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.core.ExtractorProbeResult
import com.example.downloaderandroid.core.YtDlpDownloadEngine
import com.example.downloaderandroid.core.YtDlpExtractorEngine
import com.example.downloaderandroid.state.DownloadTaskState
import com.example.downloaderandroid.state.DownloadTaskStatus
import com.example.downloaderandroid.state.NativeDownloadTaskRepository
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.Dispatchers
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
                var phase3Status by mutableStateOf("FASE 3 pronta para testar um download real.")
                var isDownloading by mutableStateOf(false)
                val scope = rememberCoroutineScope()

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
                        Text("FASE 1.1 + FASE 2 + FASE 3", textAlign = TextAlign.Center)

                        Text(
                            text = preflightStatus,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

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
                            text = "FASE 3 — Download real de áudio",
                            textAlign = TextAlign.Center
                        )

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            label = { Text("Cole o link de uma música ou vídeo") },
                            singleLine = true,
                            enabled = !isDownloading
                        )

                        Button(
                            onClick = {
                                val requestedUrl = urlInput.trim()

                                if (requestedUrl.isBlank()) {
                                    phase3Status = "❌ FASE 3: cole uma URL antes de iniciar."
                                    return@Button
                                }

                                scope.launch {
                                    isDownloading = true
                                    phase3Status = "🔄 FASE 3: preparando download…"
                                    phase3Status = runPhase3Download(requestedUrl)
                                    isDownloading = false
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp),
                            enabled = !isDownloading
                        ) {
                            Text(if (isDownloading) "Baixando…" else "Testar download")
                        }

                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }

                        Text(
                            text = phase3Status,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                LaunchedEffect(Unit) {
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

    private suspend fun runPhase3Download(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()

        if (scheme != "http" && scheme != "https") {
            return "❌ FASE 3: informe uma URL HTTP ou HTTPS válida."
        }

        val repository = NativeDownloadTaskRepository(applicationContext)

        return try {
            repository.clear()
            val taskId = "phase3-" + UUID.randomUUID().toString()

            repository.create(
                DownloadTaskState(
                    id = taskId,
                    url = url,
                    status = DownloadTaskStatus.DRAFT,
                    title = "Download real de teste",
                    detail = "URL recebida pelo aplicativo."
                )
            )
            repository.transition(DownloadTaskStatus.ANALYZING, detail = "Preparando yt-dlp.")
            repository.transition(DownloadTaskStatus.READY, detail = "Motor pronto para iniciar o download.")
            repository.transition(DownloadTaskStatus.DOWNLOADING, detail = "yt-dlp baixando o melhor áudio disponível.")

            val result = YtDlpDownloadEngine(applicationContext).downloadBestAudio(url)

            if (result.success) {
                repository.transition(DownloadTaskStatus.PROCESSING, detail = "Download concluído; validando resultado.")
                repository.transition(DownloadTaskStatus.COMPLETED, detail = result.message)
                "✅ FASE 3: download real concluído\n\n${result.message}\n\nPasta temporária desta fase:\n${result.outputDirectory ?: "indisponível"}"
            } else {
                repository.transition(DownloadTaskStatus.FAILED, detail = result.message)
                "❌ FASE 3: download falhou\n\n${result.message}\n\nCódigo do yt-dlp: ${result.exitCode}"
            }
        } catch (error: Throwable) {
            Log.e("Phase3Download", "Falha no download real", error)
            runCatching {
                if (repository.current()?.status == DownloadTaskStatus.DOWNLOADING) {
                    repository.transition(DownloadTaskStatus.FAILED, detail = error.message ?: "Falha inesperada.")
                }
            }
            "❌ FASE 3: ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
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
