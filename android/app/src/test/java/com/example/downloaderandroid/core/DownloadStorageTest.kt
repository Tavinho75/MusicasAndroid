package com.example.downloaderandroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `remove arquivo existente`() {
        val arquivo = temporaryFolder.newFile("musica.mp3")
        assertTrue(arquivo.exists())

        DownloadStorage.deleteQuietly(arquivo)

        assertFalse(arquivo.exists())
    }

    @Test
    fun `remove pasta com conteudo`() {
        val pasta = temporaryFolder.newFolder("phase4-downloads")
        File(pasta, "resto.webm").writeText("lixo")
        File(pasta, "capa.jpg").writeText("lixo")

        DownloadStorage.deleteQuietly(pasta)

        assertFalse(pasta.exists())
    }

    @Test
    fun `nao falha quando o alvo nao existe`() {
        val inexistente = File(temporaryFolder.root, "nao-existe")

        DownloadStorage.deleteQuietly(inexistente)

        assertFalse(inexistente.exists())
    }
}
