package com.joctaeng.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.device.SystemSettings
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.mind.local.LocalBackend
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.overlay.OverlayService
import com.joctaeng.jarvis.poc.LlmBenchmark
import com.joctaeng.jarvis.poc.Report
import com.joctaeng.jarvis.poc.SpeechTestPhrases
import com.joctaeng.jarvis.poc.SttProbe
import com.joctaeng.jarvis.poc.TtsProbe
import com.joctaeng.jarvis.poc.WordErrorRate
import kotlinx.coroutines.launch
import java.io.File

/** Diagnóstico (Fase 0): permissões, provas de conceito e relatório de medições. */
class DiagnosticsActivity : ComponentActivity() {

    /** Muda a cada retorno à tela, para reler permissões e medições. */
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize()) { Dashboard(refresh) { refresh++ } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        OverlayBus.dashboardVisible.value = true
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onStop() {
        OverlayBus.dashboardVisible.value = false
        super.onStop()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** [refreshKey] muda ao voltar das configurações, forçando a releitura de permissões e medições. */
    @Composable
    private fun Dashboard(refreshKey: Int, onChanged: () -> Unit) {
        val app = JarvisApp.from(this)
        val report = remember { Report(this, app.diagnostics) }
        val fps by OverlayBus.fps.collectAsState()
        val reaction by OverlayBus.lastReactionMillis.collectAsState()
        val overlayRunning by OverlayBus.running.collectAsState()
        val preview = remember { ComposeCharacterRenderer().apply { play(AnimState.IDLE); setEmotion(Emotion.HAPPY, 0.6f) } }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterView(preview, Modifier.size(72.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Diagnóstico — Fase 0", style = MaterialTheme.typography.headlineSmall)
                    Text("Provas de conceito no aparelho real", style = MaterialTheme.typography.bodyMedium)
                }
            }

            PermissionsCard(refreshKey, onChanged)

            Section("PoC 0.1 — Personagem flutuante", report.overlaySummary()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { OverlayService.start(this@DiagnosticsActivity) },
                        enabled = !overlayRunning && SystemSettings.canDrawOverlays(this@DiagnosticsActivity),
                    ) { Text("Ligar personagem") }
                    OutlinedButton(onClick = { OverlayService.stop(this@DiagnosticsActivity) }, enabled = overlayRunning) { Text("Desligar") }
                }
                Hint("Arraste para mover. Toque para conversar. Toque longo abre o início do app.")
            }

            Section("PoC 0.2 — Toque → microfone e câmera", report.touchSessionSummary()) {
                Hint("Na Fase 1 o toque no personagem abre a conversa. Este botão abre a sessão de teste original (microfone + câmera).")
                OutlinedButton(onClick = {
                    startActivity(Intent(this@DiagnosticsActivity, com.joctaeng.jarvis.session.TouchSessionActivity::class.java))
                }) { Text("Abrir teste de microfone e câmera") }
                ClearButton(Poc.TOUCH_SESSION, onChanged)
            }

            LlmSection(report, refreshKey, onChanged)
            SpeechSection(report, refreshKey, onChanged)

            Section("PoC 0.5 — Renderização", report.rendererSummary(fps, reaction)) {
                Hint("Personagem provisório desenhado em código, limitado a 30 quadros/s. A média é registrada a cada minuto com o personagem ligado.")
            }

            Section("Relatório", "Junta todas as medições em Markdown para colar em docs/benchmarks.md.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val text = report.fullMarkdown(fps, reaction)
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                "Compartilhar resultados",
                            ),
                        )
                    }) { Text("Compartilhar") }
                    OutlinedButton(onClick = {
                        Poc.all.forEach { app.diagnostics.clear(it) }
                        onChanged()
                    }) { Text("Apagar medições") }
                }
            }
        }
    }

    @Composable
    private fun PermissionsCard(refreshKey: Int, onChanged: () -> Unit) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
        val ctx = this
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissões", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Exibir sobre outros apps", SystemSettings.canDrawOverlays(ctx)) { SystemSettings.openOverlayPermission(ctx) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow("Notificações", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                PermissionRow(
                    "Microfone e câmera",
                    granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.CAMERA),
                ) { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) }
                PermissionRow("Bateria sem restrições", SystemSettings.isIgnoringBatteryOptimizations(ctx)) {
                    SystemSettings.requestIgnoreBatteryOptimizations(ctx)
                }
                if (SystemSettings.isXiaomi) {
                    PermissionRow("Xiaomi: Inicialização automática", null) { SystemSettings.openXiaomiAutostart(ctx) }
                    PermissionRow("Xiaomi: Abrir janelas em segundo plano", null) { SystemSettings.openXiaomiOtherPermissions(ctx) }
                    Hint("No HyperOS, ative também \"Sem restrições\" em Economia de bateria do app. Os itens da Xiaomi não podem ser verificados pelo app.")
                }
            }
        }
    }

    @Composable
    private fun LlmSection(report: Report, refreshKey: Int, onChanged: () -> Unit) {
        val app = JarvisApp.from(this)
        val scope = rememberCoroutineScope()
        var models by remember { mutableStateOf(app.modelStore.list()) }
        var selected by remember { mutableStateOf<File?>(models.firstOrNull()) }
        var backend by remember { mutableStateOf(LocalBackend.GPU) }
        var status by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            running = true
            scope.launch {
                status = try {
                    val file = app.modelStore.import(uri) { copied, total ->
                        status = "Copiando: ${copied shr 20} MB" + if (total > 0) " de ${total shr 20} MB" else ""
                    }
                    models = app.modelStore.list()
                    selected = file
                    "Modelo importado: ${file.name}"
                } catch (e: Exception) {
                    "Falha ao importar: ${e.message}"
                }
                running = false
            }
        }

        fun run(sustained: Boolean) {
            val model = selected ?: return
            running = true
            output = ""
            keepScreenOn(true)
            scope.launch {
                val bench = LlmBenchmark(this@DiagnosticsActivity, app.diagnostics)
                val result = if (sustained) {
                    bench.runSustained(model, backend) { status = it }
                } else {
                    bench.runOnce(model, backend) { status = it }
                }
                status = result.error?.let { "Erro: $it" }
                    ?: "Carga ${result.loadMillis} ms · 1º trecho ${result.stats?.timeToFirstChunkMillis} ms · " +
                    "${result.decodeChunksPerSecond?.let { "%.1f".format(it) } ?: "?"} trechos/s · PSS ${result.peakPssMb} MB · ${result.runs} rodada(s)"
                output = result.output
                running = false
                keepScreenOn(false)
                onChanged()
            }
        }

        Section("PoC 0.3 — Cérebro local (LiteRT-LM)", report.llmSummary(), mono = true) {
            Hint(
                "Baixe um modelo .litertlm no computador ou celular (ex.: Gemma3-1B-IT ou Gemma 3n E2B da comunidade LiteRT no Hugging Face) " +
                    "e importe aqui. RAM livre agora: ${DeviceState.memoryInfo(this@DiagnosticsActivity).availMem shr 20} MB.",
            )
            if (models.isEmpty()) Hint("Nenhum modelo em ${app.modelStore.directory.path}")
            models.forEach { file ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected == file) { selected = file },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == file, onClick = { selected = file })
                    Text("${file.name} (${file.length() shr 20} MB)")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalBackend.entries.forEach { b ->
                    RadioButton(selected = backend == b, onClick = { backend = b })
                    Text(b.name)
                    Spacer(Modifier.size(12.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !running) { Text("Importar") }
                Button(onClick = { run(false) }, enabled = !running && selected != null) { Text("Teste rápido") }
                OutlinedButton(onClick = { run(true) }, enabled = !running && selected != null) { Text("5 min") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (output.isNotEmpty()) Text("Resposta: $output", style = MaterialTheme.typography.bodySmall)
            ClearButton(Poc.LOCAL_LLM, onChanged)
        }
    }

    @Composable
    private fun SpeechSection(report: Report, refreshKey: Int, onChanged: () -> Unit) {
        val app = JarvisApp.from(this)
        var index by remember { mutableIntStateOf(app.diagnostics.read(Poc.SPEECH).count { it["kind"] == "stt" } % SpeechTestPhrases.size) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val phrase = SpeechTestPhrases[index]

        Section("PoC 0.4 — Voz offline (STT/TTS)", report.speechSummary()) {
            Hint("Para medir o modo offline de verdade, ative o modo avião. Toque em Ouvir e leia a frase em voz natural.")
            Text("Frase ${index + 1}/${SpeechTestPhrases.size}: \"$phrase\"", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    status = "Ouvindo…"
                    val online = DeviceState.snapshot(this@DiagnosticsActivity).online
                    SttProbe(this@DiagnosticsActivity).listen { result ->
                        val wer = result.text?.let { WordErrorRate.compute(phrase, it) }
                        app.diagnostics.append(
                            Poc.SPEECH, "kind" to "stt", "phrase" to index + 1, "text" to result.text,
                            "wer" to wer, "latency_ms" to result.latencyAfterSpeechMillis,
                            "on_device" to result.onDevice, "online" to online, "error" to result.error,
                        )
                        status = if (wer != null) "Ouvi: \"${result.text}\" · WER ${"%.0f".format(wer * 100)}%" else "Falhou: ${result.error}"
                        index = (index + 1) % SpeechTestPhrases.size
                        busy = false
                        onChanged()
                    }
                }) { Text("Ouvir") }
                OutlinedButton(enabled = !busy, onClick = { index = (index + 1) % SpeechTestPhrases.size }) { Text("Pular") }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    status = "Falando…"
                    TtsProbe(this@DiagnosticsActivity).speak("Oi, Joca! Eu sou o JARVIS e estou testando a minha voz.") { r ->
                        app.diagnostics.append(
                            Poc.SPEECH, "kind" to "tts", "lang" to r.languageStatus, "offline_voices" to r.offlineVoices,
                            "init_ms" to r.initMillis, "start_ms" to r.startLatencyMillis, "engine" to r.engine, "error" to r.error,
                        )
                        status = r.error?.let { "Voz falhou: $it" } ?: "Voz ok: início em ${r.startLatencyMillis} ms"
                        busy = false
                        onChanged()
                    }
                }) { Text("Testar voz") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            ClearButton(Poc.SPEECH, onChanged)
        }
    }

    @Composable
    private fun Section(title: String, summary: String, mono: Boolean = false, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                )
                content()
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, ok: Boolean?, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (when (ok) { true -> "✅ "; false -> "⚠️ "; null -> "• " }) + label,
                Modifier.weight(1f),
            )
            if (ok != true) OutlinedButton(onClick = onClick) { Text("Abrir") }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun ClearButton(poc: String, onChanged: () -> Unit) {
        OutlinedButton(onClick = {
            JarvisApp.from(this).diagnostics.clear(poc)
            onChanged()
        }) { Text("Limpar dados desta PoC") }
    }
}
