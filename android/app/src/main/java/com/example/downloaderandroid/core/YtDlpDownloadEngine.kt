package com.example.downloaderandroid.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Download engine with YouTube client fallbacks.
 *
 * YouTube's current PO-token enforcement makes ordinary GVS downloads fail
 * with HTTP 403 for several clients. We therefore try clients which currently
 * expose a stream path that does not require a GVS PO token, before falling
 * back to the more conventional clients.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext

    private data class DownloadAttempt(
        val label: String,
        val format: String,
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

                // Order matters. web_safari can expose HLS formats, whose GVS
                // requests currently do not need a PO token. Android VR format
                // 18 is kept as the next no-PO fallback. TV is another client
                // which currently does not require a GVS PO token when used
                // without account cookies.
                val attempts = listOf(
                    DownloadAttempt(
                        label = "Web Safari (HLS sem PO token)",
                        format = "bestaudio[protocol*=m3u8]/best[protocol*=m3u8]",
                        extractorArgs = "youtube:player_client=web_safari"
                    ),
                    DownloadAttempt(
                        label = "Android VR (formato 18 com áudio AAC)",
                        format = "18",
                        extractorArgs = "youtube:player_client=android_vr"
                    ),
                    DownloadAttempt(
                        label = "TV",
                        format = "bestaudio/best",
                        extractorArgs = "youtube:player_client=tv"
                    ),
                    DownloadAttempt(
                        label = "Web incorporado",
                        format = "bestaudio/best",
                        extractorArgs = "youtube:player_client=web_embedded"
                    ),
                    DownloadAttempt(
                        label = "padrão",
                        format = "bestaudio/best",
                        extractorArgs = null
                    ),
                    DownloadAttempt(
                        label = "cliente Android",
                        format = "bestaudio/best",
                        extractorArgs = "youtube:player_client=android"
                    ),
                    DownloadAttempt(
                        label = "cliente web",
                        format = "bestaudio/best",
                        extractorArgs = "youtube:player_client=web"
                    )
                )

                val errors = mutableListOf<String>()

                for (attempt in attempts) {
                    val outputTemplate = File(
                        outputDirectory,
                        "%(title)s.%(ext)s"
                    ).absolutePath

                    val request = YtDlpRequest(url)
                        .setOutputTemplate(outputTemplate)
                        .addOption("-f", attempt.format)
                        .addOption("--no-playlist")
                        .addOption("--no-part")
                        .addOption("--force-overwrites")
                        .addOption("--retries", "3")
                        .addOption("--fragment-retries", "3")

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
                                message = "Download concluído usando ${attempt.label}."
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
