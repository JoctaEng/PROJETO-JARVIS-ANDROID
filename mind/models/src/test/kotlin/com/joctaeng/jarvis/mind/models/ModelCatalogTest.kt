package com.joctaeng.jarvis.mind.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    private val catalog = ModelCatalog.bundled()

    @Test
    fun `catalogo do app abre e tem so modelos conferidos`() {
        assertEquals(1, catalog.version)
        assertEquals("2026-10-18", catalog.verifiedAt)
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b", "qwen3-4b-gguf", "embeddinggemma-2-270m"), catalog.models.map { it.id })
    }

    @Test
    fun `gemma 4 E2B continua o mesmo arquivo do download antigo`() {
        val e2b = assertNotNull(catalog.byId("gemma-4-e2b"))
        assertEquals("gemma-4-E2B-it.litertlm", e2b.file)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", e2b.url)
        assertEquals("https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/tree/main", e2b.treeApi)
        assertTrue(e2b.has(ModelCapability.TOOLS) && e2b.has(ModelCapability.VISION) && e2b.has(ModelCapability.AUDIO))
    }

    @Test
    fun `embedding tem tamanho e sha exatos do ADR 0014`() {
        val emb = assertNotNull(catalog.byFile("embeddinggemma-2-text-270m.litertlm"))
        assertEquals(ModelKind.EMBEDDING, emb.kind)
        assertEquals(164_626_432L, emb.sizeBytes)
        assertEquals(false, emb.sizeApproximate)
        assertEquals("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb", emb.sha256)
        assertTrue(emb !in catalog.chatModels)
    }

    @Test
    fun `todos os campos fazem sentido`() {
        catalog.models.forEach { m ->
            assertTrue(m.file.endsWith(m.engine.extension), m.id)
            assertTrue(m.minRamMb <= m.recommendedRamMb, m.id)
            assertTrue(m.license.isNotBlank() && m.source.startsWith("https://huggingface.co/"), m.id)
            if (m.engine == ModelEngine.LITERTLM) assertNotNull(m.minLiteRtLm, m.id)
        }
    }

    @Test
    fun `json invalido da erro claro`() {
        val base = """{"versao":1,"verificadoEm":"2026-10-18","modelos":[%s]}"""
        val ok = """{"id":"a","nome":"A","motor":"litertlm","tipo":"chat","repo":"o/r","arquivo":"a.litertlm","tamanhoBytes":10,"ramMinimaMb":1024,"ramRecomendadaMb":2048,"capacidades":["texto"],"licenca":"MIT","fonte":"https://huggingface.co/o/r"}"""
        assertEquals(1, ModelCatalog.parse(base.format(ok)).models.size)
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(base.format("$ok,$ok")) }
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(base.format(ok.replace("a.litertlm", "a.gguf"))) }
        assertFailsWith<IllegalStateException> { ModelCatalog.parse(base.format(ok.replace("\"texto\"", "\"voar\""))) }
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(base.format(ok.replace("a.litertlm", "../a.litertlm"))) }
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(base.format(ok.replace("\"ramMinimaMb\":1024", "\"ramMinimaMb\":4096"))) }
    }
}

class DeviceFitTest {
    private val catalog = ModelCatalog.bundled()
    private val gb = 1L shl 30
    private val userRam = (11.4 * gb).toLong()

    @Test
    fun `aparelho do usuario com 11,4 GB recomenda E2B e E4B`() {
        val e2b = DeviceFit.rate(catalog.byId("gemma-4-e2b")!!, userRam, 200 * gb, false, "0.17.1")
        val e4b = DeviceFit.rate(catalog.byId("gemma-4-e4b")!!, userRam, 200 * gb, false, "0.17.1")
        assertEquals(FitLevel.RECOMMENDED, e2b.level)
        assertEquals(FitLevel.RECOMMENDED, e4b.level)
    }

