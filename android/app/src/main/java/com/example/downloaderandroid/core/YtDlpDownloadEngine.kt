package com.example.downloaderandroid.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Integração de download com tentativas de compatibilidade para fontes que
 * exigem um cliente específico do extractor, como o YouTube.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext

    private data class DownloadAttempt(
        val label: String,
        val extractorArgs: String?
    )

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

                val attempts = listOf(
                    DownloadAttempt(
                        label = "padrão",
                        extractorArgs = null
                    ),
                    DownloadAttempt(
                        label = "cliente Android",
                        extractorArgs = "youtube:player_client=android"
                    ),
                    DownloadAttempt(
                        label = "cliente web",
                        extractorArgs = "youtube:player_client=web"
                    )
                )

                val errors = mutableListOf<String>()

                for ((index, attempt) in attempts.withIndex()) {
                    val outputTemplate = File(
                        outputDirectory,
                        "%(title)s.%(ext)s"
                    ).absolutePath

                    val request = YtDlpRequest(url)
                        .setOutputTemplate(outputTemplate)
                        .addOption("-f", "bestaudio/best")
                        .addOption("--no-playlist")
                        .addOption("--no-part")
                        .addOption("--force-overwrites")

                    attempt.extractorArgs?.let { extractorArgs ->
                        request.addOption("--extractor-args", extractorArgs)
                    }

                    try {
                        val response = YtDlp.execute(request, null)

                        if (response.isSuccess) {
                            return@withContext DownloadExecutionResult(
                                success = true,
                                exitCode = response.exitCode,
                                outputDirectory = outputDirectory.absolutePath,
                                message = if (index == 0) {
                                    "Áudio baixado com sucesso."
                                } else {
                                    "Áudio baixado com sucesso usando ${attempt.label}."
                                }
                            )
                        }

                        errors += "${attempt.label}: código ${response.exitCode}"
                    } catch (error: YtDlpException) {
                        errors += "${attempt.label}: ${error.message ?: "falha sem mensagem"}"
                    } catch (error: Throwable) {
                        errors += "${attempt.label}: ${error.javaClass.simpleName}: " +
                            (error.message ?: "sem mensagem")
                    }
                }

                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = outputDirectory.absolutePath,
                    message = buildString {
                        append("Nenhuma tentativa conseguiu baixar o áudio.")
                        if (errors.isNotEmpty()) {
                            append("\n\nTentativas:\n")
                            append(errors.joinToString("\n"))
                        }
                    }
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
