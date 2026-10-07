package com.joctaeng.jarvis.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.mind.cloud.CloudConfig
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.mind.local.LocalBackend
import com.joctaeng.jarvis.mind.orchestrator.BrainPreference
import com.joctaeng.jarvis.mind.persona.CharacterCatalog
import com.joctaeng.jarvis.mind.persona.CharacterProfile
import com.joctaeng.jarvis.mind.persona.PersonaMode
import com.joctaeng.jarvis.settings.CloudPreset
import com.joctaeng.jarvis.settings.PlacementMode
import com.joctaeng.jarvis.settings.SecretStore
import com.joctaeng.jarvis.voice.VoiceOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Meu Euno" (item 46 da especificação): tudo que o usuário configura. */
class SettingsActivity : ComponentActivity() {
    private val app get() = JarvisApp.from(this)
    private val settings get() = app.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Meu Euno", style = MaterialTheme.typography.headlineSmall)
                        CharacterSection()
                        BrainSection()
                        PersonalitySection()
                        ScreenSection()
                        VoiceSection()
                        PrivacySection()
                        MemorySection()
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Hint(text: String) =
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().selectable(option == selected) { onSelect(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = { onSelect(option) })
                Text(label(option))
            }
        }
    }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun CharacterSection() {
        var selected by remember { mutableStateOf(settings.characterId) }
        var name by remember { mutableStateOf(settings.characterName) }
        Section("Personagem") {
            Hint("A arte final de cada personagem ainda está em produção; por enquanto ele aparece com a cor dele.")
            CharacterCatalog.all.forEach { profile ->
                CharacterRow(profile, selected == profile.id) {
                    selected = profile.id
                    settings.characterId = profile.id
                    name = ""
                    settings.characterName = ""
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    settings.characterName = it.trim()
                },
                label = { Text("Nome do personagem") },
                placeholder = { Text(CharacterCatalog.byId(selected).defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            var userName by remember { mutableStateOf(settings.userName) }
            OutlinedTextField(
                value = userName,
                onValueChange = {
                    userName = it
                    settings.userName = it.trim().ifEmpty { "Joctã" }
                },
                label = { Text("Como ele deve te chamar") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun CharacterRow(profile: CharacterProfile, selected: Boolean, onClick: () -> Unit) {
        val renderer = remember(profile.id) {
            ComposeCharacterRenderer().apply {
                applyProfile(profile)
                setEmotion(Emotion.HAPPY, 0.6f)
            }
        }
        OutlinedCard(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CharacterView(renderer, Modifier.size(48.dp), animate = selected)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${profile.defaultName} · ${profile.trait}", style = MaterialTheme.typography.titleSmall)
                    Text(profile.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun BrainSection() {
        val scope = rememberCoroutineScope()
        var preference by remember { mutableStateOf(settings.brainPreference) }
        var preset by remember { mutableStateOf(settings.cloudPreset) }
        var baseUrl by remember { mutableStateOf(settings.cloudBaseUrl.ifBlank { settings.cloudPreset.baseUrl }) }
        var model by remember { mutableStateOf(settings.cloudModel) }
        var key by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(app.secrets.has(SecretStore.CLOUD_API_KEY)) }
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var status by remember { mutableStateOf("") }
        var localPath by remember { mutableStateOf(settings.localModelPath) }
        var backend by remember { mutableStateOf(settings.localBackend) }

        fun provider() = OpenAiCompatibleProvider(
            CloudConfig("teste", preset.label, baseUrl, app.secrets.get(SecretStore.CLOUD_API_KEY), model.ifBlank { "-" }, preset.location),
        )

        Section("Cérebro") {
            Text("Preferência", style = MaterialTheme.typography.labelLarge)
            Choice(BrainPreference.entries, preference, {
                when (it) {
                    BrainPreference.ONLINE_FIRST -> "Online primeiro (recomendado)"
                    BrainPreference.AUTO -> "Automático"
                    BrainPreference.LOCAL_FIRST -> "Celular primeiro"
                    BrainPreference.LOCAL_ONLY -> "Só no celular (offline)"
                }
            }) {
                preference = it
                settings.brainPreference = it
            }

            Text("Cérebro online", style = MaterialTheme.typography.labelLarge)
            Choice(CloudPreset.entries, preset, { it.label }) {
                preset = it
                settings.cloudPreset = it
                baseUrl = it.baseUrl
                settings.cloudBaseUrl = it.baseUrl
                model = ""
                settings.cloudModel = ""
                models = emptyList()
                status = ""
            }
            if (preset != CloudPreset.NONE) {
                Hint(preset.help)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        settings.cloudBaseUrl = it.trim()
                    },
                    label = { Text("Endereço (termina em /v1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (keySaved) "Chave de API (salva — digite para trocar)" else "Chave de API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        app.secrets.put(SecretStore.CLOUD_API_KEY, key)
                        keySaved = key.isNotBlank()
                        key = ""
                        status = if (keySaved) "Chave salva com segurança (Android Keystore)." else "Chave removida."
                    }) { Text("Salvar chave") }
                    OutlinedButton(onClick = {
                        status = "Buscando modelos…"
                        scope.launch {
                            status = try {
                                models = provider().listModels()
                                if (models.isEmpty()) "O provedor não listou modelos; digite o nome." else "Toque em um modelo para escolher."
                            } catch (e: Exception) {
                                "Não consegui listar: ${e.message}"
                            }
                        }
                    }) { Text("Buscar modelos") }
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        settings.cloudModel = it.trim()
                    },
                    label = { Text("Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (models.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        models.forEach { m ->
                            Text(
                                m,
                                Modifier.fillMaxWidth().clickable {
                                    model = m
                                    settings.cloudModel = m
                                    models = emptyList()
                                }.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
                OutlinedButton(onClick = {
                    status = "Testando…"
                    scope.launch {
                        val start = System.currentTimeMillis()
                        val text = StringBuilder()
                        var error: String? = null
                        provider().generate(LlmRequest("", listOf(ChatMessage(Role.USER, "Responda apenas: ok")), 16)).collect {
                            when (it) {
                                is LlmChunk.Text -> text.append(it.text)
                                is LlmChunk.Error -> error = it.message
                                else -> Unit
                            }
                        }
                        val ms = System.currentTimeMillis() - start
                        status = error?.let { "Falhou: $it" } ?: "Funcionou em $ms ms: \"${text.toString().trim().take(60)}\""
                    }
                }) { Text("Testar conexão") }
            }
            if (status.isNotEmpty()) Hint(status)

            Text("Cérebro no celular", style = MaterialTheme.typography.labelLarge)
            val localModels = app.modelStore.list()
            if (localModels.isEmpty()) Hint("Nenhum modelo .litertlm importado. Importe no Diagnóstico (PoC 0.3).")
            Choice(listOf("") + localModels.map { it.path }, localPath, { path ->
                if (path.isEmpty()) "Nenhum" else localModels.first { it.path == path }.let { "${it.name} (${it.length() shr 20} MB)" }
            }) {
                localPath = it
                settings.localModelPath = it
            }
            if (localPath.isNotEmpty()) {
                Choice(LocalBackend.entries, backend, { if (it == LocalBackend.GPU) "GPU (mais rápido)" else "CPU (mais compatível)" }) {
                    backend = it
                    settings.localBackend = it
                }
            }
        }
    }

    @Composable
    private fun PersonalitySection() {
        var mode by remember { mutableStateOf(settings.personaMode) }
        Section("Estilo de resposta") {
            Hint("Soma-se ao jeito do personagem escolhido.")
            Choice(PersonaMode.entries, mode, { it.label }) {
                mode = it
                settings.personaMode = it
            }
        }
    }

    @Composable
    private fun ScreenSection() {
        var placement by remember { mutableStateOf(settings.placementMode) }
        var size by remember { mutableFloatStateOf(settings.characterSizeDp.toFloat()) }
        Section("Na tela") {
            Choice(PlacementMode.entries, placement, { it.label }) {
                placement = it
                settings.placementMode = it
            }
            Text("Tamanho: ${size.toInt()} dp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { settings.characterSizeDp = size.toInt() },
                valueRange = 56f..180f,
            )
            Hint("Também dá para redimensionar com dois dedos (pinça) sobre o personagem.")
        }
    }

    @Composable
    private fun VoiceSection() {
        val voice = app.voice
        var refresh by remember { mutableIntStateOf(0) }
        var engine by remember { mutableStateOf(settings.ttsEngine.ifBlank { voice.activeEngine.orEmpty() }) }
        var voiceName by remember { mutableStateOf(settings.ttsVoice) }
        var rate by remember { mutableFloatStateOf(settings.ttsRate) }
        var pitch by remember { mutableFloatStateOf(settings.ttsPitch) }
        var listenOnOpen by remember { mutableStateOf(settings.listenOnOpen) }
        var continuous by remember { mutableStateOf(settings.continuousVoice) }
        var speakReplies by remember { mutableStateOf(settings.speakReplies) }
        LaunchedEffect(refresh) {
            delay(900)
            if (refresh < 2) refresh++
        }
        val engines = remember(refresh) { voice.engines() }
        val voices = remember(refresh, engine) { voice.portugueseVoices() }

        fun restart() {
            voice.restart()
            refresh = 0
        }

        Section("Voz") {
            if (engines.none { it.packageName == VoiceOutput.GOOGLE_TTS }) {
                Hint("Para uma voz bem mais natural, instale \"Serviços de fala do Google\" na Play Store.")
                OutlinedButton(onClick = {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${VoiceOutput.GOOGLE_TTS}")))
                    }
                }) { Text("Abrir na Play Store") }
            }
            Text("Motor de voz", style = MaterialTheme.typography.labelLarge)
            Choice(engines.map { it.packageName }, engine, { pkg -> engines.first { it.packageName == pkg }.label }) {
                engine = it
                settings.ttsEngine = it
                voiceName = ""
                settings.ttsVoice = ""
                restart()
            }
            Text("Voz em português", style = MaterialTheme.typography.labelLarge)
            if (voices.isEmpty()) Hint("Carregando vozes…")
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Choice(listOf("") + voices.map { it.name }, voiceName, { name ->
                    if (name.isEmpty()) "Automática (melhor disponível)" else voices.first { it.name == name }.label
                }) {
                    voiceName = it
                    settings.ttsVoice = it
                    restart()
                }
            }
            Text("Velocidade: ${"%.1f".format(rate)}x", style = MaterialTheme.typography.labelLarge)
            Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = {
                settings.ttsRate = rate
                restart()
            }, valueRange = 0.6f..1.8f)
            Text("Tom: ${"%.1f".format(pitch)}", style = MaterialTheme.typography.labelLarge)
            Slider(value = pitch, onValueChange = { pitch = it }, onValueChangeFinished = {
                settings.ttsPitch = pitch
                restart()
            }, valueRange = 0.6f..1.6f)
            Button(onClick = { voice.speak("Oi, ${settings.userName}! Eu sou ${settings.displayName}. Assim fica bom?") }) { Text("Testar voz") }
            Toggle("Começar ouvindo quando eu tocar nele", listenOnOpen) {
                listenOnOpen = it
                settings.listenOnOpen = it
            }
            Toggle("Conversa contínua (volta a ouvir depois de responder)", continuous) {
                continuous = it
                settings.continuousVoice = it
            }
            Toggle("Responder falando quando eu falar", speakReplies) {
                speakReplies = it
                settings.speakReplies = it
            }
        }
    }

    @Composable
    private fun PrivacySection() {
        var privateOn by remember { mutableStateOf(settings.privateMode) }
        Section("Privacidade") {
            Toggle("Modo Privado", privateOn) {
                privateOn = it
                settings.privateMode = it
            }
            Hint("No Modo Privado só o cérebro do celular é usado e nada é memorizado. Autonomia atual: Assistente — ainda não executo ações em outros apps.")
        }
    }

    @Composable
    private fun MemorySection() {
        var items by remember { mutableStateOf(app.memory.all()) }
        var confirmClear by remember { mutableStateOf(false) }
        val date = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }
        Section("Minha Memória") {
            Hint("O que ${settings.displayName} sabe sobre você. Diga \"lembre que…\" na conversa para acrescentar e \"esqueça isto\" para apagar.")
            if (items.isEmpty()) Hint("Nada memorizado ainda.")
            items.reversed().forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.text)
                        Hint("${date.format(Date(item.createdAtMillis))} · ${item.source} · ${item.reason}")
                    }
                    TextButton(onClick = {
                        app.memory.remove(item.id)
                        items = app.memory.all()
                    }) { Text("Esquecer") }
                }
            }
            if (items.isNotEmpty()) {
                OutlinedButton(onClick = {
                    if (confirmClear) {
                        app.memory.clear()
                        items = emptyList()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                }) { Text(if (confirmClear) "Toque de novo para apagar tudo" else "Apagar todas") }
            }
        }
    }
}
