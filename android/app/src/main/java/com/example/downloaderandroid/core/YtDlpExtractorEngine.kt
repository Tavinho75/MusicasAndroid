package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLException

class YtDlpExtractorEngine(
    context: Context
) : ExtractorEngine {

    private val initialized: Boolean
    private val initializationError: String?

    init {
        var errorMessage: String? = null
        initialized = try {
            SealCompatibleDownloaderBackend.init(context)
            true
        } catch (error: YoutubeDLException) {
            errorMessage = formatError(error)
            false
        } catch (error: Throwable) {
            errorMessage = formatError(error)
            false
        }
        initializationError = errorMessage
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
                message = "Falha ao inicializar o backend youtubedl-android: ${initializationError ?: "erro desconhecido"}"
            )
        }

    private fun formatError(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) {
            error::class.java.simpleName
        } else {
            "${error::class.java.simpleName}: $message"
        }
    }
}
