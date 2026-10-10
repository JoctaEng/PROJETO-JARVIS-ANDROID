package com.joctaeng.jarvis.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.settings.SecretStore
import com.joctaeng.jarvis.voice.AzureSpeech

/**
 * Escolha da voz do Azure, sempre visível quando há chave: a lista vem do próprio Azure (ao abrir a tela) e, se não
 * der para buscar, usa a lista conhecida de vozes brasileiras. Cada voz tem "Ouvir".
 */
@Composable
fun AzureVoicePicker(app: JarvisApp) {
    val settings = app.settings
    val hasKey = app.secrets.has(SecretStore.AZURE_SPEECH_KEY)
    var voices by remember { mutableStateOf(KNOWN) }
    var chosen by remember { mutableStateOf(settings.azureVoice) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(hasKey) {
        if (!hasKey) return@LaunchedEffect
        val key = app.secrets.get(SecretStore.AZURE_SPEECH_KEY) ?: return@LaunchedEffect
        runCatching { AzureSpeech(key, settings.azureRegion).voices() }
            .onSuccess { list -> if (list.isNotEmpty()) voices = list.map { it.shortName to (it.gender == "Female") }.sortedBy { it.first } }
            .onFailure { note = "Não consegui buscar a lista no Azure agora; mostrando as vozes brasileiras conhecidas." }
    }
    if (!hasKey) {
        Text("Vozes do Azure: cadastre a chave em Configurar IA e voz para escolher.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Voz do Azure", style = MaterialTheme.typography.labelLarge)
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        (listOf("" to false) + voices).forEach { (name, female) ->
            Row(
                Modifier.fillMaxWidth().selectable(chosen == name) {
                    chosen = name
                    settings.azureVoice = name
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == name, onClick = {
                    chosen = name
                    settings.azureVoice = name
                })
                Text(
                    if (name.isEmpty()) "Padrão do personagem (Francisca ou Antonio)"
                    else "${name.removePrefix("pt-BR-").removeSuffix("Neural")} (${if (female) "feminina" else "masculina"})",
                    Modifier.weight(1f),
                )
                if (name.isNotEmpty()) TextButton(onClick = {
                    chosen = name
                    settings.azureVoice = name
                    app.voice.resetAzureCooldown()
                    app.voice.stop()
                    app.voice.speak("Oi, ${settings.userName}! Esta é a voz ${name.removePrefix("pt-BR-").removeSuffix("Neural")}.")
                }) { Text("Ouvir") }
            }
        }
    }
}

/** Vozes neurais pt-BR do Azure (nome, feminina?). Usada se a lista online não vier. */
private val KNOWN = listOf(
    "pt-BR-AntonioNeural" to false, "pt-BR-BrendaNeural" to true, "pt-BR-DonatoNeural" to false,
    "pt-BR-ElzaNeural" to true, "pt-BR-FabioNeural" to false, "pt-BR-FranciscaNeural" to true,
    "pt-BR-GiovannaNeural" to true, "pt-BR-HumbertoNeural" to false, "pt-BR-JulioNeural" to false,
    "pt-BR-LeilaNeural" to true, "pt-BR-LeticiaNeural" to true, "pt-BR-ManuelaNeural" to true,
    "pt-BR-NicolauNeural" to false, "pt-BR-ThalitaNeural" to true, "pt-BR-ValerioNeural" to false,
    "pt-BR-YaraNeural" to true,
)
