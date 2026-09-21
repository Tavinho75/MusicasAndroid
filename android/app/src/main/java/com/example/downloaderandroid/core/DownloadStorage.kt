package com.example.downloaderandroid.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Registro dos processos do yt-dlp em execução neste processo do aplicativo.
 *
 * O cancelamento precisa matar exatamente os processos que existem. Antes, o
 * serviço tentava adivinhar os identificadores (índices 0 a 3) por concatenação
 * de strings, o que quebra silenciosamente se o número de tentativas mudar.
 */
object DownloadProcessRegistry {

    private const val PREFIX = "ytdlp"

    private val activeIds = linkedSetOf<String>()

    /** Identificador estável do processo de uma tentativa de download. */
    fun processId(taskId: String, attemptIndex: Int): String = "$PREFIX-$taskId-$attemptIndex"

    @Synchronized
    fun register(processId: String) {
        activeIds += processId
    }

    @Synchronized
    fun release(processId: String) {
        activeIds -= processId
    }

    /** Identificadores ativos pertencentes a uma tarefa. */
    @Synchronized
    fun activeIdsFor(taskId: String): List<String> =
        activeIds.filter { it.startsWith("$PREFIX-$taskId-") }

    /** Todos os identificadores ativos, independentemente da tarefa. */
    @Synchronized
    fun snapshot(): List<String> = activeIds.toList()

    /** Indica se existe algum download do yt-dlp em execução agora. */
    @Synchronized
    fun hasActiveProcesses(): Boolean = activeIds.isNotEmpty()
}

/**
 * Limpeza dos diretórios temporários usados pelo motor de download.
 *
 * Cada download cria uma pasta própria em ``files/phase4-downloads/<timestamp>``.
 * Sem limpeza, áudios originais (.webm/.m4a), miniaturas e arquivos parciais se
 * acumulam indefinidamente no armazenamento do aplicativo.
 */
object DownloadStorage {

    private const val TAG = "DownloadStorage"

    /** Pasta temporária considerada órfã depois deste tempo. */
    private const val STALE_AFTER_MILLIS = 6L * 60L * 60L * 1000L

    /** Raízes temporárias do motor de download, das versões atual e anterior. */
    fun temporaryRoots(context: Context): List<File> {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return emptyList()
        return listOf(
            File(base, "phase4-downloads"),
            File(base, "phase3-downloads"),
        )
    }

    /**
     * Remove pastas temporárias de tarefas antigas (interrompidas, canceladas
     * ou deixadas por versões anteriores do aplicativo).
     */
    fun cleanupStale(context: Context, maxAgeMillis: Long = STALE_AFTER_MILLIS) {
        val limit = System.currentTimeMillis() - maxAgeMillis
        temporaryRoots(context).forEach { root ->
            root.listFiles()?.forEach { candidate ->
                val lastModified = candidate.lastModified()
                if (lastModified in 1L until limit) deleteQuietly(candidate)
            }
        }
    }

    /** Apaga um arquivo ou diretório sem propagar falhas. */
    fun deleteQuietly(target: File) {
        runCatching {
            if (target.exists()) target.deleteRecursively()
        }.onFailure { error ->
            Log.w(TAG, "Não foi possível remover '${target.absolutePath}': ${error.message}")
        }
    }
}
