package com.joctaeng.jarvis.mind.embed

import com.joctaeng.jarvis.mind.memory.MemoryItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRecallTest {
    // Fatos fictícios (repositório público).
    private val aniversario = memory("a", "o aniversário dela é em 3 de maio", "família", at = 1)
    private val wifi = memory("b", "a senha do Wi-Fi do escritório é ABC123", "casa", at = 2)
    private val reuniao = memory("c", "a reunião de pais é na quinta", "geral", at = 3)
    private val base = listOf(aniversario, wifi, reuniao)

    private class Rig(val list: List<MemoryItem>, val embedder: TextEmbedder? = FakeEmbedder(), val enabled: Boolean = true, minScore: Float = 0.25f, timeoutMs: Long = 3_000) {
        val index = EmbeddingIndexStore(tempFile(), "fake-1", 256)
        val notes = mutableListOf<String>()
        val recall = MemoryRecall(
            memories = { list }, index = index, enabled = { enabled }, embedder = { embedder },
            minScore = minScore, timeoutMs = timeoutMs, onNote = { notes += it },
        )

        suspend fun indexAll() {
            MemoryIndexer(index, FakeEmbedder()).backfill(list)
        }
    }

    @Test fun optionOffReturnsEveryMemoryInOrderLikeVersion0291() = runTest {
        val rig = Rig(base, enabled = false)
        val result = rig.recall.relevant("qualquer coisa")
        assertEquals(MemoryRecall.Mode.ALL, result.mode)
        assertEquals(base, result.items)
        // mesmas linhas que o ConversationController monta hoje
        assertEquals(
            base.map { if (it.category == "geral") it.text else "(${it.category}) ${it.text}" },
            rig.recall.relevantLines("qualquer coisa"),
        )
        assertEquals(0, (rig.embedder as FakeEmbedder).calls.size) // nada foi calculado
        assertEquals(MemoryRecall.Stats(all = 2, semantic = 0, keyword = 0, avgQueryMs = 0), rig.recall.stats())
    }

    @Test fun findsBirthdayByMeaningNotJustTheWord() = runTest {
        val rig = Rig(base); rig.indexAll()
        val r = rig.recall.relevant("quando é o aniversário da minha mãe?")
        assertEquals(MemoryRecall.Mode.SEMANTIC, r.mode)
        assertEquals("a", r.items.first().id)
        assertEquals(EmbedRole.QUERY, (rig.embedder as FakeEmbedder).calls.last().second)
        // a senha do Wi-Fi não vem junto quando a pergunta é outra
        assertTrue(r.items.none { it.id == "b" })
        val w = rig.recall.relevant("qual é a senha do Wi-Fi?")
        assertEquals("b", w.items.first().id)
    }

    @Test fun limitsToEightAndNineHundredChars() = runTest {
        val many = (1..20).map { memory("m%02d".format(it), "fato $it sobre churrasco e futebol do domingo", at = it.toLong()) }
        val rig = Rig(many); rig.indexAll()
        val r = rig.recall.relevant("churrasco domingo", k = 8)
        assertEquals(8, r.items.size)

        val long = (1..20).map { memory("l%02d".format(it), "churrasco ${"x".repeat(150)} $it", at = it.toLong()) }
        val rig2 = Rig(long); rig2.indexAll()
        val r2 = rig2.recall.relevant("churrasco", k = 8, maxChars = 900)
        assertTrue(r2.items.isNotEmpty() && r2.items.size < 8, "devia cortar pelo teto: ${r2.items.size}")
        assertTrue(r2.items.sumOf { MemoryRecall.promptLine(it).length + 1 } <= 900)
    }

    @Test fun capAlwaysKeepsTheMostRelevantEvenIfLong() {
        val huge = memory("h", "z".repeat(2000))
        assertEquals(listOf(huge), MemoryRecall.capChars(listOf(huge, memory("i", "curto")), 900))
    }

    @Test fun embedderThatThrowsFallsBackToWords() = runTest {
        val boom = object : TextEmbedder {
            override val modelId = "fake-1"; override val dims = 256
            override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> = error("motor quebrou")
        }
        val rig = Rig(base, embedder = boom); rig.indexAll()
        val r = rig.recall.relevant("quando é o aniversário?")
        assertEquals(MemoryRecall.Mode.KEYWORD, r.mode)
        assertEquals(listOf("a"), r.items.map { it.id }) // o mesmo que a busca por palavras acharia
        assertTrue(rig.notes.any { it.contains("falhou") })
    }

    @Test fun embedderThatNeverAnswersTimesOutAndFallsBackToWords() = runTest {
        val hang = object : TextEmbedder {
            override val modelId = "fake-1"; override val dims = 256
            override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> = awaitCancellation()
        }
        val rig = Rig(base, embedder = hang, timeoutMs = 3_000); rig.indexAll()
        val r = rig.recall.relevant("senha do wifi do escritório")
        assertEquals(MemoryRecall.Mode.KEYWORD, r.mode)
        assertEquals("b", r.items.first().id)
        assertTrue(rig.notes.any { it.contains("tempo esgotado") })
    }

    @Test fun noModelOnDeviceFallsBackToWords() = runTest {
        val rig = Rig(base, embedder = null); rig.indexAll()
        val r = rig.recall.relevant("aniversário")
        assertEquals(MemoryRecall.Mode.KEYWORD, r.mode)
        assertEquals(listOf("a"), r.items.map { it.id })
    }

    @Test fun emptyIndexFallsBackToWords() = runTest {
        val r = Rig(base).recall.relevant("aniversário dela")
        assertEquals(MemoryRecall.Mode.KEYWORD, r.mode)
        assertEquals(listOf("a"), r.items.map { it.id })
    }

    @Test fun keywordFallbackWithNoMatchSendsTheMostRecent() = runTest {
        val r = Rig(base, embedder = null).recall.relevant("xyzzy qwerty", k = 2)
        assertEquals(listOf("c", "b"), r.items.map { it.id })
    }

    @Test fun newMemoryWithoutVectorStillShowsUpByWords() = runTest {
        val novo = memory("n", "o dentista é na terça às 15h", "geral", at = 9)
        val rig = Rig(base + novo)
        MemoryIndexer(rig.index, FakeEmbedder()).backfill(base) // só as três antigas têm vetor
        val r = rig.recall.relevant("quando é o dentista?")
        assertEquals(MemoryRecall.Mode.SEMANTIC, r.mode)
        assertEquals("n", r.items.first().id)
    }

    @Test fun editedMemoryWithStaleVectorIsNotTrustedByMeaning() = runTest {
        val rig = Rig(base); rig.indexAll()
        val edited = listOf(aniversario.copy(text = "o aniversário dela mudou para 9 de junho"), wifi, reuniao)
        val rig2 = Rig(edited)
        // copia o índice antigo (vetor do texto antigo) para o novo cenário
        rig.index.vectors().forEach { (id, v) -> rig2.index.put(id, rig.index.hashOf(id)!!, v) }
        val r = rig2.recall.relevant("aniversário dela")
        assertEquals("a", r.items.first().id) // achada por palavras, pois o vetor antigo não vale mais
    }

    @Test fun statsCountEachPath() = runTest {
        val rig = Rig(base); rig.indexAll()
        rig.recall.relevant("aniversário dela")
        rig.recall.relevant("senha do wifi")
        assertEquals(2, rig.recall.stats().semantic)
    }
}
