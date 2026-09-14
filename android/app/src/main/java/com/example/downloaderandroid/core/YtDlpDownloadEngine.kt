package com.example.downloaderandroid.core

import android.content.Context
import android.content.Intent
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.auth.YouTubeCookieStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real audio download engine used by the Phase 3 test harness.
 *
 * The execution layer now follows Seal's backend model: YoutubeDLRequest is
 * executed through youtubedl-android, whose runtime supplies Python/yt-dlp,
 * QuickJS, and the ffmpeg/aria2c integration. We keep our own small engine so
 * the rest of MusicasAndroid does not depend on Seal's UI/database classes.
 *
 * Cookies remain in app-private storage and are passed directly to yt-dlp;
 * they never enter the Compose/JavaScript layer and are never logged.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cookieStore = YouTubeCookieStore(appContext)

    private data class DownloadAttempt(
        val label: String,
        val extractorArgs: String? = null,
        val requiresCookies: Boolean = false,
    )

    suspend fun downloadBestAudio(url: String): DownloadExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                // Local/native initialization is safe here, while the network
                // update is explicitly kept on Dispatchers.IO.
                SealCompatibleDownloaderBackend.init(appContext)
                SealCompatibleDownloaderBackend.ensureYtDlpUpdated(appContext)

                val outputDirectory = File(
                    requireNotNull(appContext.getExternalFilesDir(null)) {
                        "Armazenamento externo do aplicativo indisponível."
                    },
                    "phase3-downloads",
                ).apply {
                    if (!exists() && !mkdirs()) {
                        error("Não foi possível criar a pasta de downloads.")
                    }
                }

                val hasCookies = cookieStore.hasCookies()
                val attempts = buildList {
                    if (hasCookies) {
                        add(
                            DownloadAttempt(
                                label = "YouTube autenticado",
                                requiresCookies = true,
                            )
                        )
                    }

                    // Keep the public fallback routes from the previous test,
                    // but execute them through the Seal-compatible backend.
                    add(
                        DownloadAttempt(
                            label = "YouTube padrão",
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "Android VR",
                            extractorArgs = "youtube:player_client=android_vr",
                        )
                    )
                    add(
                        DownloadAttempt(
                            label = "Web Safari HLS",
                            extractorArgs = "youtube:player_client=web_safari",
                        )
                    )
                }

                val errors = mutableListOf<String>()

                for (attempt in attempts) {
                    val outputTemplate = File(
                        outputDirectory,
                        "%(title)s.%(ext)s",
                    ).absolutePath

                    val request = YoutubeDLRequest(url)
                        .addOption("-o", outputTemplate)
                        .addOption("-f", "bestaudio/best")
                        .addOption("-x")
                        .addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", "0")
                        .addOption("--no-playlist")
                        .addOption("--no-mtime")
                        .addOption("--newline")
                        .addOption("--no-part")
                        .addOption("--force-overwrites")
                        .addOption("--retries", "3")
                        .addOption("--fragment-retries", "3")
                        .addOption("--concurrent-fragments", "4")
                        .addOption("--embed-metadata")

                    if (attempt.requiresCookies) {
                        request.addOption("--cookies", cookieStore.cookieFile.absolutePath)
                    }

                    attempt.extractorArgs?.let { extractorArgs ->
                        request.addOption("--extractor-args", extractorArgs)
                    }

                    try {
                        val response = YoutubeDL.getInstance().execute(
                            request = request,
                            processId = "phase3-${System.currentTimeMillis()}",
                        )

                        if (response.exitCode == 0) {
                            return@withContext DownloadExecutionResult(
                                success = true,
                                exitCode = response.exitCode,
                                outputDirectory = outputDirectory.absolutePath,
                                message = "Download concluído usando ${attempt.label}.",
                            )
                        }

                        errors += "${attempt.label}: código ${response.exitCode}"
                        response.err
                            .lineSequence()
                            .filter { line -> line.isNotBlank() }
                            .toList()
                            .takeLast(3)
                            .forEach { line -> errors += "  $line" }
                    } catch (error: YoutubeDLException) {
                        errors += "${attempt.label}: ${error.message ?: "falha sem mensagem"}"
                    } catch (error: YoutubeDL.CanceledException) {
                        errors += "${attempt.label}: download cancelado"
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        errors += "${attempt.label}: execução interrompida"
                    } catch (error: Throwable) {
                        errors += "${attempt.label}: ${error.javaClass.simpleName}: " +
                            (error.message ?: "sem mensagem")
                    }
                }

                val had403 = errors.any { it.contains("403") }
                if (!hasCookies && had403) {
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, YouTubeAuthActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
                        if (!hasCookies && had403) {
                            append("\n\nO YouTube bloqueou os streams públicos com HTTP 403.")
                            append("\nFaça login em ‘Entrar no YouTube’, salve os cookies e tente novamente.")
                        }
                    },
                )
            } catch (error: Throwable) {
                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = null,
                    message = "${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}",
                )
            }
        }
}

data class DownloadExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val outputDirectory: String?,
    val message: String,
)
