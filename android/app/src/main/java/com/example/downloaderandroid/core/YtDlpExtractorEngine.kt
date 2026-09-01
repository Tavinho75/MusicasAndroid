package com.example.downloaderandroid.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException

class YtDlpExtractorEngine(
    context: Context
) : ExtractorEngine {

    private val appContext = context.applicationContext

    private val initialized: Boolean

    init {
        initialized = try {
            YtDlp.init(appContext)
            true
        } catch (error: YtDlpException) {
            false
        }
    }

    override suspend fun probe(url: String): ExtractorProbeResult =
        if (initialized) {
            ExtractorProbeResult(
                initialized = true,
                message = "yt-dlp-android inicializado; integração Kotlin → ExtractorEngine disponível."
            )
        } else {
            ExtractorProbeResult(
                initialized = false,
                message = "Falha ao inicializar yt-dlp-android."
            )
        }
}
