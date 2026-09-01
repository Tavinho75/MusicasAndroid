package com.example.downloaderandroid

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.downloaderandroid.core.YtDlpExtractorEngine
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val phase1Scope = CoroutineScope(Dispatchers.Default)

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

                phase1Scope.launch {
                    val extractorResult = try {
                        val extractor = YtDlpExtractorEngine(applicationContext)
                        extractor.probe("https://example.com/")
                    } catch (error: Exception) {
                        com.example.downloaderandroid.core.ExtractorProbeResult(
                            initialized = false,
                            message = "Falha inesperada: ${error.javaClass.simpleName}: ${error.message}"
                        )
                    }

                    Log.i(
                        "Phase1Probe",
                        "initialized=${extractorResult.initialized}; message=${extractorResult.message}"
                    )

                    withContext(Dispatchers.Main) {
                        status = if (extractorResult.initialized) {
                            "✅ ExtractorEngine OK\n\nExecutando teste do FFmpeg…"
                        } else {
                            "❌ ExtractorEngine falhou\n\n${extractorResult.message}"
                        }
                    }

                    val ffmpegSession = try {
                        FFmpegKit.execute("-hide_banner -encoders")
                    } catch (error: Exception) {
                        Log.e("Phase1FFmpeg", "Falha ao iniciar FFmpeg", error)
                        null
                    }

                    val finalMessage = if (ffmpegSession == null) {
                        "❌ FFmpeg não conseguiu iniciar. Veja o Logcat."
                    } else {
                        val returnCode = ffmpegSession.returnCode
                        val output = ffmpegSession.output ?: ""
                        val success = ReturnCode.isSuccess(returnCode)
                        val hasMp3Lame = output.contains("libmp3lame", ignoreCase = true)

                        Log.i("Phase1FFmpeg", "ReturnCode=$returnCode")
                        Log.i("Phase1FFmpeg", "libmp3lame=$hasMp3Lame")
                        Log.i("Phase1FFmpegOutput", output)

                        when {
                            !success ->
                                "❌ FFmpeg executou, mas retornou erro.\n\nCódigo: $returnCode\n\nVeja o Logcat."

                            hasMp3Lame ->
                                "✅ ExtractorEngine OK\n\n✅ FFmpeg executado\n\n✅ libmp3lame ENCONTRADO"

                            else ->
                                "⚠️ FFmpeg executado\n\n❌ libmp3lame NÃO encontrado\n\nVeja o Logcat."
                        }
                    }

                    withContext(Dispatchers.Main) {
                        status = finalMessage
                    }
                }
            }
        }
    }
}
