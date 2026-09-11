package com.example.downloaderandroid.auth

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Native YouTube authentication screen.
 *
 * The WebView is used only to establish the user's YouTube session. Cookie
 * values are never displayed or logged; they are persisted by YouTubeCookieStore
 * in the application's private storage.
 */
class YouTubeAuthActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The authentication screen owns its layout; do not use an ActionBar.
        actionBar?.hide()
        cookieManager.setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 12)
        }

        val title = TextView(this).apply {
            text = "Autenticação do YouTube"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        status = TextView(this).apply {
            text = "Faça login no YouTube. Depois toque em ‘Salvar autenticação’."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 0)
        }
        header.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Keep this action in the app's own content area, above the WebView,
        // instead of placing it at the bottom where Android navigation controls
        // or an ActionBar could overlap it.
        saveButton = Button(this).apply {
            text = "SALVAR AUTENTICAÇÃO"
            isEnabled = false
            setOnClickListener { saveCookies() }
        }
        root.addView(
            saveButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 4, 16, 12)
            }
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    status.text = "Sessão do YouTube carregada. Faça login, se necessário, e depois salve a autenticação."
                    saveButton.isEnabled = true
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    status.text = "Não foi possível carregar o YouTube. Verifique a internet e tente novamente."
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

        setContentView(root)
        webView.loadUrl("https://www.youtube.com/")
    }

    override fun onResume() {
        super.onResume()
        cookieManager.setAcceptCookie(true)
        if (::webView.isInitialized) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
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
            status.text = "✅ Autenticação salva com segurança."
            saveButton.isEnabled = false
            setResult(RESULT_OK)
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message ?: "Nenhum cookie encontrado.", Toast.LENGTH_LONG).show()
            status.text = "❌ Nenhum cookie de sessão foi encontrado. Faça login e tente novamente."
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
