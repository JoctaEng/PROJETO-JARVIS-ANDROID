package com.joctaeng.jarvis.poc

import android.content.Context
import android.os.Debug
import com.joctaeng.jarvis.core.contracts.GenerationStats
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.diagnostics.Diagnostics
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.mind.local.LiteRtLmProvider
import com.joctaeng.jarvis.mind.local.LocalBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

data class BenchmarkResult(
    val model: String,
    val backend: LocalBackend,
    val loadMillis: Long?,
    val stats: GenerationStats?,
    val peakPssMb: Long,
    val output: String,
    val error: String?,
    val runs: Int = 1,
) {
    /** Trechos por segundo após o primeiro — cada trecho do LiteRT-LM ≈ 1 token (a confirmar). */
    val decodeChunksPerSecond: Double?
        get() = stats?.let {
            val decodeMillis = it.totalMillis - it.timeToFirstChunkMillis
            if (decodeMillis <= 0 || it.chunkCount <= 1) null else (it.chunkCount - 1) * 1000.0 / decodeMillis
        }
}

/**
 * PoC 0.3 — mede o cérebro local no aparelho real: tempo de carga, tempo até o
 * primeiro trecho, velocidade, memória de pico (PSS) e temperatura.
 */
class LlmBenchmark(private val context: Context, private val diagnostics: Diagnostics) {

    private val systemPrompt = "Você é o JARVIS, um personagem assistente amigável. Responda sempre em português do Brasil."
    private val prompt = "Em até três frases, explique por que o céu é azul."

    suspend fun runOnce(model: File, backend: LocalBackend, onProgress: (String) -> Unit): BenchmarkResult =
        measure(model, backend, durationMillis = 0, onProgress)

    /** Gera respostas seguidas pelo tempo pedido (padrão 5 min) para observar aquecimento. */
    suspend fun runSustained(model: File, backend: LocalBackend, durationMillis: Long = 5 * 60_000L, onProgress: (String) -> Unit) =
        measure(model, backend, durationMillis, onProgress)

    private suspend fun measure(
        model: File,
        backend: LocalBackend,
        durationMillis: Long,
        onProgress: (String) -> Unit,
    ): BenchmarkResult = coroutineScope {
        val before = DeviceState.memoryInfo(context)
        val thermalBefore = DeviceState.thermalStatus(context)
        val tempBefore = DeviceState.batteryTemperatureC(context)
        val headroomBefore = DeviceState.thermalHeadroom(context)
        var peakPssKb = Debug.getPss()
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                peakPssKb = max(peakPssKb, Debug.getPss())
                delay(500)
            }
        }

        val provider = LiteRtLmProvider(model, backend, context.cacheDir)
        var loadMillis: Long? = null
        var firstStats: GenerationStats? = null
        val output = StringBuilder()
        var error: String? = null
        var runs = 0
        try {
            onProgress("Carregando ${model.name} (${backend.name})…")
            loadMillis = provider.load()
            val request = LlmRequest(systemPrompt, listOf(ChatMessage(Role.USER, prompt)))
            val deadline = System.currentTimeMillis() + durationMillis
            do {
                runs++
                onProgress(if (durationMillis > 0) "Gerando (rodada $runs)…" else "Gerando…")
                provider.generate(request).collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Text -> if (runs == 1) output.append(chunk.text)
                        is LlmChunk.Done -> if (runs == 1) firstStats = chunk.stats
                        is LlmChunk.Error -> error = chunk.message
                        is LlmChunk.ToolRequest -> Unit
                    }
                }
            } while (error == null && System.currentTimeMillis() < deadline)
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        } finally {
            provider.unload()
            sampler.cancel()
        }

        val result = BenchmarkResult(
            model = model.name, backend = backend, loadMillis = loadMillis, stats = firstStats,
            peakPssMb = peakPssKb / 1024, output = output.toString(), error = error, runs = runs,
        )
        diagnostics.append(
            Poc.LOCAL_LLM,
            "mode" to if (durationMillis > 0) "sustained" else "once",
            "model" to model.name,
            "model_mb" to model.length() / (1024 * 1024),
            "backend" to backend.name,
            "load_ms" to loadMillis,
            "ttft_ms" to firstStats?.timeToFirstChunkMillis,
            "total_ms" to firstStats?.totalMillis,
            "chunks" to firstStats?.chunkCount,
            "chars" to firstStats?.charCount,
            "chunks_per_s" to result.decodeChunksPerSecond?.let { "%.1f".format(it) },
            "runs" to runs,
            "peak_pss_mb" to result.peakPssMb,
            "avail_ram_before_mb" to before.availMem / (1024 * 1024),
            "thermal_before" to thermalBefore,
            "thermal_after" to DeviceState.thermalStatus(context),
            "headroom_before" to headroomBefore,
            "headroom_after" to DeviceState.thermalHeadroom(context),
            "battery_temp_before" to tempBefore,
            "battery_temp_after" to DeviceState.batteryTemperatureC(context),
            "error" to error,
        )
        result
    }
}
