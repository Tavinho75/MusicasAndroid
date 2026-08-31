package com.example.downloaderandroid

import android.os.Bundle
import android.util.Log
import com.example.downloaderandroid.core.YtDlpExtractorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.downloaderandroid.ui.theme.DownloaderAndroidTheme

class MainActivity : ComponentActivity() {
    private val phase1Scope = CoroutineScope(Dispatchers.Default)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val extractor = YtDlpExtractorEngine(applicationContext)
        phase1Scope.launch {
            val result = extractor.probe("https://example.com/")
            Log.i("Phase1Probe", result.message)
        }
        setContent {
            DownloaderAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DownloaderAndroidTheme {
        Greeting("Android")
    }
}