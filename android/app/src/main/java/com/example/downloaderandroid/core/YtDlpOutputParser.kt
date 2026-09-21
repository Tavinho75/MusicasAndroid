package com.example.downloaderandroid.core

/**
 * Interpreta as linhas de saída do yt-dlp executado com ``--newline``.
 *
 * Serve para descobrir o título da faixa durante o próprio download, evitando
 * uma extração completa de metadados só para saber o nome da música.
 *
 * Não depende do Android, portanto é testável em JVM pura.
 */
object YtDlpOutputParser {

    private val destinationPattern = Regex(
        """^\[[^\]]+]\s*(?:Destination|Merging formats into|Extracting audio[^:]*):\s*(.+)$"""
    )

    private val alreadyDownloadedPattern = Regex(
        """^\[download]\s*(.+?)\s+has already been downloaded"""
    )

    /**
     * Extrai o título (nome do arquivo sem extensão) de uma linha de saída do
     * yt-dlp. Devolve null quando a linha não traz essa informação.
     */
    fun titleFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val rawPath = destinationPattern.find(trimmed)?.groupValues?.get(1)?.trim()
            ?: alreadyDownloadedPattern.find(trimmed)?.groupValues?.get(1)?.trim()
            ?: return null

        return titleFromPath(rawPath)
    }

    /**
     * Converte um caminho de arquivo em título legível: remove aspas, diretórios
     * e a extensão.
     */
    fun titleFromPath(rawPath: String): String? {
        val path = rawPath.trim().trim('"', '\'')
        if (path.isEmpty()) return null

        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isEmpty()) return null

        val withoutExtension = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        return withoutExtension.trim().takeIf { it.isNotEmpty() }
    }
}
