package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLException

class YtDlpExtractorEngine(
    context: Context
) : ExtractorEngine {

    private val initialized: Boolean

    init {
        initialized = try {
            SealCompatibleDownloaderBackend.init(context)
            true
        } catch (error: YoutubeDLException) {
            false
        } catch (error: Throwable) {
            false
        }
    }

    override suspend fun probe(url: String): ExtractorProbeResult =
        if (initialized) {
            ExtractorProbeResult(
                initialized = true,
                message = "youtubedl-android inicializado; integração Kotlin → ExtractorEngine disponível."
            )
        } else {
            ExtractorProbeResult(
                initialized = false,
                message = "Falha ao inicializar o backend youtubedl-android."
            )
        }
}
