package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate

/** Feriados nacionais do Brasil (fixos e os que dependem da Páscoa), calculados: nada de lista decorada que vence no ano seguinte. */
object BrHolidays {
    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Feriados nacionais oficiais (a Consciência Negra, 20/11, é nacional desde 2024). */
    fun of(year: Int): Map<LocalDate, String> {
        val easter = easter(year)
        val map = linkedMapOf(
            LocalDate.of(year, 1, 1) to "Confraternização Universal (Ano Novo)",
            LocalDate.of(year, 4, 21) to "Tiradentes",
            LocalDate.of(year, 5, 1) to "Dia do Trabalho",
            LocalDate.of(year, 9, 7) to "Independência do Brasil",
            LocalDate.of(year, 10, 12) to "Nossa Senhora Aparecida",
            LocalDate.of(year, 11, 2) to "Finados",
            LocalDate.of(year, 11, 15) to "Proclamação da República",
            LocalDate.of(year, 12, 25) to "Natal",
            easter.minusDays(2) to "Sexta-feira Santa",
        )
        if (year >= 2024) map[LocalDate.of(year, 11, 20)] = "Consciência Negra"
        return map
    }

    /** Pontos facultativos que o povo trata como folga (Carnaval, Corpus Christi). */
    fun optional(year: Int): Map<LocalDate, String> {
        val easter = easter(year)
        return linkedMapOf(
            easter.minusDays(48) to "Carnaval (segunda-feira, ponto facultativo)",
            easter.minusDays(47) to "Carnaval (terça-feira, ponto facultativo)",
            easter.plusDays(60) to "Corpus Christi (ponto facultativo)",
        )
    }

    fun nameOn(d: LocalDate): String? = of(d.year)[d]
    fun optionalOn(d: LocalDate): String? = optional(d.year)[d]

    /** Próximo feriado nacional a partir de [from] (inclusive). */
    fun next(from: LocalDate): Pair<LocalDate, String> {
        val all = (of(from.year) + of(from.year + 1)).toSortedMap()
        val e = all.entries.first { !it.key.isBefore(from) }
        return e.key to e.value
    }

    fun between(from: LocalDate, toExclusive: LocalDate): List<Pair<LocalDate, String>> {
        val all = (of(from.year) + of(toExclusive.year)).toSortedMap()
        return all.filterKeys { !it.isBefore(from) && it.isBefore(toExclusive) }.map { it.key to it.value }
    }
}
