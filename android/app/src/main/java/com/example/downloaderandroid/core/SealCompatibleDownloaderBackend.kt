package com.example.downloaderandroid.core

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Initializes the same native Android backend family used by Seal:
 * youtubedl-android + its bundled FFmpeg + aria2c modules.
 *
 * This intentionally keeps the initialization isolated from the UI so the
 * existing Phase 1/2 state machine can continue to evolve independently.
 */
object SealCompatibleDownloaderBackend {

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)
        Aria2c.getInstance().init(appContext)
        initialized = true
    }
}
