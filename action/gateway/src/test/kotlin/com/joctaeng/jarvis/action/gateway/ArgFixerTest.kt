package com.joctaeng.jarvis.action.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArgFixerTest {
    private val openApp = """{"type":"object","properties":{"nome":{"type":"string"}},"required":["nome"]}"""
    private val agenda = """{"type":"object","properties":{"titulo":{"type":"string"},"inicio":{"type":"string"}},"required":["titulo","inicio"]}"""
    private val memory = """{"type":"object","properties":{"fato":{"type":"string"},"categoria":{"type":"string"}},"required":["fato"]}"""

    @Test fun apelidoViraONomeDoEsquema() =
        assertEquals("""{"app":"Agenda","nome":"Agenda"}""", ArgFixer.normalize("""{"app":"Agenda"}""", openApp))

    @Test fun acentoEMaiusculaNaoAtrapalham() =
        assertTrue(ArgFixer.normalize("""{"Título":"Almoço","inicio":"12/10 12:00"}""", agenda).contains("\"titulo\":\"Almoço\""))

    @Test fun unicoValorSobrandoPreencheOObrigatorio() =
        assertTrue(ArgFixer.normalize("""{"frase":"Almoço em família dia 12"}""", memory).contains("\"fato\":\"Almoço em família dia 12\""))

    @Test fun naoTrocaValorQueJaVeioCerto() =
        assertEquals("""{"nome":"WhatsApp","app":"Agenda"}""", ArgFixer.normalize("""{"nome":"WhatsApp","app":"Agenda"}""", openApp))

    @Test fun faltandoMostraOsCampos() {
        assertEquals(listOf("nome"), ArgFixer.missingRequired("{}", openApp))
        assertEquals("campos=[x(3)]", ArgFixer.shape("""{"x":"abc"}"""))
    }
}
