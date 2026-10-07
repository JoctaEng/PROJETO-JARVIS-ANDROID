package com.joctaeng.jarvis.mind.persona

import com.joctaeng.jarvis.core.model.Emotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmotionParserTest {
    @Test fun tagSplitAcrossChunksIsRemoved() {
        val p = StreamingEmotionParser()
        assertEquals("", p.feed("[fel"))
        assertEquals("Oi, Joca!", p.feed("iz] Oi, Joca!"))
        assertEquals(" Tudo bem?", p.feed(" Tudo bem?"))
        assertEquals(Emotion.HAPPY, p.emotion)
    }

    @Test fun textWithoutTagPassesThrough() {
        val p = StreamingEmotionParser()
        assertEquals("Olá!", p.feed("Olá!"))
        assertNull(p.emotion)
    }

    @Test fun unknownBracketIsKeptAsText() {
        val p = StreamingEmotionParser()
        assertEquals("[nota] veja", p.feed("[nota] veja"))
        assertNull(p.emotion)
    }

    @Test fun accentAndCaseInsensitive() {
        val p = StreamingEmotionParser()
        assertEquals("Haha", p.feed("  [Brincalhão]Haha"))
        assertEquals(Emotion.PLAYFUL, p.emotion)
    }

    @Test fun unfinishedTagIsReleasedAtEnd() {
        val p = StreamingEmotionParser()
        assertEquals("", p.feed("[pensa"))
        assertEquals("[pensa", p.finish())
    }
}

class MemoryCommandsTest {
    @Test fun rememberVariants() {
        assertEquals(MemoryCommand.Remember("Eu prefiro respostas com fontes"), MemoryCommands.parse("Lembre que eu prefiro respostas com fontes."))
        assertEquals(MemoryCommand.Remember("Minha aula é às terças"), MemoryCommands.parse("Jarvis, lembra que minha aula é às terças"))
        assertEquals(MemoryCommand.Remember("O carro é prata"), MemoryCommands.parse("lembre-se de que o carro é prata"))
    }

    @Test fun forgetVariants() {
        assertEquals(MemoryCommand.Forget(null), MemoryCommands.parse("Esqueça isto"))
        assertEquals(MemoryCommand.Forget(null), MemoryCommands.parse("joca, esquece isso!"))
        assertEquals(MemoryCommand.Forget("minha aula"), MemoryCommands.parse("Esqueça que minha aula"))
    }

    @Test fun normalSentencesAreNotCommands() {
        assertNull(MemoryCommands.parse("Como está o tempo hoje?"))
        assertNull(MemoryCommands.parse("Você lembra que dia é hoje?"))
        assertNull(MemoryCommands.parse("Lembre que"))
    }
}

class SentenceChunkerTest {
    @Test fun neverSplitsInsideFormulas() {
        val c = SentenceChunker()
        val out = c.feed("Use a fórmula \$x = \\frac{-b \\pm \\sqrt{\\Delta}}{2a}. Ok\$ para achar as raízes. Depois ")
        assertEquals(listOf("Use a fórmula \$x = \\frac{-b \\pm \\sqrt{\\Delta}}{2a}. Ok\$ para achar as raízes."), out)
        val display = SentenceChunker().feed("Veja:\n\$\$a: b. c\$\$\nFim da explicação. Mais ")
        assertEquals(listOf("Veja:\n\$\$a: b. c\$\$", "Fim da explicação."), display)
    }

    @Test fun moneyAndStrayDollarDoNotBlockSpeech() {
        assertEquals(listOf("Custa R\$ 10.", "Muito barato!"), SentenceChunker().feed("Custa R\$ 10. Muito barato! "))
        val out = SentenceChunker().feed("Um cifrão \$ solto aqui\nE a frase seguinte termina. Fim ")
        assertEquals("E a frase seguinte termina.", out.last())
    }

    @Test fun releasesCompleteSentencesOnly() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.feed("Bom dia, Joca"))
        assertEquals(listOf("Bom dia, Joca!"), c.feed("! Tudo certo? Hoje "))
        // "Tudo certo?" é curta demais sozinha e vai junto com a próxima frase.
        assertEquals(listOf("Tudo certo? Hoje temos três tarefas."), c.feed("temos três tarefas. A"))
        assertEquals("A", c.flush())
    }

    @Test fun doesNotSplitDecimalNumbers() {
        val c = SentenceChunker()
        assertEquals(emptyList(), c.feed("O valor é 3.5 reais"))
        assertEquals("O valor é 3.5 reais", c.flush())
    }
}

class PersonaEngineTest {
    @Test fun asksForLatexMathInEveryMode() {
        for (speaking in listOf(true, false)) for (compact in listOf(true, false)) {
            val prompt = PersonaEngine.systemPrompt(
                userName = "Joctã", character = CharacterCatalog.default, characterName = "", mode = PersonaMode.entries.first(),
                memories = emptyList(), context = PromptContext("hoje", offline = false, privateMode = false, speakingAloud = speaking),
                compact = compact,
            )
            assertTrue("LaTeX" in prompt && "\$\\Delta = b^2 - 4ac\$" in prompt, prompt)
        }
    }

    private val ctx = PromptContext("terça-feira, 10:30", offline = false, privateMode = false, speakingAloud = true)

    @Test fun promptCarriesModeMemoriesAndRules() {
        val prompt = PersonaEngine.systemPrompt("Joctã", CharacterCatalog.byId("nina"), "", PersonaMode.TEACHER, listOf("Prefere fontes confiáveis"), ctx)
        assertTrue(PersonaMode.TEACHER.instruction in prompt)
        assertTrue("Prefere fontes confiáveis" in prompt)
        assertTrue("Nunca diga que executou" in prompt)
        assertTrue("falada em voz alta" in prompt)
        assertTrue(EmotionTag.INSTRUCTION in prompt)
    }

    @Test fun compactPromptDropsLongRulesAndLimitsMemories() {
        val memories = (1..30).map { "memória $it" }
        val prompt = PersonaEngine.systemPrompt("Joctã", CharacterCatalog.default, "", PersonaMode.FRIENDLY, memories, ctx, compact = true)
        assertFalse("Regras de caráter" in prompt)
        assertFalse("memória 20\n" in prompt)
        assertTrue("memória 30" in prompt)
    }
}

class CharacterCatalogTest {
    @Test fun twelveCharactersWithoutTheRobot() {
        assertEquals(
            listOf(
                "Joca", "Luna", "Thor", "Nina", "Selene", "Rex", "Maya", "Kiko", "Astra",
                "Joctã Estrategista", "Joctã Casual", "Joctã Jovem",
            ),
            CharacterCatalog.all.map { it.defaultName },
        )
        assertEquals(CharacterCatalog.all.size, CharacterCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals("joca", CharacterCatalog.byId("zig").id)
        assertEquals("joca", CharacterCatalog.byId(null).id)
    }

    @Test fun promptUsesCustomNameAndGrammaticalGender() {
        val ctx = PromptContext("agora", offline = false, privateMode = false, speakingAloud = false)
        val luna = PersonaEngine.systemPrompt("Joctã", CharacterCatalog.byId("luna"), "", PersonaMode.FRIENDLY, emptyList(), ctx)
        assertTrue("Você é a Luna" in luna)
        val renamed = PersonaEngine.systemPrompt("Joctã", CharacterCatalog.byId("thor"), "Trovão", PersonaMode.FRIENDLY, emptyList(), ctx)
        assertTrue("Você é o Trovão" in renamed)
        assertTrue(CharacterCatalog.byId("thor").instruction in renamed)
    }
}
