package com.joctaeng.jarvis.system.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptStoreTest {
    private val day = 86_400_000L

    private fun dir() = kotlin.io.path.createTempDirectory("conv").toFile()

    @Test
    fun grava_turnos_em_ordem_e_lê_tudo() {
        val store = TranscriptStore(dir(), clock = { 1_000_000_000_000L })
        store.append("Eu", "oi")
        store.append("Euno", "olá, professor")
        val text = store.readAll()
        assertTrue(text.indexOf("Eu: oi") in 0 until text.indexOf("Euno: olá"))
    }

    @Test
    fun guarda_só_os_dias_pedidos() {
        val d = dir()
        var t = 1_000_000_000_000L
        val store = TranscriptStore(d, keepDays = 2, clock = { t })
        repeat(4) { store.append("Eu", "dia $it"); t += day }
        assertEquals(2, d.listFiles()!!.size)
        assertTrue(!store.readAll().contains("dia 0"))
        assertTrue(store.readAll().contains("dia 3"))
    }

    @Test
    fun pasta_impossivel_não_derruba() {
        TranscriptStore(File("/proc/nao/existe")).append("Eu", "x")
    }
}
