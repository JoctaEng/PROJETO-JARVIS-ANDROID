package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals

class AppNameMatchTest {
    private val apps = listOf("Agenda", "Calculadora", "WhatsApp", "WhatsApp Business", "Câmera", "Google Maps", "EduMath")

    @Test fun googleAgendaAchaAgenda() = assertEquals(0, AppNameMatch.best("Google Agenda", apps))
    @Test fun exatoVenceParcial() = assertEquals(2, AppNameMatch.best("whatsapp", apps))
    @Test fun semAcento() = assertEquals(4, AppNameMatch.best("camera", apps))
    @Test fun mapasPeloNomeDoGoogle() = assertEquals(5, AppNameMatch.best("maps", apps))
    @Test fun appDoPrefixo() = assertEquals(1, AppNameMatch.best("aplicativo calculadora", apps))
    @Test fun naoInventa() = assertEquals(-1, AppNameMatch.best("banco xyz", apps))
}
