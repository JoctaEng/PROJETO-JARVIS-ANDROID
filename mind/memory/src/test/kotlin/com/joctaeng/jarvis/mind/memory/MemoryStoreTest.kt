package com.joctaeng.jarvis.mind.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreTest {
    private fun tempFile(): File = Files.createTempDirectory("mem").resolve("memory.json").toFile()

    @Test fun persistsAcrossInstances() {
        val file = tempFile()
        var t = 1L
        MemoryStore(file) { t++ }.add("Prefere respostas com fontes", "conversa", "pedido do usuário")
        val reloaded = MemoryStore(file).all().single()
        assertEquals("Prefere respostas com fontes", reloaded.text)
        assertEquals("conversa", reloaded.source)
        assertEquals(1L, reloaded.createdAtMillis)
    }

    @Test fun forgetByMeaningfulWords() {
        var t = 1L
        val store = MemoryStore(tempFile()) { t++ }
        store.add("A aula de física é às terças", "conversa", "")
        store.add("O carro é prata", "conversa", "")
        val forgotten = store.forget("minha aula de física")
        assertEquals("A aula de física é às terças", forgotten?.text)
        assertEquals(listOf("O carro é prata"), store.all().map { it.text })
    }

    @Test fun accentsDoNotPreventMatch() {
        val store = MemoryStore(tempFile())
        store.add("Reunião no DETRAN amanhã", "conversa", "")
        assertEquals("Reunião no DETRAN amanhã", store.forget("reuniao detran")?.text)
    }

    @Test fun forgetWithoutQueryRemovesMostRecent() {
        var t = 1L
        val store = MemoryStore(tempFile()) { t++ }
        store.add("primeira", "conversa", "")
        store.add("segunda", "conversa", "")
        assertEquals("segunda", store.forget(null)?.text)
        assertEquals(listOf("primeira"), store.all().map { it.text })
    }

    @Test fun forgetNothingWhenNoMatch() {
        val store = MemoryStore(tempFile())
        store.add("O carro é prata", "conversa", "")
        assertNull(store.forget("bicicleta azul"))
        assertTrue(store.all().isNotEmpty())
    }

    @Test fun corruptFileStartsEmpty() {
        val file = tempFile().apply { parentFile.mkdirs(); writeText("{não é json") }
        assertTrue(MemoryStore(file).all().isEmpty())
    }
}
