package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryAppointmentTest {
    @Test fun anoteComDataEHoraVaiParaAgenda() = assertNull(MemoryCommands.parse("Anote aí no dia 15 de Outubro às 17 horas ir para casa"))
    @Test fun anoteNaAgendaVaiParaAgenda() = assertNull(MemoryCommands.parse("anota aí na agenda reunião com a coordenação"))
    @Test fun anoteAmanhaVaiParaAgenda() = assertNull(MemoryCommands.parse("anote que amanhã tenho dentista"))

    @Test fun anoteSemDataContinuaMemoria() =
        assertEquals(MemoryCommand.Remember("Minha cor preferida é azul"), MemoryCommands.parse("anote que minha cor preferida é azul"))

    @Test fun lembreQueComDataContinuaMemoria() =
        assertEquals(MemoryCommand.Remember("Meu aniversário é dia 5 de maio"), MemoryCommands.parse("lembre que meu aniversário é dia 5 de maio"))

    @Test fun esqueca() = assertEquals(MemoryCommand.Forget(null), MemoryCommands.parse("esqueça isso"))
}
