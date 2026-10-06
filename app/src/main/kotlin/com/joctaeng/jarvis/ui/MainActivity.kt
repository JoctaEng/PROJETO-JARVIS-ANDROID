package com.joctaeng.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.chat.ChatActivity
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.device.SystemSettings
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.overlay.OverlayService

/** Início: o personagem escolhido, ligar/desligar, conversar e configurar. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisTheme { Surface(Modifier.fillMaxSize()) { Home(refresh) } } }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    @Composable
    private fun Home(refreshKey: Int) {
        val app = JarvisApp.from(this)
        val running by OverlayBus.running.collectAsState()
        val version by app.settings.version.collectAsState()
        val profile = app.settings.character
        val renderer = remember { ComposeCharacterRenderer().apply { play(AnimState.IDLE) } }
        renderer.applyColor(profile.color)
        renderer.setEmotion(Emotion.HAPPY, 0.6f)
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
        val canOverlay = refreshKey >= 0 && SystemSettings.canDrawOverlays(this)
        val brains = remember(refreshKey, version) { app.conversation.configuredProviders().map { it.displayName } }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CharacterView(renderer, Modifier.size(150.dp))
            Text("Euno · Seu segundo eu digital", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(app.settings.displayName, style = MaterialTheme.typography.headlineMedium)
            Text(profile.trait, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                profile.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cérebro", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (brains.isEmpty()) "Nenhum configurado. Abra Meu Euno → Cérebro." else brains.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (app.settings.privateMode) Text("Modo Privado ativo", color = MaterialTheme.colorScheme.secondary)
                }
            }

            if (!canOverlay) {
                Text("Para ${app.settings.displayName} flutuar sobre os apps, permita \"Exibir sobre outros apps\".", textAlign = TextAlign.Center)
                Button(onClick = { SystemSettings.openOverlayPermission(this@MainActivity) }) { Text("Permitir") }
            }
            if (running) {
                OutlinedButton(onClick = { OverlayService.stop(this@MainActivity) }, Modifier.fillMaxWidth()) { Text("Esconder personagem") }
            } else {
                Button(
                    onClick = {
                        val missing = listOf(Manifest.permission.RECORD_AUDIO) +
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
                        val needed = missing.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
                        OverlayService.start(this@MainActivity)
                    },
                    enabled = canOverlay,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mostrar personagem na tela") }
            }
            FilledTonalButton(
                onClick = { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Conversar") }
            OutlinedButton(
                onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Meu Euno") }
            OutlinedButton(
                onClick = { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Diagnóstico (Fase 0)") }
        }
    }
}
