package com.example.downloaderandroid.core

import android.content.Context
import android.content.Intent
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.auth.YouTubeCookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real audio download engine used by the Phase 3 test harness.
 *
 * YouTube currently enforces PO-token requirements for several clients. The
 * engine therefore tries an authenticated/default request when a private
 * cookie jar exists, then the known free no-PO fallbacks.
 *
 * Cookies remain in app-private storage and are passed directly to yt-dlp;
 * they never enter the Compose/JavaScript layer and are never logged.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cookieStore = YouTubeCookieStore(appContext)

    private data class DownloadAttempt(
        val label: String,
        val format: String,
        val extractorArgs: String?,
        val requiresCookies: Boolean = false
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

                val hasCookies = cookieStore.hasCookies()

                val attempts = buildList {
                    if (hasCookies) {
                        // With account cookies, let the current yt-dlp default
                        // client selection choose the authenticated YouTube
                        // path. This is also the path needed for private data.
                        add(
                            DownloadAttempt(
                                label = "YouTube autenticado",
                                format = "bestaudio/best",
                                extractorArgs = null,
                                requiresCookies = true
                            )
                        )
                        add(
                            DownloadAttempt(
                                label = "YouTube autenticado (web creator)",
                                format = "bestaudio/best",
                                extractorArgs = "youtube:player_client=web_creator",
                                requiresCookies = true
                            )
                        )
                    }

                    // Free public fallbacks. web_safari may expose HLS formats
                    // that avoid the current GVS PO-token requirement.
                    add(
                        DownloadAttempt(
                            label = "Web Safari (HLS sem PO token)",
                            format = "bestaudio[protocol*=m3u8]/best[protocol*=m3u8]",
                            extractorArgs = "youtube:player_client=web_safari"
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "Android VR (formato 18 com áudio AAC)",
                            format = "18",
                            extractorArgs = "youtube:player_client=android_vr"
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "TV",
                            format = "bestaudio/best",
                            extractorArgs = "youtube:player_client=tv"
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "Web incorporado",
                            format = "bestaudio/best",
                            extractorArgs = "youtube:player_client=web_embedded"
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "padrão",
                            format = "bestaudio/best",
                            extractorArgs = null
                        )
                    )
                }

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

                    if (attempt.requiresCookies) {
                        request.addOption("--cookies", cookieStore.cookieFile.absolutePath)
                    }

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

                if (!hasCookies && errors.any { it.contains("403") }) {
                    // Open the native login screen only after the free public
                    // routes have failed. The next tap on the download button
                    // will reuse the private cookie jar.
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, YouTubeAuthActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
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
                        if (!hasCookies && errors.any { it.contains("403") }) {
                            append("\n\nO YouTube bloqueou os streams públicos com HTTP 403.")
                            append("\nA tela de autenticação foi aberta. Faça login e salve a autenticação; depois toque novamente em ‘Testar download’.")
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
