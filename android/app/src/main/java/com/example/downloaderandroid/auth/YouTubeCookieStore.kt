package com.example.downloaderandroid.auth

import android.content.Context
import java.io.File

/**
 * Keeps the YouTube session in the app-private storage and exposes it to
 * yt-dlp only through a Netscape-format cookie file.
 *
 * The cookie values are never logged or sent to the JavaScript/hybrid layer.
 */
class YouTubeCookieStore(context: Context) {
    private val appContext = context.applicationContext

    val cookieFile: File
        get() = File(
            File(appContext.filesDir, "youtube-auth"),
            "cookies.txt"
        )

    fun hasCookies(): Boolean = cookieFile.isFile && cookieFile.length() > 0L

    fun saveFromWebViewCookieStrings(cookieStrings: Map<String, String>): Int {
        val parent = cookieFile.parentFile ?: error("Diretório de autenticação indisponível.")
        if (!parent.exists() && !parent.mkdirs()) {
            error("Não foi possível criar o diretório privado de autenticação.")
        }

        val lines = mutableListOf("# Netscape HTTP Cookie File")
        val seen = mutableSetOf<String>()

        for ((host, cookieString) in cookieStrings) {
            val normalizedHost = host.removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
                .removePrefix("www.")

            cookieString.split(';').forEach { rawCookie ->
                val separator = rawCookie.indexOf('=')
                if (separator <= 0) return@forEach

                val name = rawCookie.substring(0, separator).trim()
                val value = rawCookie.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach

                val domain = ".${normalizedHost.ifEmpty { "youtube.com" }}"
                val key = "$domain\t$name\t$value"
                if (!seen.add(key)) return@forEach

                // Session cookies are intentionally stored with expiry 0.
                lines += listOf(
                    domain,
                    "TRUE",
                    "/",
                    "TRUE",
                    "0",
                    name,
                    value
                ).joinToString("\t")
            }
        }

        require(lines.size > 1) {
            "Nenhum cookie do YouTube foi encontrado. Faça login e tente salvar novamente."
        }

        cookieFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return lines.size - 1
    }

    fun clear() {
        if (cookieFile.exists()) cookieFile.delete()
    }
}
