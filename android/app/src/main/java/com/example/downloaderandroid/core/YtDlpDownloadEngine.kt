package com.example.downloaderandroid.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.example.downloaderandroid.auth.YouTubeAuthActivity
import com.example.downloaderandroid.auth.YouTubeCookieStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Motor real de download de áudio.
 *
 * O yt-dlp baixa primeiro para o diretório temporário privado do aplicativo. Ao
 * final, os MP3 são publicados via MediaStore na pasta pública de músicas, onde
 * os players do sistema conseguem encontrá-los.
 *
 * Fluxo atual:
 * - o título da faixa é descoberto durante o próprio download, evitando uma
 *   extração extra só para mostrar o nome na notificação;
 * - a estratégia de extração que funcionou por último é tentada primeiro;
 * - a atualização do yt-dlp fica fora do caminho crítico (uma vez por dia);
 * - a pasta temporária da tarefa é removida ao final, com ou sem sucesso.
 */
class YtDlpDownloadEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cookieStore = YouTubeCookieStore(appContext)
    private val attemptMemory = DownloadAttemptMemory(appContext)

    private data class DownloadAttempt(
        val key: String,
        val label: String,
        val extractorArgs: String? = null,
        val requiresCookies: Boolean = false,
    )

    suspend fun resolveTitle(url: String): String? =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            runCatching {
                SealCompatibleDownloaderBackend.init(appContext)

                val request = YoutubeDLRequest(url)
                    .addOption("--no-playlist")
                    .addOption("--skip-download")
                    .addOption("--no-warnings")
                if (cookieStore.hasCookies()) {
                    request.addOption("--cookies", cookieStore.cookieFile.absolutePath)
                }
                YoutubeDL.getInstance().getInfo(request).title?.trim()?.takeIf { it.isNotBlank() }
            }.also { result ->
                Log.i(
                    TIMING_TAG,
                    "resolveTitulo=${SystemClock.elapsedRealtime() - startedAt}ms ok=${result.getOrNull() != null}",
                )
            }.getOrNull()
        }

    /** Atualiza o yt-dlp fora do fluxo de download (uma vez por dia). */
    suspend fun ensureUpToDate(force: Boolean = false): Boolean =
        SealCompatibleDownloaderBackend.updateYtDlpIfDue(appContext, force)

    suspend fun downloadBestAudio(
        url: String,
        taskId: String,
        onProgress: (progressPercent: Float, etaSeconds: Long, line: String) -> Unit = { _, _, _ -> },
        onTitle: (String) -> Unit = {},
    ): DownloadExecutionResult =
        withContext(Dispatchers.IO) {
            val temporaryDirectory = createTemporaryDirectory() ?: return@withContext DownloadExecutionResult(
                success = false,
                exitCode = -1,
                outputDirectory = null,
                message = "Não foi possível criar a pasta temporária de downloads.",
            )

            val jobStartedAt = SystemClock.elapsedRealtime()
            try {
                // Publica arquivos deixados por uma versão anterior do motor antes
                // de limpar a pasta. Sem isso, o áudio já baixado seria perdido.
                val legacyStartedAt = SystemClock.elapsedRealtime()
                publishMp3Files(legacyTemporaryDirectory())
                Log.i(
                    TIMING_TAG,
                    "publicacaoLegada=${SystemClock.elapsedRealtime() - legacyStartedAt}ms",
                )

                val hasCookies = cookieStore.hasCookies()
                // Desligado por padrão: reordenar a escada sem medição pode fixar
                // um cliente mais lento como primeira escolha. Ligue com
                // PREFER_REMEMBERED_ATTEMPT depois de comparar os tempos.
                val preferredKey = if (PREFER_REMEMBERED_ATTEMPT) attemptMemory.preferredKey(url) else null
                val attempts = orderedAttempts(hasCookies, preferredKey)

                val errors = mutableListOf<String>()
                var earnedTitle: String? = null

                for ((attemptIndex, attempt) in attempts.withIndex()) {
                    val outputTemplate = File(temporaryDirectory, "%(title)s.%(ext)s").absolutePath

                    val request = YoutubeDLRequest(url)
                        .addOption("-o", outputTemplate)
                        .addOption("-f", "bestaudio/best")
                        .addOption("-x")
                        .addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", AUDIO_QUALITY)
                        .addOption("--no-playlist")
                        .addOption("--no-mtime")
                        .addOption("--newline")
                        // Sem --no-part o yt-dlp retoma de onde parou; --force-overwrites
                        // é desnecessário porque cada download usa uma pasta nova.
                        .addOption("--continue")
                        .addOption("--no-overwrites")
                        .addOption("--cache-dir", cacheDirectory().absolutePath)
                        .addOption("--retries", "3")
                        .addOption("--fragment-retries", "3")
                        .addOption("--concurrent-fragments", CONCURRENT_FRAGMENTS.toString())
                        .addOption("--embed-metadata")
                        .addOption("--embed-thumbnail")
                        .addOption("--convert-thumbnails", "jpg")

                    if (attempt.requiresCookies) {
                        request.addOption("--cookies", cookieStore.cookieFile.absolutePath)
                    }

                    attempt.extractorArgs?.let { extractorArgs ->
                        request.addOption("--extractor-args", extractorArgs)
                    }

                    val processId = DownloadProcessRegistry.processId(taskId, attemptIndex)
                    DownloadProcessRegistry.register(processId)

                    val attemptStartedAt = SystemClock.elapsedRealtime()
                    var firstOutputAt = 0L
                    // Cada pós-processador do yt-dlp é anunciado por uma linha
                    // "[Nome] ...". Medindo o intervalo entre anúncios consecutivos
                    // dá para separar download de rede de conversão/capa.
                    var stageName = "extracao+download"
                    var stageStartedAt = attemptStartedAt
                    val stageDurations = mutableListOf<String>()

                    try {
                        val response = YoutubeDL.getInstance().execute(
                            request = request,
                            processId = processId,
                        ) { progress, etaSeconds, line ->
                            if (firstOutputAt == 0L) firstOutputAt = SystemClock.elapsedRealtime()

                            val postProcessorMarker = POST_PROCESSOR_PATTERN.find(line)?.groupValues?.get(1)
                            if (postProcessorMarker != null && postProcessorMarker != stageName) {
                                stageDurations += "$stageName=${SystemClock.elapsedRealtime() - stageStartedAt}ms"
                                stageName = postProcessorMarker
                                stageStartedAt = SystemClock.elapsedRealtime()
                            }

                            onProgress(progress, etaSeconds, line)
                            YtDlpOutputParser.titleFromLine(line)?.let { title ->
                                if (earnedTitle == null) {
                                    earnedTitle = title
                                    onTitle(title)
                                }
                            }
                        }

                        val finishedAt = SystemClock.elapsedRealtime()
                        stageDurations += "$stageName=${finishedAt - stageStartedAt}ms"
                        Log.i(
                            TIMING_TAG,
                            "tentativa=${attempt.key} total=${finishedAt - attemptStartedAt}ms " +
                                "primeiraSaida=${firstOutputAt.takeIf { it > 0 }?.minus(attemptStartedAt) ?: -1}ms " +
                                "etapas=[${stageDurations.joinToString()}] exit=${response.exitCode}",
                        )

                        if (response.exitCode == 0) {
                            if (PREFER_REMEMBERED_ATTEMPT) attemptMemory.remember(url, attempt.key)

                            val sourceFiles = temporaryDirectory.listFiles()
                                ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
                                .orEmpty()
                            val sourceFile = sourceFiles.firstOrNull()
                            val sourceTitle = sourceFile?.nameWithoutExtension ?: earnedTitle
                            val sourceSize = sourceFile?.length() ?: 0L

                            val publishStartedAt = SystemClock.elapsedRealtime()
                            val (published, alreadyPresent) = publishMp3Files(temporaryDirectory)
                            Log.i(
                                TIMING_TAG,
                                "publicacaoMediaStore=${SystemClock.elapsedRealtime() - publishStartedAt}ms " +
                                    "publicados=$published jaExistiam=$alreadyPresent",
                            )
                            Log.i(
                                TIMING_TAG,
                                "TOTAL_DOWNLOAD=${SystemClock.elapsedRealtime() - jobStartedAt}ms",
                            )

                            val outcomeMessage = buildString {
                                append("Download concluído usando ")
                                append(attempt.label)
                                append('.')
                                if (published == 0 && alreadyPresent > 0) {
                                    append("\nA música já estava na sua biblioteca; nada foi duplicado.")
                                }
                            }

                            return@withContext DownloadExecutionResult(
                                success = true,
                                exitCode = response.exitCode,
                                outputDirectory = MUSIC_DIRECTORY_DESCRIPTION,
                                message = outcomeMessage,
                                title = sourceTitle,
                                fileSizeBytes = sourceSize,
                                folder = "Music/MusicasAndroid",
                            )
                        }

                        // A estratégia lembrada deixou de funcionar: descarta e
                        // deixa as demais tentativas decidirem o resultado.
                        if (PREFER_REMEMBERED_ATTEMPT && attempt.key == preferredKey) attemptMemory.forget(url)

                        errors += "${attempt.label}: código ${response.exitCode}"
                        response.err
                            .lineSequence()
                            .filter { line -> line.isNotBlank() }
                            .toList()
                            .takeLast(3)
                            .forEach { line -> errors += "  $line" }
                    } catch (error: YoutubeDLException) {
                        if (PREFER_REMEMBERED_ATTEMPT && attempt.key == preferredKey) attemptMemory.forget(url)
                        errors += "${attempt.label}: ${error.message ?: "falha sem mensagem"}"
                    } catch (error: YoutubeDL.CanceledException) {
                        throw kotlinx.coroutines.CancellationException("Download interrompido pelo usuário.", error)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw kotlinx.coroutines.CancellationException("Download interrompido pelo usuário.", error)
                    } catch (error: Throwable) {
                        errors += "${attempt.label}: ${error.javaClass.simpleName}: " +
                            (error.message ?: "sem mensagem")
                    } finally {
                        DownloadProcessRegistry.release(processId)
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

                val errorSummary = errors.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("YouTube ") }
                    .firstOrNull { it.contains("ERROR:", ignoreCase = true) }
                    ?.substringAfter("ERROR:", "")
                    ?.trim()
                    ?: errors.lastOrNull()?.trim()
                    ?: "Erro não identificado pelo yt-dlp."

                val failureMessage = buildString {
                    append("Falha no download: ")
                    append(errorSummary)

                    if (errors.isNotEmpty()) {
                        append("\n\nTentativas:\n")
                        append(errors.joinToString("\n"))
                    }

                    if (!hasCookies && had403) {
                        append("\n\nO YouTube bloqueou os streams públicos com HTTP 403.")
                        append("\nFaça login em ‘Entrar no YouTube’, salve os cookies e tente novamente.")
                    }
                }

                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = temporaryDirectory.absolutePath,
                    message = failureMessage,
                )
            } catch (error: Throwable) {
                DownloadExecutionResult(
                    success = false,
                    exitCode = -1,
                    outputDirectory = temporaryDirectory.absolutePath,
                    message = "${error.javaClass.simpleName}: ${error.message ?: "sem mensagem"}",
                )
            } finally {
                // Libera o espaço da tarefa: sobram apenas áudios originais,
                // miniaturas e arquivos parciais quando o download não conclui.
                DownloadStorage.deleteQuietly(temporaryDirectory)
            }
        }

    /**
     * Monta a escada de tentativas colocando em primeiro lugar a estratégia que
     * já funcionou para este site. A ordem padrão é preservada como fallback.
     */
    private fun orderedAttempts(hasCookies: Boolean, preferredKey: String?): List<DownloadAttempt> {
        val attempts = buildList {
            if (hasCookies) {
                add(
                    DownloadAttempt(
                        key = "cookies",
                        label = "YouTube autenticado",
                        requiresCookies = true,
                    )
                )
            }

            add(DownloadAttempt(key = "default", label = "YouTube padrão"))
            add(
                DownloadAttempt(
                    key = "android_vr",
                    label = "Android VR",
                    extractorArgs = "youtube:player_client=android_vr",
                )
            )
            add(
                DownloadAttempt(
                    key = "web_safari",
                    label = "Web Safari HLS",
                    extractorArgs = "youtube:player_client=web_safari",
                )
            )
        }

        if (preferredKey == null) return attempts
        return attempts.sortedByDescending { attempt -> if (attempt.key == preferredKey) 1 else 0 }
    }

    private fun createTemporaryDirectory(): File? {
        val base = appContext.getExternalFilesDir(null) ?: return null
        val directory = File(base, "phase4-downloads/${System.currentTimeMillis()}").apply {
            if (!mkdirs() && !isDirectory) return null
        }
        return directory
    }

    private fun legacyTemporaryDirectory(): File =
        File(appContext.getExternalFilesDir(null), "phase3-downloads")

    private fun cacheDirectory(): File =
        File(appContext.filesDir, "yt-dlp-cache").apply { mkdirs() }

    /**
     * Copia os MP3 do diretório temporário para a coleção pública de músicas.
     *
     * Arquivos com o mesmo nome já presentes na pasta de destino são ignorados,
     * evitando duplicatas como "Título (1).mp3" na biblioteca do usuário.
     *
     * @return quantidade publicada e quantidade que já existia no destino.
     */
    private fun publishMp3Files(directory: File): Pair<Int, Int> {
        if (!directory.exists()) return 0 to 0

        var publishedCount = 0
        var alreadyPresentCount = 0

        directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            ?.forEach { source ->
                if (musicWithNameExists(source.name)) {
                    alreadyPresentCount++
                    source.delete()
                    return@forEach
                }

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
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@forEach

                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Não foi possível abrir o arquivo de música de destino.")

                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )

                    // A cópia pública já está completa; se a remoção do temporário
                    // falhar, ela é limpa na próxima varredura de pastas antigas.
                    source.delete()
                    publishedCount++
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                }
            }

        return publishedCount to alreadyPresentCount
    }

    /** Verifica se já existe uma música com este nome na pasta de destino. */
    private fun musicWithNameExists(fileName: String): Boolean {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "${Environment.DIRECTORY_MUSIC}/MusicasAndroid%")

        return runCatching {
            appContext.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                selection,
                selectionArgs,
                null,
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    companion object {
        private const val MUSIC_DIRECTORY_DESCRIPTION =
            "Armazenamento interno compartilhado/Music/MusicasAndroid"

        /**
         * Qualidade do áudio final no formato MP3.
         *
         * "0" mantém o comportamento atual (VBR de alta qualidade). O desktop usa
         * CBR 320 (config.json); alinhar os dois é uma decisão de produto pendente.
         */
        private const val AUDIO_QUALITY = "0"

        // ------------------------------------------------------------------
        // Ajustes de desempenho
        //
        // Alterne UM por vez e compare com os números do Logcat (tag
        // "DownloadTiming"). Estes dois foram mudados sem medição na primeira
        // tentativa e o tempo piorou (0:58 -> 1:14), por isso voltaram ao
        // comportamento original.
        // ------------------------------------------------------------------

        /** Fragmentos baixados em paralelo quando a origem é segmentada (DASH/HLS). */
        private const val CONCURRENT_FRAGMENTS = 4

        /**
         * Prioriza a estratégia de extração que funcionou por último neste site.
         *
         * Só ligue depois de comparar os tempos: se o cliente "vencedor" for mais
         * lento que os caminhos primários, isto aumenta o tempo de download.
         */
        private const val PREFER_REMEMBERED_ATTEMPT = false

        /** Tag do Logcat com os tempos de cada etapa de um download. */
        const val TIMING_TAG = "DownloadTiming"

        /** Linhas como "[ExtractAudio] Destination: ..." marcam o início de um pós-processador. */
        private val POST_PROCESSOR_PATTERN = Regex("""^\[([A-Za-z]+)]\s""")
    }
}

data class DownloadExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val outputDirectory: String?,
    val message: String,
    val title: String? = null,
    val fileSizeBytes: Long = 0L,
    val folder: String = "Music/MusicasAndroid",
)
