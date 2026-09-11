package com.example.downloaderandroid.auth

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * Native YouTube authentication screen.
 *
 * Cookies stay inside the app-private storage. The screen never displays,
 * logs, or forwards cookie values to the hybrid layer.
 */
class YouTubeAuthActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val cookieManager by lazy { CookieManager.getInstance() }

    private val importCookies =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            try {
                val source = contentResolver.openInputStream(uri)
                    ?: error("Não foi possível abrir o arquivo selecionado.")

                source.use { input ->
                    val text = input.bufferedReader(Charsets.UTF_8).readText()
                    importNetscapeCookies(text)
                }
            } catch (error: Throwable) {
                Toast.makeText(
                    this,
                    error.message ?: "Falha ao importar cookies.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cookieManager.setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        status = TextView(this).apply {
            text = "Entre na sua conta do YouTube. Depois toque em ‘Salvar autenticação’."
            setPadding(24, 24, 24, 16)
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    status.text = "YouTube aberto. Faça login, se necessário, e depois toque em ‘Salvar autenticação’."
                }
            }
            webChromeClient = WebChromeClient()
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val saveButton = Button(this).apply {
            text = "Salvar autenticação"
            setOnClickListener { saveCookies() }
        }
        root.addView(
            saveButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val importButton = Button(this).apply {
            text = "Importar cookies.txt"
            setOnClickListener {
                importCookies.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
            }
        }
        root.addView(
            importButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        webView.loadUrl("https://www.youtube.com/")
    }

    override fun onResume() {
        super.onResume()
        cookieManager.setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    private fun saveCookies() {
        cookieManager.flush()

        val cookies = linkedMapOf<String, String>()
        listOf(
            "https://www.youtube.com/",
            "https://music.youtube.com/",
            "https://m.youtube.com/"
        ).forEach { url ->
            CookieManager.getInstance().getCookie(url)?.let { cookies[url] = it }
        }

        try {
            val count = YouTubeCookieStore(this).saveFromWebViewCookieStrings(cookies)
            Toast.makeText(this, "Autenticação salva com $count cookies.", Toast.LENGTH_LONG).show()
            status.text = "✅ Autenticação salva com segurança no armazenamento privado do aplicativo."
            setResult(RESULT_OK)
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message ?: "Nenhum cookie encontrado.", Toast.LENGTH_LONG).show()
            status.text = "❌ Nenhum cookie de sessão foi encontrado. Faça login e tente novamente."
        }
    }

    private fun importNetscapeCookies(text: String) {
        val headerOk = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { it == "# Netscape HTTP Cookie File" || it == "# HTTP Cookie File" }
            ?: false

        require(headerOk) {
            "O arquivo não está no formato Netscape/Mozilla cookies.txt."
        }

        val filtered = buildString {
            append("# Netscape HTTP Cookie File\n")
            var count = 0

            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 7) continue

                val domain = fields[0].lowercase()
                if (!domain.contains("youtube.com") && !domain.contains("google.com")) {
                    continue
                }

                append(line.trimEnd())
                append('\n')
                count++
            }

            require(count > 0) {
                "Nenhum cookie do YouTube/Google foi encontrado no arquivo."
            }
        }

        val destination = YouTubeCookieStore(this).cookieFile
        destination.parentFile?.mkdirs()
        File(destination.absolutePath).writeText(filtered, Charsets.UTF_8)

        Toast.makeText(this, "Cookies do YouTube importados com segurança.", Toast.LENGTH_LONG).show()
        status.text = "✅ Cookies importados. Você já pode voltar e testar o download."
        setResult(RESULT_OK)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
