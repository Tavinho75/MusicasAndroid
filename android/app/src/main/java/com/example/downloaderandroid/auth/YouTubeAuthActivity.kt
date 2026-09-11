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

/**
 * Small native authentication screen used only to establish a YouTube session.
 * No cookie value is displayed or logged.
 */
class YouTubeAuthActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val cookieManager by lazy { CookieManager.getInstance() }

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

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
