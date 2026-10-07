package com.joctaeng.jarvis.mind.llama

import java.io.File

/** Ponte JNI com libeuno_llama.so (llama.cpp, arm64-v8a); implementação em src/main/cpp. */
internal object LlamaNative {
    /** null = motor pronto; senão, o motivo de não poder usar. */
    val loadError: String? = when {
        !cpuHas("asimddp") -> "o processador não tem a instrução dotprod exigida pelo motor"
        else -> runCatching { System.loadLibrary("euno_llama") }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }

    /** Sem /proc/cpuinfo legível, assume que tem (o carregamento da biblioteca acusa se não tiver). */
    private fun cpuHas(feature: String): Boolean = runCatching {
        val lines = File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
        lines.isEmpty() || lines.any { line -> line.substringAfter(':').trim().split(Regex("\\s+")).contains(feature) }
    }.getOrDefault(true)

    fun interface TokenCallback {
        /** Devolver false interrompe a geração. */
        fun onToken(piece: String): Boolean
    }

    /** 0 = ok; -1 modelo; -2 contexto. */
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, threads: Int, threadsBatch: Int, nBatch: Int): Int
    @JvmStatic external fun nativeUnload()
    @JvmStatic external fun nativeLoaded(): Boolean
    @JvmStatic external fun nativeSetThreads(threads: Int, threadsBatch: Int)
    @JvmStatic external fun nativeResetCache()

    /** [0] tokens do prompt, [1] reaproveitados do cache, [2] leitura ms, [3] 1º token ms, [4] gerados, [5] geração ms. */
    @JvmStatic external fun nativeMetrics(): LongArray
    @JvmStatic external fun nativeWarmup(roles: Array<String>, contents: Array<String>): Long

    /** 0 = fim do texto, 1 = limite de tokens, 2 = cancelado, negativo = erro. */
    @JvmStatic external fun nativeGenerate(
        roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float,
        noThinking: Boolean, callback: TokenCallback,
    ): Int

    @JvmStatic external fun nativeSystemInfo(): String
}
