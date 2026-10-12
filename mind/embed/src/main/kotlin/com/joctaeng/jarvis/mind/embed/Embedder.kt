package com.joctaeng.jarvis.mind.embed

/** Para que serve o texto: pergunta (consulta) ou algo guardado (documento). O modelo usa um prefixo diferente para cada um. */
enum class EmbedRole { QUERY, DOCUMENT }

/**
 * Transforma texto em vetor numérico (significado). O app implementa com o EmbeddingGemma 2 no LiteRT-LM;
 * os testes usam um falso determinístico. Todos os vetores de um mesmo [modelId] têm [dims] posições e norma 1.
 */
interface TextEmbedder {
    val modelId: String
    val dims: Int
    suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray>
}

/**
 * Prefixos de tarefa do EmbeddingGemma 2 para busca (ficha oficial google/embeddinggemma-2, seção "Task Instruction Prefixes",
 * linha "Web / document search"): consulta = `task: search result | query: {texto}`;
 * documento = `title: {título} | text: {texto}`, com `title: none` quando não há título. Memórias não têm título.
 * Se algum dia o esquema mudar, suba [VERSION]: o índice antigo é descartado e refeito.
 */
object EmbedPrompts {
    const val VERSION = 1

    fun format(text: String, role: EmbedRole): String = when (role) {
        EmbedRole.QUERY -> "task: search result | query: $text"
        EmbedRole.DOCUMENT -> "title: none | text: $text"
    }
}
