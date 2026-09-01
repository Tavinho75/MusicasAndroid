package com.example.downloaderandroid

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.downloaderandroid.core.ExtractorProbeResult
import com.example.downloaderandroid.core.YtDlpExtractorEngine
import com.example.downloaderandroid.state.NativeDownloadTaskRepository
import com.example.downloaderandroid.state.DownloadTaskState
import com.example.downloaderandroid.state.DownloadTaskStatus
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                var status by mutableStateOf("Executando testes da FASE 1.1…")

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "FASE 1.1 + FASE 2 — Base nativa",
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = status,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    val phase11 = runPhase11Tests()

                    status = if (phase11.startsWith("❌")) {
                        phase11
                    } else {
                        /*
                         * A validação do ciclo nativo usa repository.clear().
                         * Portanto, em uma abertura posterior ela não pode rodar
                         * antes de verificar o checkpoint de reinício, ou apagaria
                         * justamente o estado que precisamos restaurar.
                         */
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

    private suspend fun runPhase11Tests(): String {
        val extractorResult: ExtractorProbeResult = try {
            val extractor = YtDlpExtractorEngine(applicationContext)
            extractor.probe("https://example.com/")
        } catch (error: Throwable) {
            Log.e("Phase1Probe", "Falha no ExtractorEngine", error)
            ExtractorProbeResult(
                initialized = false,
                message = "Falha inesperada: ${error.javaClass.simpleName}: ${error.message}"
            )
        }

        Log.i(
            "Phase1Probe",
            "initialized=${extractorResult.initialized}; message=${extractorResult.message}"
        )

        if (!extractorResult.initialized) {
            return "❌ ExtractorEngine falhou\n\n${extractorResult.message}"
        }

        return try {
            val ffmpegResult = withContext(Dispatchers.Default) {
                runFfmpegValidation()
            }

            ffmpegResult
        } catch (error: Throwable) {
            Log.e("Phase1FFmpeg", "Falha fatal durante os testes do FFmpeg", error)

            "❌ ExtractorEngine OK\n\n❌ FFmpeg FALHOU AO EXECUTAR\n\n" +
                "${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}\n\n" +
                "Tag do Logcat: Phase1FFmpeg"
        }
    }

    /**
     * Validação mínima da FASE 2.
     * Não cria uma fila nem inicia downloads. Apenas confirma que o estado
     * da aplicação pode permanecer na camada Android e sobreviver a uma
     * leitura posterior do SharedPreferences.
     */
    /**
     * Validação incremental da FASE 2.
     *
     * Ainda não existe fila, Foreground Service ou download real. O teste
     * valida a fronteira de repositório nativa e todo o ciclo permitido de
     * uma única tarefa, incluindo persistência após cada mudança de estado.
     */
    private fun runPhase2NativeStateValidation(): String {
        val repository = NativeDownloadTaskRepository(applicationContext)

        return try {
            repository.clear()

            val draft = DownloadTaskState(
                id = "phase2-native-lifecycle-validation",
                url = "https://example.com/",
                status = DownloadTaskStatus.DRAFT,
                title = "Validação de ciclo nativo",
                detail = "Tarefa criada pelo repositório Android.",
                updatedAtEpochMillis = 1_000L
            )

            repository.create(draft)
            val draftRestored = repository.current() == draft

            val analyzing = repository.transition(
                target = DownloadTaskStatus.ANALYZING,
                detail = "DRAFT -> ANALYZING",
                updatedAtEpochMillis = 2_000L
            )
            val analyzingRestored = repository.current() == analyzing

            val ready = repository.transition(
                target = DownloadTaskStatus.READY,
                detail = "ANALYZING -> READY",
                updatedAtEpochMillis = 3_000L
            )
            val readyRestored = repository.current() == ready

            val downloading = repository.transition(
                target = DownloadTaskStatus.DOWNLOADING,
                detail = "READY -> DOWNLOADING",
                updatedAtEpochMillis = 4_000L
            )
            val downloadingRestored = repository.current() == downloading

            val processing = repository.transition(
                target = DownloadTaskStatus.PROCESSING,
                detail = "DOWNLOADING -> PROCESSING",
                updatedAtEpochMillis = 5_000L
            )
            val processingRestored = repository.current() == processing

            val completed = repository.transition(
                target = DownloadTaskStatus.COMPLETED,
                detail = "PROCESSING -> COMPLETED",
                updatedAtEpochMillis = 6_000L
            )
            val completedRestored = repository.current() == completed

            val terminalTransitionRejected = try {
                repository.transition(
                    target = DownloadTaskStatus.DOWNLOADING,
                    updatedAtEpochMillis = 7_000L
                )
                false
            } catch (_: IllegalStateException) {
                true
            }

            repository.clear()
            val clearedCorrectly = repository.current() == null

            Log.i(
                "Phase2State",
                "draft=$draftRestored; analyzing=$analyzingRestored; " +
                    "ready=$readyRestored; downloading=$downloadingRestored; " +
                    "processing=$processingRestored; completed=$completedRestored; " +
                    "terminalRejected=$terminalTransitionRejected; " +
                    "cleared=$clearedCorrectly"
            )

            if (
                draftRestored &&
                analyzingRestored &&
                readyRestored &&
                downloadingRestored &&
                processingRestored &&
                completedRestored &&
                terminalTransitionRejected &&
                clearedCorrectly
            ) {
                "✅ FASE 2: ciclo nativo completo + persistência OK"
            } else {
                "❌ FASE 2: falha na validação do ciclo nativo"
            }
        } catch (error: Throwable) {
            Log.e("Phase2State", "Falha na validação do ciclo nativo", error)
            "❌ FASE 2: ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
        } finally {
            try {
                repository.clear()
            } catch (_: Throwable) {
                // O resultado principal já foi registrado; a limpeza não deve
                // mascarar uma falha anterior.
            }
        }
    }

    /**
     * Teste prático de persistência entre execuções do aplicativo.
     *
     * Primeira execução: grava um checkpoint READY e solicita reinício.
     * Execução seguinte: restaura o checkpoint, compara todos os campos e
     * limpa o armazenamento somente depois da confirmação.
     *
     * Isso ainda não implementa fila nem serviço em primeiro plano.
     */
    private fun runPhase2RestartPersistenceValidation(): String {
        val repository = NativeDownloadTaskRepository(applicationContext)
        val checkpointId = PHASE2_RESTART_CHECKPOINT_ID

        return try {
            val existing = repository.current()

            if (existing?.id == checkpointId) {
                val expected = DownloadTaskState(
                    id = checkpointId,
                    url = "https://example.com/restart-validation",
                    status = DownloadTaskStatus.READY,
                    title = "Checkpoint de persistência",
                    detail = "Validar após reiniciar o aplicativo.",
                    updatedAtEpochMillis = 42_000L
                )

                val restoredCorrectly = existing == expected
                repository.clear()

                Log.i(
                    "Phase2Restart",
                    "checkpointRestored=$restoredCorrectly; restored=$existing"
                )

                if (restoredCorrectly) {
                    "✅ FASE 2: persistência após reinício do app OK"
                } else {
                    "❌ FASE 2: checkpoint restaurado com dados diferentes"
                }
            } else {
                repository.clear()

                val draftCheckpoint = DownloadTaskState(
                    id = checkpointId,
                    url = "https://example.com/restart-validation",
                    status = DownloadTaskStatus.DRAFT,
                    title = "Checkpoint de persistência",
                    detail = "Preparando checkpoint de persistência.",
                    updatedAtEpochMillis = 40_000L
                )

                // O repositório corretamente exige que uma nova tarefa comece
                // em DRAFT. Para chegar ao checkpoint READY usamos as
                // transições oficiais da máquina de estados.
                repository.create(draftCheckpoint)

                repository.transition(
                    target = DownloadTaskStatus.ANALYZING,
                    detail = "Preparando checkpoint de persistência.",
                    updatedAtEpochMillis = 41_000L
                )

                val checkpoint = repository.transition(
                    target = DownloadTaskStatus.READY,
                    detail = "Validar após reiniciar o aplicativo.",
                    updatedAtEpochMillis = 42_000L
                )

                val savedCorrectly = repository.current() == checkpoint

                Log.i(
                    "Phase2Restart",
                    "checkpointCreated=$savedCorrectly; checkpoint=$checkpoint"
                )

                if (savedCorrectly) {
                    "🔄 FASE 2: checkpoint salvo. Feche completamente o app e abra novamente para validar a persistência."
                } else {
                    "❌ FASE 2: não foi possível salvar o checkpoint de reinício"
                }
            }
        } catch (error: Throwable) {
            Log.e("Phase2Restart", "Falha no teste de persistência após reinício", error)
            "❌ FASE 2: ${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
        }
    }


    private fun runFfmpegValidation(): String {
        Log.i("Phase1FFmpeg", "Teste 1: iniciando -hide_banner -encoders")

        val encoderSession = FFmpegKit.execute("-hide_banner -encoders")
        val encoderOutput = encoderSession.output ?: ""
        val encoderSuccess = ReturnCode.isSuccess(encoderSession.returnCode)
        val hasMp3Lame = encoderOutput.contains("libmp3lame", ignoreCase = true)

        Log.i("Phase1FFmpeg", "Teste 1 finalizado. ReturnCode=${encoderSession.returnCode}")
        Log.i("Phase1FFmpeg", "libmp3lame=$hasMp3Lame")
        Log.i("Phase1FFmpegOutput", encoderOutput)

        if (!encoderSuccess) {
            return "❌ ExtractorEngine OK\n\n❌ FFmpeg retornou erro ao listar encoders.\n\nVeja o Logcat."
        }

        if (!hasMp3Lame) {
            return "⚠️ ExtractorEngine OK\n\n⚠️ FFmpeg executado\n\n❌ libmp3lame NÃO encontrado"
        }

        val testDirectory = File(cacheDir, "phase11-media-test").apply {
            mkdirs()
        }
        val wavFile = File(testDirectory, "input-test.wav")
        val mp3File = File(testDirectory, "output-test.mp3")

        wavFile.delete()
        mp3File.delete()

        // Gera primeiro um arquivo WAV real. Depois ele é convertido para MP3
        // em uma segunda execução, validando um fluxo de entrada -> conversão.
        val generateWavCommand =
            "-hide_banner -y -f lavfi -i sine=frequency=440:sample_rate=44100:duration=2 " +
                "-c:a pcm_s16le \"${wavFile.absolutePath}\""

        Log.i("Phase1FFmpeg", "Teste 2: gerando WAV de entrada")
        val wavSession = FFmpegKit.execute(generateWavCommand)

        if (!ReturnCode.isSuccess(wavSession.returnCode) ||
            !wavFile.exists() ||
            wavFile.length() <= 0
        ) {
            Log.e("Phase1FFmpeg", "Falha ao gerar WAV. ReturnCode=${wavSession.returnCode}")
            Log.e("Phase1FFmpegOutput", wavSession.output ?: "")

            return "❌ ExtractorEngine OK\n\n✅ libmp3lame encontrado\n\n" +
                "❌ Falha ao gerar arquivo de áudio de teste"
        }

        Log.i(
            "Phase1FFmpeg",
            "WAV criado: ${wavFile.absolutePath}; bytes=${wavFile.length()}"
        )

        val convertMp3Command =
            "-hide_banner -y -i \"${wavFile.absolutePath}\" " +
                "-c:a libmp3lame -b:a 192k \"${mp3File.absolutePath}\""

        Log.i("Phase1FFmpeg", "Teste 3: convertendo WAV -> MP3 com libmp3lame")
        val mp3Session = FFmpegKit.execute(convertMp3Command)

        if (!ReturnCode.isSuccess(mp3Session.returnCode)) {
            Log.e("Phase1FFmpeg", "Falha na conversão MP3. ReturnCode=${mp3Session.returnCode}")
            Log.e("Phase1FFmpegOutput", mp3Session.output ?: "")

            return "❌ ExtractorEngine OK\n\n✅ libmp3lame encontrado\n\n" +
                "❌ Conversão WAV → MP3 falhou"
        }

        val fileExists = mp3File.exists()
        val fileSize = if (fileExists) mp3File.length() else 0L
        val correctExtension = mp3File.extension.equals("mp3", ignoreCase = true)
        val formatValid = validateMp3Format(mp3File)
        val playbackValid = validateMp3Playback(mp3File)

        Log.i(
            "Phase1MP3",
            "exists=$fileExists; bytes=$fileSize; extension=$correctExtension; " +
                "format=$formatValid; playback=$playbackValid; path=${mp3File.absolutePath}"
        )

        return if (
            fileExists &&
            fileSize > 0 &&
            correctExtension &&
            formatValid &&
            playbackValid
        ) {
            "✅ ExtractorEngine OK\n\n" +
                "✅ FFmpeg executado\n\n" +
                "✅ libmp3lame ENCONTRADO\n\n" +
                "✅ WAV → MP3 convertido\n\n" +
                "✅ MP3 válido e reproduzível\n\n" +
                "Tamanho: $fileSize bytes"
        } else {
            "⚠️ Conversão executada, mas validação incompleta\n\n" +
                "Arquivo existe: $fileExists\n" +
                "Tamanho: $fileSize bytes\n" +
                "Extensão MP3: $correctExtension\n" +
                "Formato válido: $formatValid\n" +
                "Reprodução válida: $playbackValid\n\n" +
                "Veja Logcat: Phase1MP3"
        }
    }

    private fun validateMp3Format(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null

        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val mime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                .orEmpty()

            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            Log.i("Phase1MP3", "mime=$mime; durationMs=$duration")

            mime.contains("audio", ignoreCase = true) && duration > 0
        } catch (error: Throwable) {
            Log.e("Phase1MP3", "Formato MP3 inválido", error)
            false
        } finally {
            try {
                retriever?.release()
            } catch (releaseError: Throwable) {
                Log.w("Phase1MP3", "Falha ao liberar MediaMetadataRetriever", releaseError)
            }
        }
    }

    private fun validateMp3Playback(file: File): Boolean {
        var player: MediaPlayer? = null

        return try {
            player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.prepare()

            val valid = player.duration > 0
            Log.i("Phase1MP3", "MediaPlayer preparado; durationMs=${player.duration}")
            valid
        } catch (error: Throwable) {
            Log.e("Phase1MP3", "MP3 não pôde ser preparado para reprodução", error)
            false
        } finally {
            try {
                player?.release()
            } catch (releaseError: Throwable) {
                Log.w("Phase1MP3", "Falha ao liberar MediaPlayer", releaseError)
            }
        }
    }
}
