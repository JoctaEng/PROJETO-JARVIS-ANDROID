package com.joctaeng.jarvis.mind.embed

import com.joctaeng.jarvis.mind.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingIndexStoreTest {
    private fun unit(dims: Int, i: Int) = FloatArray(dims).also { it[i % dims] = 1f }

    @Test fun roundTripThroughJson() {
        val f = tempFile()
        val a = EmbeddingIndexStore(f, "m1", 4)
        a.put("x", "h1", floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
        a.put("y", "h2", floatArrayOf(1f, 0f, 0f, 0f))
        val b = EmbeddingIndexStore(f, "m1", 4)
        assertEquals(setOf("x", "y"), b.ids())
        assertEquals("h1", b.hashOf("x"))
        assertEquals(0.5f, b.vectors().getValue("x")[2])
        assertFalse(b.discardedOnLoad)
        val text = f.readText()
        assertTrue(text.contains("\"model\"") && text.contains("\"dims\"") && text.contains("\"prefixVersion\"") && text.contains("\"textHash\""))
    }

    @Test fun changingModelOrDimsOrPrefixDiscardsEverything() {
        val f = tempFile()
        EmbeddingIndexStore(f, "m1", 4).put("x", "h", unit(4, 0))
        assertTrue(EmbeddingIndexStore(f, "m1", 4).ids().isNotEmpty())

        val other = EmbeddingIndexStore(f, "m2", 4)
        assertTrue(other.ids().isEmpty()); assertTrue(other.discardedOnLoad)

        EmbeddingIndexStore(f, "m1", 4).put("x", "h", unit(4, 0))
        val dims = EmbeddingIndexStore(f, "m1", 8)
        assertTrue(dims.ids().isEmpty()); assertTrue(dims.discardedOnLoad)

        EmbeddingIndexStore(f, "m1", 4).put("x", "h", unit(4, 0))
        val prefix = EmbeddingIndexStore(f, "m1", 4, prefixVersion = 99)
        assertTrue(prefix.ids().isEmpty()); assertTrue(prefix.discardedOnLoad)
    }

    @Test fun corruptFileBecomesEmptyIndex() {
        val f = tempFile().also { it.writeText("{isto não é json") }
        assertEquals(0, EmbeddingIndexStore(f, "m1", 4).size())
    }

    @Test fun removeDeletesTheVectorOnDisk() {
        val f = tempFile()
        val s = EmbeddingIndexStore(f, "m1", 4)
        s.put("x", "h", unit(4, 0)); s.put("y", "h", unit(4, 1))
        assertTrue(s.remove("x"))
        assertFalse(s.remove("x"))
        assertEquals(setOf("y"), EmbeddingIndexStore(f, "m1", 4).ids())
        assertFalse(f.readText().contains("\"x\""))
    }

    @Test fun rejectsVectorOfWrongSize() {
        assertFailsWith<IllegalArgumentException> { EmbeddingIndexStore(tempFile(), "m1", 4).put("x", "h", FloatArray(3)) }
    }

    @Test fun hashIsStableAndIgnoresOuterSpaces() {
        assertEquals(EmbeddingIndexStore.hash("oi"), EmbeddingIndexStore.hash("  oi "))
        assertTrue(EmbeddingIndexStore.hash("oi") != EmbeddingIndexStore.hash("tchau"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryIndexerTest {
    private fun many(n: Int) = (1..n).map { memory("m%02d".format(it), "fato número $it sobre assunto$it") }

    @Test fun backfillWorksInBatchesOfEightWithProgress() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val progress = mutableListOf<Int>()
        val r = MemoryIndexer(idx, fake).backfill(many(20)) { done, total -> assertEquals(20, total); progress += done }
        assertEquals(listOf(8, 8, 4), fake.calls.map { it.first.size })
        assertTrue(fake.calls.all { it.second == EmbedRole.DOCUMENT })
        assertEquals(listOf(0, 8, 16, 20), progress)
        assertEquals(20, r.indexed); assertEquals(0, r.upToDate); assertEquals(20, idx.size())
    }

    @Test fun unchangedTextIsNotRecomputed() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val indexer = MemoryIndexer(idx, fake)
        val list = many(5)
        indexer.backfill(list)
        val before = fake.textsEmbedded
        val again = indexer.backfill(list)
        assertEquals(before, fake.textsEmbedded) // nenhuma chamada nova
        assertEquals(0, again.indexed); assertEquals(5, again.upToDate)
        // mudou o texto de uma: só ela é refeita
        indexer.backfill(list.mapIndexed { i, m -> if (i == 2) m.copy(text = "texto novo e diferente") else m })
        assertEquals(before + 1, fake.textsEmbedded)
        assertFalse(indexer.indexOne(list[0])) // indexOne com texto igual também não recalcula
        assertEquals(before + 1, fake.textsEmbedded)
    }

    @Test fun backfillResumesAfterCancellation() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val indexer = MemoryIndexer(idx, fake)
        val list = many(20)
        var batches = 0
        assertFailsWith<CancellationException> {
            indexer.backfill(list) { done, _ -> if (done >= 8 && ++batches == 1) throw CancellationException("cancelado") }
        }
        assertEquals(8, idx.size()) // o primeiro lote ficou guardado
        val before = fake.textsEmbedded
        val r = indexer.backfill(list)
        assertEquals(12, fake.textsEmbedded - before) // só o que faltava
        assertEquals(8, r.upToDate); assertEquals(20, idx.size())
    }

    @Test fun backfillDropsVectorsOfDeletedMemories() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val indexer = MemoryIndexer(idx, fake)
        val list = many(4)
        indexer.backfill(list)
        indexer.backfill(list.drop(2))
        assertEquals(setOf("m03", "m04"), idx.ids())
    }

    @Test fun privateModeWritesNothing() = runTest {
        val fake = FakeEmbedder()
        val f = tempFile()
        val idx = EmbeddingIndexStore(f, fake.modelId, fake.dims)
        val indexer = MemoryIndexer(idx, fake, privateMode = { true })
        assertFalse(indexer.indexOne(memory("a", "segredo")))
        val r = indexer.backfill(many(3))
        assertTrue(r.skippedPrivate)
        assertEquals(0, fake.calls.size)
        assertEquals(0, idx.size())
        assertFalse(f.exists())
    }

    @Test fun deletingMemoryDeletesVectorViaStoreListener() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var on = true
        val store = MemoryStore(File(Files.createTempDirectory("mem").toFile(), "memory.json"))
        store.listener = IndexingMemoryListener(MemoryIndexer(idx, fake), scope, active = { on })
        val ana = store.add("o aniversário dela é em 3 de maio", "t", "t")
        val wifi = store.add("a senha do Wi-Fi do escritório é ABC123", "t", "t")
        assertEquals(setOf(ana.id, wifi.id), idx.ids())
        store.remove(ana.id)
        assertEquals(setOf(wifi.id), idx.ids())
        // editar troca o vetor (texto novo → hash novo)
        val oldHash = idx.hashOf(wifi.id)
        store.update(wifi.id, "a senha do Wi-Fi da sala é XYZ789", "casa")
        assertNotNull(idx.hashOf(wifi.id)); assertTrue(idx.hashOf(wifi.id) != oldHash)
        // opção desligada: não calcula vetor novo, mas apagar continua apagando
        on = false
        val calls = fake.calls.size
        val third = store.add("reunião de pais é na quinta", "t", "t")
        assertEquals(calls, fake.calls.size); assertNull(idx.hashOf(third.id))
        store.forget("senha")
        assertTrue(idx.ids().isEmpty())
        store.clear()
        assertEquals(0, idx.size())
    }

    @Test fun privateModeInStoreListenerDoesNotIndex() = runTest {
        val fake = FakeEmbedder()
        val idx = EmbeddingIndexStore(tempFile(), fake.modelId, fake.dims)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val store = MemoryStore(File(Files.createTempDirectory("mem").toFile(), "memory.json"))
        store.listener = IndexingMemoryListener(MemoryIndexer(idx, fake, privateMode = { true }), scope, active = { true })
        store.add("algo particular", "t", "t")
        assertEquals(0, idx.size()); assertEquals(0, fake.calls.size)
    }
}
