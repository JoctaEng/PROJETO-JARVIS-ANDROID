package com.joctaeng.jarvis.poc

import android.content.Context
import android.os.Build
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.diagnostics.Diagnostics
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.diagnostics.Record
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resumos das medições, prontos para colar em docs/benchmarks.md. */
class Report(private val context: Context, private val diagnostics: Diagnostics) {

    fun overlaySummary(): String {
        val records = diagnostics.read(Poc.OVERLAY)
        if (records.isEmpty()) return "Sem dados ainda. Ligue o personagem e use o celular normalmente por 24 h."
        val starts = records.filter { it["event"] == "start" }
        val stickyRestarts = starts.count { it["reason"] == "sticky-restart" }
        // Um início sem "stop" na execução anterior = o sistema matou o serviço.
        val runs = records.groupBy { it["run"] }
        val unexpectedDeaths = starts.zipWithNext().count { (previous, _) ->
            runs[previous["run"]].orEmpty().none { it["event"] == "stop" }
        }
        val longestRunHours = runs.values.maxOfOrNull { run ->
            (run.last().ts() - run.first().ts()) / 3_600_000.0
        } ?: 0.0
        val current = runs[starts.last()["run"]].orEmpty()
        val drain = batteryDrainPerHour(current)
        return buildString {
            appendLine("- Inícios: ${starts.size} (recriações automáticas pelo sistema: $stickyRestarts)")
            appendLine("- Mortes inesperadas detectadas: $unexpectedDeaths")
            appendLine("- Maior tempo contínuo vivo: ${"%.1f".format(longestRunHours)} h (meta: 24 h)")
            append("- Consumo de bateria do aparelho durante a execução atual: ${drain ?: "n/d"} (inclui todo o uso do celular)")
        }
    }

    fun touchSessionSummary(): String {
        val sessions = diagnostics.read(Poc.TOUCH_SESSION)
        val ok = sessions.filter { it["event"] == "session" }
        val fromOtherApps = ok.filter { it["dashboard_was_visible"] == "false" }
        val failuresToOpen = sessions.count { it["event"] == "launch_timeout" || it["event"] == "launch_failed" }
        val attempts = fromOtherApps.size + failuresToOpen
        val successes = fromOtherApps.count { it["success"] == "true" }
        if (attempts == 0) return "Sem dados ainda. Abra outro app e toque no personagem 20 vezes."
        val latencies = ok.mapNotNull { it["tap_to_resume_ms"]?.toLongOrNull() }.sorted()
        return buildString {
            appendLine("- Tentativas com outro app em primeiro plano: $attempts (meta: 20)")
            appendLine("- Sucesso (mic + câmera): $successes de $attempts = ${percent(successes, attempts)} (meta: ≥ 95%)")
            appendLine("- Sessão não abriu (bloqueio/timeout): $failuresToOpen")
            append("- Toque → tela (mediana): ${latencies.median()?.let { "$it ms" } ?: "n/d"}")
        }
    }

    fun llmSummary(): String {
        val rows = diagnostics.read(Poc.LOCAL_LLM)
        if (rows.isEmpty()) return "Sem dados ainda. Importe um modelo .litertlm e rode o teste."
        return buildString {
            appendLine("| Modelo | Backend | Modo | Carga (ms) | 1º trecho (ms) | Trechos/s | PSS pico (MB) | Temp. bateria (°C) | Erro |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            rows.forEach { r ->
                appendLine(
                    "| ${r["model"]} | ${r["backend"]} | ${r["mode"]} (${r["runs"]}x) | ${r["load_ms"]} | ${r["ttft_ms"]} | " +
                        "${r["chunks_per_s"]} | ${r["peak_pss_mb"]} | ${r["battery_temp_before"]} → ${r["battery_temp_after"]} | ${r["error"].orEmpty()} |",
                )
            }
        }.trimEnd()
    }

