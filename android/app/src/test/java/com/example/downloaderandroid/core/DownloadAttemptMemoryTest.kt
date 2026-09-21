package com.example.downloaderandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadAttemptMemoryTest {

    @Test
    fun `extrai host de uma url https`() {
        assertEquals("youtube.com", DownloadAttemptMemory.hostOf("https://www.youtube.com/watch?v=abc"))
    }

    @Test
    fun `extrai host de uma url com porta`() {
        assertEquals("localhost", DownloadAttemptMemory.hostOf("http://localhost:8080/musica"))
    }

    @Test
    fun `remove esquema e barra final`() {
        assertEquals("soundcloud.com", DownloadAttemptMemory.hostOf("https://soundcloud.com/artista/faixa/"))
    }

    @Test
    fun `url sem host conhecido nao quebra`() {
        assertEquals("desconhecido", DownloadAttemptMemory.hostOf(""))
    }
}
