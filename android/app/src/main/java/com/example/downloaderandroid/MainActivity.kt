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
                var status by mutableStateOf("Inicializando teste do yt-dlp…")

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
                            text = "FASE 1.1 — Teste do ExtractorEngine",
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
                    val result = try {
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
                        "initialized=${result.initialized}; message=${result.message}"
                    )

                    withContext(Dispatchers.Main) {
                        status = if (result.initialized) {
                            "✅ ${result.message}"
                        } else {
                            "❌ ${result.message}"
                        }
                    }
                }
            }
        }
    }
}
