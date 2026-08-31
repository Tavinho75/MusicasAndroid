package com.example.downloaderandroid.media

import java.io.File

interface MediaProcessor {
    suspend fun convertToMp3(
        input: File,
        output: File,
        bitrateKbps: Int
    ): MediaProcessResult
}

data class MediaProcessResult(
    val success: Boolean,
    val output: File?,
    val detail: String
)
