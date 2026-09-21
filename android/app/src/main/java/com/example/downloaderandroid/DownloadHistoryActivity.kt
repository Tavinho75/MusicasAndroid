package com.example.downloaderandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.downloaderandroid.state.DownloadHistoryItem
import com.example.downloaderandroid.state.DownloadHistoryStore
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.Date

/** Phase 5: persistent local history of completed/failed downloads. */
class DownloadHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DownloaderAndroidTheme {
                var items by mutableStateOf<List<DownloadHistoryItem>>(emptyList())
                val store = DownloadHistoryStore(applicationContext)

                LaunchedEffect(Unit) {
                    while (true) {
                        items = store.list()
                        delay(1000L)
                    }
                }

                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("← Voltar")
                        }

                        Text("MusicasAndroid — Histórico")
                        Text("Downloads recentes")

                        if (items.isEmpty()) {
                            Text("Nenhum download finalizado ainda.")
                        } else {
                            items.forEach { item ->
                                HistoryItem(item)
                            }
                            Button(
                                onClick = { store.clear(); items = emptyList() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Limpar histórico")
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HistoryItem(item: DownloadHistoryItem) {
    val status = when (item.status) {
        "COMPLETED" -> "Concluído"
        "CANCELLED" -> "Cancelado"
        "FAILED" -> "Falhou"
        else -> item.status
    }
    val elapsedMillis = if (item.startedAtEpochMillis > 0L && item.completedAtEpochMillis >= item.startedAtEpochMillis) {
        item.completedAtEpochMillis - item.startedAtEpochMillis
    } else 0L
    val elapsed = formatElapsed(elapsedMillis)
    val size = formatSize(item.fileSizeBytes)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Download $status:")
        Text("${item.title ?: "Download sem título"} - $elapsed")
        Text("${item.folder} - $size")
        if (item.status == "FAILED" || item.status == "CANCELLED") {
            item.detail?.let { detail ->
                Text("Erro: " + detail.take(220))
            }
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    if (milliseconds <= 0L) return "--"
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%02d:%02d".format(minutes, remaining)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "--"
    if (bytes < 1024L * 1024L) return "%.1f KB".format(bytes / 1024.0)
    return "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
