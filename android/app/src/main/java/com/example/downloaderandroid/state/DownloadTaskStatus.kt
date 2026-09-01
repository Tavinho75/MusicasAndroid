package com.example.downloaderandroid.state

enum class DownloadTaskStatus {
    DRAFT,
    ANALYZING,
    READY,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
