package com.example.downloaderandroid.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.auth.YouTubeCookieStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real audio download engine used by the Phase 3/4 test harness.
 *
 * yt-dlp downloads into the app-private temporary directory first. Successful
 * MP3 files are then published through MediaStore into the public Music folder,
 * where normal music players can discover them.
 *
 * Phase 4.2 embeds the source thumbnail as MP3 cover art and keeps the yt-dlp
 * metadata post-processing enabled. Phase 4.3 exposes yt-dlp progress and ETA
 * to the foreground service so the notification can show real progress.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cookieStore = YouTubeCookieStore(appContext)

    private data class DownloadAttempt(
        val label: String,
        val extractorArgs: String? = null,
        val requiresCookies: Boolean = false,
    )

    suspend fun downloadBestAudio(
        url: String,
        onProgress: (progressPercent: Float, etaSeconds: Long, line: String) -> Unit = { _, _, _ -> },
    ): DownloadExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                SealCompatibleDownloaderBackend.init(appContext)
                SealCompatibleDownloaderBackend.ensureYtDlpUpdated(appContext)

                val legacyTemporaryDirectory = File(
                    requireNotNull(appContext.getExternalFilesDir(null)) {
                        "Armazenamento externo do aplicativo indisponível."
                    },
                    "phase3-downloads",
                ).apply {
                    if (!exists() && !mkdirs()) {
                        error("Não foi possível criar a pasta temporária de downloads.")
                    }
                }

                val previousFiles = publishMp3Files(legacyTemporaryDirectory)

                val temporaryDirectory = File(
                    requireNotNull(appContext.getExternalFilesDir(null)) {
                        "Armazenamento externo do aplicativo indisponível."
                    },
                    "phase4-downloads/${System.currentTimeMillis()}",
                ).apply {
                    if (!mkdirs()) {
                        error("Não foi possível criar a pasta temporária do download.")
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

                    add(DownloadAttempt(label = "YouTube padrão"))
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
                        temporaryDirectory,
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
                        .addOption("--embed-thumbnail")
                        .addOption("--convert-thumbnails", "jpg")

                    if (attempt.requiresCookies) {
                        request.addOption("--cookies", cookieStore.cookieFile.absolutePath)
                    }

                    attempt.extractorArgs?.let { extractorArgs ->
                        request.addOption("--extractor-args", extractorArgs)
                    }

                    try {
                        val response = YoutubeDL.getInstance().execute(
                            request = request,
                            processId = "phase4-${System.currentTimeMillis()}",
                        ) { progress, etaSeconds, line ->
                            onProgress(progress, etaSeconds, line)
                        }

                        if (response.exitCode == 0) {
                            val published = publishMp3Files(temporaryDirectory)
                            val totalPublished = previousFiles + published
                            return@withContext DownloadExecutionResult(
                                success = true,
                                exitCode = response.exitCode,
                                outputDirectory = MUSIC_DIRECTORY_DESCRIPTION,
                                message = if (totalPublished > 0) {
                                    "Download concluído usando ${attempt.label}. " +
                                        "Metadados e capa foram processados quando disponíveis. " +
                                        "Música salva em $MUSIC_DIRECTORY_DESCRIPTION."
                                } else {
                                    "Download concluído usando ${attempt.label}. " +
                                        "Metadados e capa foram processados quando disponíveis."
                                },
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
                    outputDirectory = temporaryDirectory.absolutePath,
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

    /**
     * Copies MP3s from the temporary app directory into the public Android
     * Music collection. MediaStore makes the files visible to music players
     * without requiring broad storage permissions on Android 10+.
     */
    private fun publishMp3Files(directory: File): Int {
        if (!directory.exists()) return 0

        var publishedCount = 0
        directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            ?.forEach { source ->
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC + "/MusicasAndroid",
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val resolver = appContext.contentResolver
                val uri = resolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return@forEach

                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Não foi possível abrir o arquivo de música de destino.")

                    resolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )

                    if (!source.delete()) {
                        // The public copy is already complete, so leaving the
                        // temporary file is safe and does not invalidate it.
                    }
                    publishedCount++
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                }
            }

        return publishedCount
    }

    companion object {
        private const val MUSIC_DIRECTORY_DESCRIPTION =
            "Armazenamento interno compartilhado/Music/MusicasAndroid"
    }
}

data class DownloadExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val outputDirectory: String?,
    val message: String,
)
