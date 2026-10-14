package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenProseTest {
    @Test fun datesAndTimes() {
        assertEquals("Reunião dia 15 de outubro das 8 horas às 9 e 30.", SpokenProse.normalize("Reunião dia 15/10 08:00–09:30."))
        assertEquals("Futebol às 19 horas", SpokenProse.normalize("Futebol às 19h"))
        assertEquals("Às 8 e 30, aula", SpokenProse.normalize("Às 8h30, aula"))
        assertEquals("Vence em 24 de outubro de 2026", SpokenProse.normalize("Vence em 24/10/2026"))
        assertEquals("Começa ao meio-dia", SpokenProse.normalize("Começa ao meio-dia"))
    }

    @Test fun codesAndDashes() {
        assertEquals("MP Vence prazo, Ofício número, Escola A, SIMP", SpokenProse.normalize("[MP] Vence prazo – Ofício nº 213/2026 – Escola A – SIMP 029809-500/2026"))
        assertEquals("o e-mail chegou", SpokenProse.normalize("o e-mail chegou"))
    }

    @Test fun fractionsAndMoneyStay() {
        assertEquals("a razão 3/4 e R$ 1.010,56", SpokenProse.normalize("a razão 3/4 e R$ 1.010,56"))
    }
}
