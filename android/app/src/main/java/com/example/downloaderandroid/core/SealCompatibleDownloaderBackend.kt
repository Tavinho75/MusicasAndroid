package com.example.downloaderandroid.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inicializa o mesmo backend youtubedl-android usado pelo Seal.
 * A inicialização apenas faz preparação nativa local e nunca acessa a rede.
 *
 * A versão 0.18.1 do wrapper embarca o QuickJS e adiciona automaticamente
 * --js-runtimes a cada execução do yt-dlp. O executável embutido pode ser mais
 * antigo que o exigido pelo extrator atual do YouTube, então o binário é
 * atualizado separadamente em background, no máximo uma vez por dia.
 */
object SealCompatibleDownloaderBackend {

    private const val TAG = "YtDlpBackend"

    private const val PREFERENCES_NAME = "downloader_ytdlp_updates"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check_epoch_millis"

    /** Intervalo mínimo entre duas verificações de atualização do yt-dlp. */
    private const val UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext

        // Mantém a ordem de inicialização compatível com o Seal.
        YoutubeDL.init(appContext)
        FFmpeg.init(appContext)

        initialized = true
    }

    /**
     * Informa se já passou tempo suficiente desde a última verificação de
     * atualização. Função pura, testável sem Android.
     */
    fun isUpdateCheckDue(
        lastCheckEpochMillis: Long,
        nowEpochMillis: Long,
        intervalMillis: Long = UPDATE_INTERVAL_MILLIS,
    ): Boolean {
        if (lastCheckEpochMillis <= 0L) return true
        if (nowEpochMillis < lastCheckEpochMillis) return true
        return nowEpochMillis - lastCheckEpochMillis >= intervalMillis
    }

    /**
     * Atualiza o yt-dlp respeitando o intervalo mínimo (uma vez por dia).
     *
     * Nunca deve ficar no caminho crítico do usuário: uma falha de rede aqui é
     * apenas registrada e o download prossegue com o binário já instalado.
     *
     * @param force Ignora o intervalo e verifica agora (ação explícita do usuário).
     * @return true quando a atualização foi executada com sucesso.
     */
    suspend fun updateYtDlpIfDue(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            init(context)

            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastCheck = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)

            if (!force && !isUpdateCheckDue(lastCheck, now)) return@withContext false

            val updated = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.MASTER)
            }.onFailure { error ->
                Log.w(TAG, "Falha ao verificar atualização do yt-dlp: ${error.message}")
            }.isSuccess

            if (updated) {
                preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
            }

            updated
        }

    /** Só inicializa o backend. Seguro para chamar da inicialização do app. */
    fun warmUp(context: Context) = runCatching { init(context) }
        .onFailure { error -> Log.w(TAG, "Falha ao preparar o backend: ${error.message}") }
}
