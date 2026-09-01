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
                    val result = withContext(Dispatchers.Default) {
                        runPhase11Tests()
                    }

                    status = result
                }
            }
        }
    }

    private fun runPhase11Tests(): String {
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

        Log.i("Phase1FFmpeg", "Iniciando FFmpegKit.execute(-hide_banner -encoders)")

        return try {
            val session = FFmpegKit.execute("-hide_banner -encoders")

            val returnCode = session.returnCode
            val output = session.output ?: ""
            val success = ReturnCode.isSuccess(returnCode)
            val hasMp3Lame = output.contains("libmp3lame", ignoreCase = true)

            Log.i("Phase1FFmpeg", "FFmpeg finalizado. ReturnCode=$returnCode")
            Log.i("Phase1FFmpeg", "libmp3lame=$hasMp3Lame")
            Log.i("Phase1FFmpegOutput", output)

            when {
                !success ->
                    "❌ ExtractorEngine OK\n\n❌ FFmpeg retornou erro.\n\nCódigo: $returnCode\n\nVeja o Logcat."

                hasMp3Lame ->
                    "✅ ExtractorEngine OK\n\n✅ FFmpeg executado\n\n✅ libmp3lame ENCONTRADO"

                else ->
                    "⚠️ ExtractorEngine OK\n\n⚠️ FFmpeg executado\n\n❌ libmp3lame NÃO encontrado\n\nVeja o Logcat."
            }
        } catch (error: Throwable) {
            Log.e("Phase1FFmpeg", "Falha fatal durante teste do FFmpeg", error)

            "❌ ExtractorEngine OK\n\n❌ FFmpeg FALHOU AO EXECUTAR\n\n" +
                "${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}\n\n" +
                "Tag do Logcat: Phase1FFmpeg"
        }
    }
}