    @Test
    fun `pouca RAM e pouco espaco viram pesado demais`() {
        val e4b = catalog.byId("gemma-4-e4b")!!
        assertEquals(FitLevel.TOO_HEAVY, DeviceFit.rate(e4b, 4 * gb, 200 * gb, false, "0.17.1").level)
        assertEquals(FitLevel.WORKS, DeviceFit.rate(e4b, 7 * gb, 200 * gb, false, "0.17.1").level)
        val noSpace = DeviceFit.rate(e4b, userRam, 2 * gb, false, "0.17.1")
        assertEquals(FitLevel.TOO_HEAVY, noSpace.level)
        assertTrue(noSpace.reason.contains("espaço"))
        // Já baixado: espaço não importa.
        assertEquals(FitLevel.RECOMMENDED, DeviceFit.rate(e4b, userRam, 0, true, "0.17.1").level)
    }

    @Test
    fun `versao do LiteRT-LM menor que a exigida bloqueia`() {
        val m = catalog.byId("gemma-4-e2b")!!.copy(minLiteRtLm = "0.18.0")
        assertEquals(FitLevel.TOO_HEAVY, DeviceFit.rate(m, userRam, 200 * gb, false, "0.17.1").level)
        assertTrue(DeviceFit.compareVersions("0.17.1", "0.17") > 0)
        assertEquals(0, DeviceFit.compareVersions("0.18.0", "0.18"))
        assertTrue(DeviceFit.compareVersions("0.9.9", "0.17.0") < 0)
    }
}

class ModelSwitchTest {
    @Test
    fun `escolher nao aplica ate salvar`() {
        var d = ModelDraft(active = "/m/a.litertlm", pending = "/m/a.litertlm")
        assertEquals(false, d.dirty)
        d = d.select("/m/b.litertlm")
        assertTrue(d.dirty)
        assertEquals("/m/a.litertlm", d.discard().pending)
        assertEquals(false, d.saved().dirty)
        assertEquals("/m/b.litertlm", d.saved().active)
    }

    @Test
    fun `confirmacao usa o que o motor disse`() {
        val r = EngineReport("gemma-4-E4B-it.litertlm", "Gemma 4 E4B", "CPU (GPU recusou)", vision = true, audio = false, nativeTools = true, testReply = "Oi!", loadMillis = 12_340)
        val t = SwitchText.confirmation(r)
        assertTrue(t.startsWith("Troca concluída: agora o Euno usa Gemma 4 E4B (CPU (GPU recusou))"), t)
        assertTrue(t.contains("ferramentas nativas") && t.contains("lê imagens") && !t.contains("ouve áudio"), t)
        assertTrue(t.contains("12,3 s"), t)
        assertTrue(SwitchText.failure("sem memória", "Gemma 4 E2B").contains("Continua ativo: Gemma 4 E2B"))
    }
}

class SharedModelsTest {
    private val catalog = ModelCatalog.bundled()

    @Test
    fun `so arquivos completos do catalogo sao expostos`() {
        val files = listOf(
            "gemma-4-E2B-it.litertlm" to 2_583_000_000L,
            "gemma-4-E4B-it.litertlm.download" to 1_000L,
            "meu-modelo.litertlm" to 10L,
            "embeddinggemma-2-text-270m.litertlm" to 100L, // tamanho exato errado = incompleto
        )
        val list = SharedModels.installed(catalog, files, "gemma-4-E2B-it.litertlm") { null }
        assertEquals(listOf("gemma-4-e2b"), list.map { it.model.id })
        val row = SharedModels.row(list.single())
        assertEquals(SharedModels.COLUMNS.size, row.size)
        assertEquals(1, row[SharedModels.COLUMNS.indexOf(SharedModels.COL_ACTIVE)])
        assertEquals("texto,ferramentas,visao,audio", row[SharedModels.COLUMNS.indexOf(SharedModels.COL_CAPS)])
    }

    @Test
    fun `so o Joca pode ler`() {
        assertTrue(SharedModels.allowed(listOf("com.aistudio.jocafinancas.wxyzkp")))
        assertEquals(false, SharedModels.allowed(listOf("com.outro.app")))
        assertEquals(false, SharedModels.allowed(emptyList()))
    }
}
