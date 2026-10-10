package com.joctaeng.jarvis.mind.memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeBookTest {
    private val steps = listOf(
        RecipeStep("abrir_app", """{"nome":"Gmail"}"""),
        RecipeStep("tela_tocar", """{"nome":"Pesquisar"}"""),
        RecipeStep("tela_digitar", """{"texto":"fatura"}"""),
    )

    @Test fun findsSimilarRequestAndPersists() {
        val file = Files.createTempFile("receitas", ".json").toFile().also { it.delete() }
        val book = RecipeBook(file)
        book.record("pesquisa a fatura no Gmail", steps)
        val again = RecipeBook(file)
        val r = assertNotNull(again.find("pesquisa fatura de luz no Gmail"))
        assertEquals(3, r.steps.size)
        assertTrue("abrir_app" in again.describe(r))
        assertNull(again.find("qual a previsão do tempo"))
    }

    @Test fun ignoresTrivialOrNonScreenPaths() {
        val book = RecipeBook(null)
        book.record("abre o gmail", listOf(RecipeStep("abrir_app", "{}")))
        book.record("agenda de hoje", listOf(RecipeStep("agenda_consultar", "{}"), RecipeStep("agenda_consultar", "{}")))
        assertTrue(book.all().isEmpty())
    }

    @Test fun sameRequestUpdatesInsteadOfDuplicating() {
        val book = RecipeBook(null)
        book.record("pesquisa a fatura no Gmail", steps)
        book.record("pesquisa a fatura no Gmail", steps)
        assertEquals(1, book.all().size)
        assertEquals(2, book.all().single().uses)
    }
}
