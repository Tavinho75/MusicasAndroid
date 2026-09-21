package com.example.downloaderandroid.core

/**
 * Limita a frequência com que o progresso de um download é publicado.
 *
 * O yt-dlp emite dezenas de linhas de progresso por segundo. Persistir e
 * notificar a cada linha gera escrita em disco e atualização de notificação em
 * rajada, sem ganho perceptível para o usuário.
 *
 * O relógio é injetável para permitir teste unitário puro (sem Android).
 */
class ProgressThrottle(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val minPercentDelta: Float = DEFAULT_MIN_PERCENT_DELTA,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private var lastPublishedAt = Long.MIN_VALUE / 2
    private var lastPublishedPercent = Float.NaN

    /**
     * Informa se este progresso deve ser publicado agora.
     *
     * @param percent Progresso atual, em porcentagem.
     * @param force Quando true, publica imediatamente (usado em transições de estado).
     */
    fun shouldPublish(percent: Float, force: Boolean = false): Boolean {
        if (force) {
            record(percent)
            return true
        }

        val elapsed = now() - lastPublishedAt
        if (elapsed >= minIntervalMillis) {
            record(percent)
            return true
        }

        if (!lastPublishedPercent.isNaN() && percent - lastPublishedPercent >= minPercentDelta) {
            record(percent)
            return true
        }

        return false
    }

    /** Descarta o histórico, forçando a próxima publicação a acontecer. */
    fun reset() {
        lastPublishedAt = Long.MIN_VALUE / 2
        lastPublishedPercent = Float.NaN
    }

    private fun record(percent: Float) {
        lastPublishedAt = now()
        lastPublishedPercent = percent
    }

    companion object {
        private const val DEFAULT_MIN_INTERVAL_MILLIS = 1_000L
        private const val DEFAULT_MIN_PERCENT_DELTA = 1f
    }
}
