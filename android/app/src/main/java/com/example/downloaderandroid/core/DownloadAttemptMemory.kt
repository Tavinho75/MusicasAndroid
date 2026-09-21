package com.example.downloaderandroid.core

import android.content.Context

/**
 * Lembra qual estratégia de extração funcionou por site.
 *
 * Sem isso, cada download recomeça a escada de tentativas do zero e pode pagar
 * uma ou mais extrações completas que já sabemos que falham (por exemplo, o
 * cliente web do YouTube bloqueando com HTTP 403).
 */
class DownloadAttemptMemory(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Chave da tentativa que funcionou por último para este site, se houver. */
    fun preferredKey(url: String): String? =
        preferences.getString(preferenceKey(url), null)?.takeIf { it.isNotBlank() }

    /** Registra a estratégia que funcionou, para usá-la primeiro nas próximas vezes. */
    fun remember(url: String, attemptKey: String) {
        preferences.edit().putString(preferenceKey(url), attemptKey).apply()
    }

    /** Esquece a estratégia registrada (usado quando ela deixa de funcionar). */
    fun forget(url: String) {
        preferences.edit().remove(preferenceKey(url)).apply()
    }

    private fun preferenceKey(url: String): String = "attempt_for_" + hostOf(url)

    companion object {
        private const val PREFERENCES_NAME = "downloader_attempt_memory"

        /** Extrai o host de uma URL sem depender de java.net.URI. */
        fun hostOf(url: String): String {
            val withoutScheme = url.substringAfter("://", url)
            return withoutScheme
                .substringBefore('/')
                .substringBefore(':')
                .removePrefix("www.")
                .lowercase()
                .ifBlank { "desconhecido" }
        }
    }
}
