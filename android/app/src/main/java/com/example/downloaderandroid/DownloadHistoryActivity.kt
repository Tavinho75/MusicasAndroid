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
import java.text.DateFormat
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
                        Text("MusicasAndroid — Histórico")
                        Text("FASE 5 — Downloads recentes")

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
    val date = if (item.completedAtEpochMillis > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.completedAtEpochMillis))
    } else ""
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(item.title ?: "Download sem título")
        Text(if (item.status == "COMPLETED") "Concluído" else "Falhou")
        item.detail?.let { Text(it) }
        Text(date)
    }
}
