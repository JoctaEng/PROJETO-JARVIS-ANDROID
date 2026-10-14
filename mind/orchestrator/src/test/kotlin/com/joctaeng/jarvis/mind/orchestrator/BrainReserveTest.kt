package com.joctaeng.jarvis.mind.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainReserveTest {
    private val models = listOf(
        "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.5-flash-preview-09-2026", "gemini-3.8-flash-tts",
        "gemini-2.5-flash", "gemini-3.5-pro", "gemini-embedding-001", "gemma-3-27b-it", "gemini-3.1-flash-image",
    )

    @Test fun picksNewestStableFlashThatIsNotThePrimary() = assertEquals("gemini-3.5-flash", BrainReserve.pickGemini(models, "gemini-3.1-flash-lite"))
    @Test fun whenPrimaryIsFlashPicksAnother() = assertEquals("gemini-3.5-flash-preview-09-2026", BrainReserve.pickGemini(models, "gemini-3.5-flash"))
    @Test fun nothingUsable() = assertNull(BrainReserve.pickGemini(listOf("gemini-3.8-flash-tts", "gemini-embedding-001"), "x"))
}

class ToolSelectorTest {
    private val all = listOf(
        "abrir_app", "tela_ler", "tela_tocar", "tela_navegar", "tela_rolar", "tela_digitar", "tela_ver", "agenda_consultar", "agenda_salvar",
        "agenda_editar", "agenda_apagar", "clima", "agenda_abrir", "pesquisar_e_resumir", "memoria_guardar", "whatsapp_mensagem", "contatos_buscar", "gmail_buscar",
        "joca_financas_resumo", "joca_financas_lancar", "nao_perturbe", "criar_timer", "lanterna",
    )

    @Test fun smallTalkGetsOnlyTheCore() = assertEquals(ToolSelector.core, ToolSelector.select(all, "e aí, tudo bem?"))

    @Test fun topicAddsItsTools() {
        val s = ToolSelector.select(all, "manda uma mensagem no whats para a Maria")
        assertTrue("whatsapp_mensagem" in s && "contatos_buscar" in s && "lanterna" !in s)
    }

    @Test fun deleteWordsBringAgendaDelete() = assertTrue("agenda_apagar" in ToolSelector.select(all, "entendi pode apagar futebol"))

    @Test fun weatherTool() = assertTrue("clima" in ToolSelector.select(all, "vai chover hoje?"))

    @Test fun prefixToolsForFinance() = assertTrue("joca_financas_resumo" in ToolSelector.select(all, "quanto tenho de saldo?"))

    @Test fun skillStepsAddTools() = assertTrue("nao_perturbe" in ToolSelector.select(all, "bom dia", extraText = "1) nao_perturbe ligar=true"))

    @Test fun capped() = assertTrue(ToolSelector.select(all, "agenda whats email tela aula saldo lanterna", max = 12).size <= 12)

    @Test fun localGetsTheTopicFirstAndStaysSmall() {
        val s = ToolSelector.forLocal(all, "liga a lanterna por favor")
        assertTrue(s.first() == "lanterna", s.joinToString())
        assertTrue(s.size <= 8)
        assertTrue(ToolSelector.core.take(3).all { it in s } || s.size == 8)
    }
}
