package com.joctaeng.jarvis.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.poc.SttProbe
import com.joctaeng.jarvis.poc.TtsProbe
import kotlinx.coroutines.launch

/**
 * PoC 0.2 — o toque no personagem abre esta Activity compacta. Com ela visível, o
 * app está em primeiro plano e pode usar microfone e câmera (no Android 14+, um
 * serviço em segundo plano não pode iniciar uso de câmera/microfone).
 */
class TouchSessionActivity : ComponentActivity() {

    private val renderer = ComposeCharacterRenderer()
    private val lines = mutableStateListOf<String>()
    private var busy by mutableStateOf(true)
    private var tapToResumeMillis: Long? = null
    private var probesStarted = false
    private val diagnostics get() = JarvisApp.from(this).diagnostics

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runProbes() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayBus.sessionActive.value = true
        renderer.applyProfile(JarvisApp.from(this).settings.character)
        renderer.play(AnimState.LISTENING)
        renderer.setEmotion(Emotion.HAPPY, 0.7f)
        setContent { MaterialTheme { SessionCard() } }
    }

    override fun onResume() {
        super.onResume()
        if (tapToResumeMillis == null) {
            val tap = intent.getLongExtra(EXTRA_TAP_ELAPSED, -1L)
            if (tap > 0) tapToResumeMillis = SystemClock.elapsedRealtime() - tap
        }
        if (!probesStarted) {
            probesStarted = true
            val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) runProbes() else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        OverlayBus.sessionActive.value = false
        super.onDestroy()
    }

    private fun runProbes() = lifecycleScope.launch {
        busy = true
        renderer.play(AnimState.THINKING)
        lines += "Toque → tela: ${tapToResumeMillis?.let { "$it ms" } ?: "n/d"}"

        val micGranted = granted(Manifest.permission.RECORD_AUDIO)
        val mic = if (micGranted) MicProbe.run() else MicResult(false, 0, "permissão negada")
        lines += "Microfone: " + when {
            mic.silenced -> "abriu, mas o sistema entregou silêncio"
            mic.ok -> "ok (pico ${mic.peak})"
            else -> "falhou — ${mic.error}"
        }

        val camGranted = granted(Manifest.permission.CAMERA)
        val cam = if (camGranted) CameraProbe.run(this@TouchSessionActivity) else CameraResult(false, error = "permissão negada")
        lines += "Câmera: " + if (cam.ok) "ok (${cam.lens}, ${cam.resolution})" else "falhou — ${cam.error}"

        val success = mic.ok && !mic.silenced && cam.ok
        diagnostics.append(
            Poc.TOUCH_SESSION,
            "event" to "session",
            "success" to success,
            "tap_to_resume_ms" to tapToResumeMillis,
            "dashboard_was_visible" to intent.getBooleanExtra(EXTRA_DASHBOARD_WAS_VISIBLE, false),
            "mic_ok" to mic.ok, "mic_peak" to mic.peak, "mic_error" to mic.error,
            "cam_ok" to cam.ok, "cam_res" to cam.resolution, "cam_error" to cam.error,
        )
        renderer.play(if (success) AnimState.HAPPY else AnimState.CONCERNED)
        renderer.setEmotion(if (success) Emotion.HAPPY else Emotion.CONCERNED, 0.8f)
        busy = false
    }

    private fun testStt() {
        busy = true
        renderer.play(AnimState.LISTENING)
        lines += "Fale uma frase…"
        SttProbe(this).listen { result ->
            lines += if (result.text != null) {
                "Entendi: \"${result.text}\" (${if (result.onDevice) "no aparelho" else "serviço do sistema"}, ${result.latencyAfterSpeechMillis ?: "?"} ms)"
            } else {
                "Reconhecimento falhou: ${result.error}"
            }
            renderer.play(AnimState.IDLE)
            busy = false
        }
    }

    private fun testTts() {
        busy = true
        renderer.play(AnimState.SPEAKING)
        renderer.setMouthOpen(0.6f)
        TtsProbe(this).speak("Oi, Joca! Estou funcionando no seu celular.") { result ->
            lines += if (result.error == null) {
                "Voz: ${result.offlineVoices} voz(es) PT offline, início em ${result.startLatencyMillis ?: "?"} ms"
            } else {
                "Voz falhou: ${result.error}"
            }
            renderer.setMouthOpen(0f)
            renderer.play(AnimState.IDLE)
            busy = false
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @androidx.compose.runtime.Composable
    private fun SessionCard() {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterView(renderer, Modifier.size(72.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            if (busy) "Testando…" else "Oi! Sessão de teste (Fase 0)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::testStt, enabled = !busy) { Text("Ouvir") }
                        OutlinedButton(onClick = ::testTts, enabled = !busy) { Text("Falar") }
                        Button(onClick = ::finish) { Text("Fechar") }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAP_ELAPSED = "tap_elapsed"
        const val EXTRA_DASHBOARD_WAS_VISIBLE = "dashboard_was_visible"
    }
}
