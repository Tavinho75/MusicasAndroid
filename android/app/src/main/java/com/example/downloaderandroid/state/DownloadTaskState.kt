package com.example.downloaderandroid.state

data class DownloadTaskState(
    val id: String,
    val url: String,
    val status: DownloadTaskStatus,
    val title: String? = null,
    val detail: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
