package com.example.downloaderandroid.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.compat.YoutubeDL
import dev.ffmpegkit_maintained.ytdlp.compat.YoutubeDLException

class YtDlpExtractorEngine(
    context: Context
) : ExtractorEngine {

    private val initialized: Boolean

    init {
        initialized = try {
            YoutubeDL.getInstance().init(context.applicationContext)
            true
        } catch (error: YoutubeDLException) {
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
