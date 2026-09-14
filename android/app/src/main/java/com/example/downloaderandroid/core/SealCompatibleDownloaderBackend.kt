package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes the same youtubedl-android backend family used by Seal.
 * Initialization is intentionally isolated from the UI and from the existing
 * Phase 1/2 state machine.
 *
 * The 0.18.1 Android wrapper bundles QuickJS and automatically adds
 * --js-runtimes to every yt-dlp invocation. Its embedded yt-dlp executable can
 * be older than the current YouTube extractor requirements, however. We use
 * youtubedl-android's built-in free updater to install the latest yt-dlp master
 * build on first initialization (and whenever the stored version is stale).
 */
object SealCompatibleDownloaderBackend {

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext

        // Match Seal's current initialization order/API exactly.
        YoutubeDL.init(appContext)
        FFmpeg.init(appContext)
        Aria2c.init(appContext)

        // 0.18.1 supplies QuickJS, but its bundled yt-dlp may not yet know
        // --js-runtimes. Updating the actual yt-dlp binary fixes that mismatch
        // and also keeps the YouTube extractor current without paid components.
        YoutubeDL.getInstance().updateYoutubeDL(
            appContext,
            YoutubeDL.UpdateChannel.MASTER,
        )

        initialized = true
    }
}
