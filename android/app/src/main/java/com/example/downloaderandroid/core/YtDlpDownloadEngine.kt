package com.example.downloaderandroid.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Primeira integração real de download.
 *
 * Esta etapa baixa o melhor áudio disponível para o armazenamento privado do
 * aplicativo. A conversão final para MP3 e a organização definitiva serão
 * conectadas nas próximas fases ao MediaProcessor/FFmpeg.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext

    suspend fun downloadBestAudio(url: String): DownloadExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                YtDlp.init(appContext)

                val outputDirectory = File(
                    requireNotNull(appContext.getExternalFilesDir(null)) {
                        "Armazenamento externo do aplicativo indisponível."
                    },
                    "phase3-downloads"
                ).apply {
                    if (!exists() && !mkdirs()) {
                        error("Não foi possível criar a pasta de downloads.")
                    }
                }

                val outputTemplate = File(
                    outputDirectory,
                    "%(title)s.%(ext)s"
                ).absolutePath

                val request = YtDlpRequest(url)
                    .setOutputTemplate(outputTemplate)
                    .addOption("-f", "bestaudio/best")
                    .addOption("--no-playlist")

                val response = YtDlp.execute(request, null)

                DownloadExecutionResult(
                    success = response.isSuccess,
                    exitCode = response.exitCode,
                    outputDirectory = outputDirectory.absolutePath,
                    message = if (response.isSuccess) {
                        "Áudio baixado com sucesso."
                    } else {
                        "yt-dlp terminou com código ${response.exitCode}."
                    }
                )
            } catch (error: YtDlpException) {
                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = null,
                    message = error.message ?: "Falha ao executar yt-dlp."
                )
            } catch (error: Throwable) {
                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = null,
                    message = "${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}"
                )
            }
        }
}

data class DownloadExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val outputDirectory: String?,
    val message: String
)
