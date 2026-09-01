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
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

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
                            text = "FASE 1.1 — ExtractorEngine + FFmpeg",
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
                    status = runPhase11Tests()
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