    fun speechSummary(): String {
        val rows = diagnostics.read(Poc.SPEECH)
        val stt = rows.filter { it["kind"] == "stt" }
        val tts = rows.filter { it["kind"] == "tts" }
        if (stt.isEmpty() && tts.isEmpty()) return "Sem dados ainda. Leia as 20 frases de teste."
        val recognized = stt.filter { it["wer"] != null && it["wer"] != "" }
        val avgWer = recognized.mapNotNull { it["wer"]?.toDoubleOrNull() }.average().takeIf { !it.isNaN() }
        val latencies = stt.mapNotNull { it["latency_ms"]?.toLongOrNull() }.sorted()
        return buildString {
            appendLine("- Frases testadas: ${stt.size} (meta: 20); reconhecidas: ${recognized.size}")
            appendLine("- WER médio: ${avgWer?.let { "%.1f%%".format(it * 100) } ?: "n/d"}")
            appendLine("- Latência fim da fala → texto (mediana): ${latencies.median()?.let { "$it ms" } ?: "n/d"}")
            appendLine("- Reconhecedor no aparelho: ${stt.lastOrNull()?.get("on_device") ?: "n/d"}; online durante o teste: ${stt.lastOrNull()?.get("online") ?: "n/d"}")
            append(
                tts.lastOrNull()?.let {
                    "- TTS: pt-BR ${it["lang"]}, ${it["offline_voices"]} voz(es) offline, início da fala ${it["start_ms"]} ms, motor ${it["engine"]}"
                } ?: "- TTS: não testado",
            )
        }
    }

    fun rendererSummary(fpsNow: Int, reactionMs: Long?): String {
        val fpsAverages = diagnostics.read(Poc.RENDERER).mapNotNull { it["fps"]?.replace(',', '.')?.toDoubleOrNull() }
        val reactions = diagnostics.read(Poc.RENDERER).mapNotNull { it["reaction_ms"]?.toLongOrNull() }.sorted()
        return buildString {
            appendLine("- FPS agora: $fpsNow; média registrada: ${fpsAverages.average().takeIf { !it.isNaN() }?.let { "%.1f".format(it) } ?: "n/d"} (meta: ≤ 30)")
            append("- Reação ao toque (mediana): ${reactions.median()?.let { "$it ms" } ?: reactionMs?.let { "$it ms" } ?: "n/d"} (meta: < 100 ms)")
        }
    }

    fun fullMarkdown(fpsNow: Int, reactionMs: Long?): String {
        val memory = DeviceState.memoryInfo(context)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """
            |# Resultados da Fase 0 — $date
            |
            |Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · RAM ${memory.totalMem / (1024 * 1024)} MB · SoC ${Build.SOC_MODEL}
            |
            |## PoC 0.1 — Overlay persistente
            |${overlaySummary()}
            |
            |## PoC 0.2 — Toque → câmera/microfone
            |${touchSessionSummary()}
            |
            |## PoC 0.3 — LLM local
            |${llmSummary()}
            |
            |## PoC 0.4 — Voz (STT/TTS)
            |${speechSummary()}
            |
            |## PoC 0.5 — Renderização do personagem
            |${rendererSummary(fpsNow, reactionMs)}
        """.trimMargin()
    }

    private fun batteryDrainPerHour(run: List<Record>): String? {
        val withBattery = run.filter { it["battery"]?.toIntOrNull() != null }
        if (withBattery.size < 2) return null
        val hours = (withBattery.last().ts() - withBattery.first().ts()) / 3_600_000.0
        if (hours < 0.25) return null
        val drop = withBattery.first()["battery"]!!.toInt() - withBattery.last()["battery"]!!.toInt()
        return "%.1f%%/h em %.1f h".format(drop / hours, hours)
    }

    private fun Record.ts(): Long = this["ts"]?.toLongOrNull() ?: 0L
    private fun percent(part: Int, total: Int) = if (total == 0) "n/d" else "%.0f%%".format(part * 100.0 / total)
    private fun List<Long>.median(): Long? = if (isEmpty()) null else this[size / 2]
}
