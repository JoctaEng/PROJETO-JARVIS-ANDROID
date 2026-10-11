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

    private val two = listOf("Agenda" to "com.xiaomi.calendar", "Agenda" to "com.google.android.calendar", "Gmail" to "com.google.android.gm")

    @Test fun googleAgendaComDuasAgendasAbreADoGoogle() = assertEquals(1, AppNameMatch.bestApp("Google Agenda", two))
    @Test fun agendaDoGoogle() = assertEquals(1, AppNameMatch.bestApp("agenda do google", two))
    @Test fun agendaDaXiaomi() = assertEquals(0, AppNameMatch.bestApp("agenda da Xiaomi", two))
    @Test fun agendaSemFabricanteFicaComAPrimeira() = assertEquals(0, AppNameMatch.bestApp("agenda", two))
    @Test fun nomeMostradoVoltaAoAppCerto() = assertEquals(1, AppNameMatch.bestApp(AppNameMatch.displayNames(two)[1], two))
    @Test fun nomesRepetidosGanhamFabricante() =
        assertEquals(listOf("Agenda (Xiaomi)", "Agenda (Google)", "Gmail"), AppNameMatch.displayNames(two))
}

