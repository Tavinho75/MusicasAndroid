package com.example.downloaderandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpOutputParserTest {

    @Test
    fun `extrai titulo da linha de destino`() {
        assertEquals(
            "Nome da Música",
            YtDlpOutputParser.titleFromLine("[download] Destination: /tmp/phase4-1/Nome da Música.webm"),
        )
    }

    @Test
    fun `extrai titulo da linha de mesclagem`() {
        assertEquals(
            "Outra Faixa",
            YtDlpOutputParser.titleFromLine("[Merger] Merging formats into \"Outra Faixa.webm\""),
        )
    }

    @Test
    fun `extrai titulo da linha de extracao de audio`() {
        assertEquals(
            "Faixa Convertida",
            YtDlpOutputParser.titleFromLine("[ExtractAudio] Destination: Faixa Convertida.mp3"),
        )
    }

    @Test
    fun `extrai titulo de arquivo ja baixado`() {
        assertEquals(
            "Ja Baixada",
            YtDlpOutputParser.titleFromLine("[download] Ja Baixada.mp3 has already been downloaded"),
        )
    }

    @Test
    fun `linhas sem informacao de arquivo devolvem null`() {
        assertNull(YtDlpOutputParser.titleFromLine("[download]  45.0% of 3.2MiB at 1.1MiB/s ETA 00:02"))
        assertNull(YtDlpOutputParser.titleFromLine(""))
        assertNull(YtDlpOutputParser.titleFromLine("Deleting original file"))
    }

    @Test
    fun `caminho com pastas usa apenas o nome do arquivo`() {
        assertEquals(
            "Musica",
            YtDlpOutputParser.titleFromPath("/storage/emulated/0/Android/data/app/files/phase4-downloads/1/Musica.mp3"),
        )
        assertEquals(
            "Windows",
            YtDlpOutputParser.titleFromPath("C:\\Users\\user\\Musicas\\Windows.mp3"),
        )
    }
}
