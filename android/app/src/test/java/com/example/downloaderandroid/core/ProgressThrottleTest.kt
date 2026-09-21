package com.example.downloaderandroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {

    private var clock = 0L
    private fun throttle(interval: Long = 1_000L, delta: Float = 1f) =
        ProgressThrottle(minIntervalMillis = interval, minPercentDelta = delta) { clock }

    @Test
    fun `primeira leitura e sempre publicada`() {
        assertTrue(throttle().shouldPublish(1f))
    }

    @Test
    fun `leituras dentro do intervalo sao descartadas`() {
        val throttle = throttle()

        assertTrue(throttle.shouldPublish(1f))
        clock += 200L
        assertFalse(throttle.shouldPublish(1.5f))
        clock += 200L
        assertFalse(throttle.shouldPublish(1.8f))
    }

    @Test
    fun `publica quando o intervalo minimo passa`() {
        val throttle = throttle()

        assertTrue(throttle.shouldPublish(1f))
        clock += 1_000L
        assertTrue(throttle.shouldPublish(1.2f))
    }

    @Test
    fun `publica quando o progresso avanca o suficiente`() {
        val throttle = throttle()

        assertTrue(throttle.shouldPublish(10f))
        clock += 100L
        assertFalse(throttle.shouldPublish(10.5f))
        assertTrue(throttle.shouldPublish(25f))
    }

    @Test
    fun `force publica mesmo dentro do intervalo`() {
        val throttle = throttle()

        assertTrue(throttle.shouldPublish(1f))
        assertTrue(throttle.shouldPublish(1f, force = true))
    }

    @Test
    fun `reset libera a proxima publicacao`() {
        val throttle = throttle()

        assertTrue(throttle.shouldPublish(1f))
        assertFalse(throttle.shouldPublish(1f))
        throttle.reset()
        assertTrue(throttle.shouldPublish(1f))
    }
}
