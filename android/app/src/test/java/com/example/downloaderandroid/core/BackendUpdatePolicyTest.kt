package com.example.downloaderandroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUpdatePolicyTest {

    private val umDia = 24L * 60L * 60L * 1000L

    @Test
    fun `nunca verificou deve verificar`() {
        assertTrue(SealCompatibleDownloaderBackend.isUpdateCheckDue(0L, 1_000L))
    }

    @Test
    fun `verificado agora nao deve verificar de novo`() {
        val agora = 10_000_000L
        assertFalse(SealCompatibleDownloaderBackend.isUpdateCheckDue(agora, agora))
    }

    @Test
    fun `verificado ha 23 horas ainda nao deve verificar`() {
        val agora = 100_000_000L
        assertFalse(SealCompatibleDownloaderBackend.isUpdateCheckDue(agora - umDia + 3_600_000L, agora))
    }

    @Test
    fun `verificado ha mais de um dia deve verificar`() {
        val agora = 100_000_000L
        assertTrue(SealCompatibleDownloaderBackend.isUpdateCheckDue(agora - umDia, agora))
    }

    @Test
    fun `relogio para tras deve forcar verificacao`() {
        assertTrue(SealCompatibleDownloaderBackend.isUpdateCheckDue(50_000L, 10_000L))
    }
}
