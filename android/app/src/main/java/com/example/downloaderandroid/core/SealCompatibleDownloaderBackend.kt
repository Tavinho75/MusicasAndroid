package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Initializes the same youtubedl-android backend family used by Seal.
 * Initialization only performs local/native setup and never performs network I/O.
 *
 * The 0.18.1 Android wrapper bundles QuickJS and automatically adds
 * --js-runtimes to every yt-dlp invocation. Its embedded yt-dlp executable can
 * be older than the current YouTube extractor requirements, so the actual
 * yt-dlp binary is updated separately on an IO dispatcher before downloads.
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

        initialized = true
    }

    /**
     * Checks for and installs the latest free yt-dlp MASTER build without ever
     * blocking Android's main thread. This must happen before a real download.
     */
    suspend fun ensureYtDlpUpdated(context: Context) = withContext(Dispatchers.IO) {
        init(context)
        YoutubeDL.getInstance().updateYoutubeDL(
            context.applicationContext,
            YoutubeDL.UpdateChannel.MASTER,
        )
    }
}
