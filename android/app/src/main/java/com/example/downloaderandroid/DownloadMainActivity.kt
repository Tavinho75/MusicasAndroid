package com.example.downloaderandroid

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import com.example.downloaderandroid.auth.YouTubeAuthActivity

/**
 * Thin launcher wrapper around the existing MainActivity.
 *
 * It adds a direct YouTube login action without changing the existing Phase
 * 1/2/3 test implementation. The button is part of the activity content,
 * not an ActionBar or Android navigation bar.
 */
class DownloadMainActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val topSpace = (76 * density).toInt()

        // Reserve a dedicated content area for the login action so it does not
        // cover the existing Compose screen underneath it.
        findViewById<FrameLayout>(android.R.id.content)?.setPadding(
            0,
            topSpace,
            0,
            0
        )

        val loginButton = Button(this).apply {
            text = "Entrar no YouTube"
            contentDescription = "Entrar no YouTube e salvar autenticação"
            setOnClickListener {
                startActivity(Intent(this@DownloadMainActivity, YouTubeAuthActivity::class.java))
            }
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (8 * density).toInt()
        }

        addContentView(loginButton, params)
    }
}
