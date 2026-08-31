package com.example.downloaderandroid.core

interface ExtractorEngine {
    suspend fun probe(url: String): ExtractorProbeResult
}

data class ExtractorProbeResult(
    val initialized: Boolean,
    val message: String
)
