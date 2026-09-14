package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes the same youtubedl-android backend family used by Seal.
 * Initialization is intentionally isolated from the UI and from the existing
 * Phase 1/2 state machine.
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
}
