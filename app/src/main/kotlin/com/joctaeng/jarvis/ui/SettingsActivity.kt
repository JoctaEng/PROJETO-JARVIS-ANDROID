package com.joctaeng.jarvis.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.joctaeng.jarvis.mind.persona.Gender
import com.joctaeng.jarvis.voice.KokoroVoice
import com.joctaeng.jarvis.voice.GeminiSpeech
import com.joctaeng.jarvis.settings.VoiceEngine
import androidx.compose.runtime.collectAsState
import com.joctaeng.jarvis.tools.McpServerInfo
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.CharacterArt
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.mind.cloud.CloudConfig
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.mind.local.LocalBackend
import com.joctaeng.jarvis.mind.orchestrator.BrainPreference
import com.joctaeng.jarvis.mind.persona.CharacterCatalog
import com.joctaeng.jarvis.mind.persona.CharacterProfile
import com.joctaeng.jarvis.mind.persona.PersonaMode
import com.joctaeng.jarvis.poc.ModelDownload
import com.joctaeng.jarvis.poc.ModelStore
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
/** Limite do dossiê "Sobre Mim" (vai no prompt a cada conversa). */
private const val MAX_BIO_CHARS = 4000

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
                        var refresh by remember { mutableIntStateOf(0) }
                        FullTestSection { refresh++ }
                        androidx.compose.runtime.key(refresh) { ConversationSection() }
                        SummariesSection()
                        PhoneControlSection()
                        CharacterSection()
                        ProfileSection()
                        BrainSection()
                        PersonalitySection()
                        ScreenSection()
                        VoiceSection()
                        ActionsSection()
                        HistorySection()
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

    /** Uma chave da área de teste: ligada à configuração real (sem estado duplicado). */
    private class TestSwitch(val key: String, val label: String, val hint: String, val get: () -> Boolean, val set: (Boolean) -> Unit)

    private fun testSwitches() = listOf(
        TestSwitch("caption", "Legenda (sem abrir o chat)", "Melhor testada com: Começar ouvindo e Conversa contínua.",
            { settings.captionMode }, { settings.captionMode = it }),
        TestSwitch("listen", "Começar ouvindo ao tocar", "Melhor testada com: Conversa contínua e Responder falando.",
            { settings.listenOnOpen }, { settings.listenOnOpen = it }),
        TestSwitch("continuous", "Conversa contínua", "Melhor testada com: Começar ouvindo, Responder falando e Ouvir comandos enquanto fala.",
            { settings.continuousVoice }, { settings.continuousVoice = it }),
        TestSwitch("speak", "Responder falando", "Melhor testada com: Conversa contínua.",
            { settings.speakReplies }, { settings.speakReplies = it }),
        TestSwitch("barge", "Ouvir comandos enquanto ele fala", "Melhor testada com: Conversa contínua e Responder falando; com fone de ouvido funciona melhor (sem fone a voz dele pode ser ouvida).",
            { settings.bargeIn }, { settings.bargeIn = it }),
        TestSwitch("beep", "Silenciar o bip do microfone", "Melhor testada com: Conversa contínua e Oi ${settings.wakeName} (que reabre a escuta muitas vezes).",
            { settings.muteMicBeep }, { settings.muteMicBeep = it }),
        TestSwitch("wake", "Chamar pelo nome (Oi ${settings.wakeName})", "Melhor testada com: Silenciar o bip e Legenda. Precisa do microfone e do personagem na tela; solta o microfone quando a conversa abre.",
            { settings.wakeWord }, { settings.wakeWord = it }),
        TestSwitch("phone", "Controle do celular (acessibilidade)", "Melhor testada com: Conversa contínua e Responder falando (peça \"toca em pesquisar\" falando). Depois de ligar aqui, ative \"Euno - controle do celular\" nas configurações de acessibilidade do Android.",
            { settings.phoneControl }, { settings.phoneControl = it }),
        TestSwitch("report", "Incluir a conversa no relatório", "Melhor testada com: qualquer teste; o relatório passa a mostrar o que foi dito.",
            { settings.reportIncludeChat }, { settings.reportIncludeChat = it }),
    )

    private fun granted(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Botão "Habilitar tudo para teste completo" + chaves individuais com dica de quais testar juntas. */
    @Composable
    private fun FullTestSection(onChanged: () -> Unit) {
        var tick by remember { mutableIntStateOf(0) }
        val switches = remember(tick) { testSwitches() }
        val pending = remember(tick) {
            buildList {
                if (!granted(android.Manifest.permission.RECORD_AUDIO)) add("microfone")
                if (!granted(android.Manifest.permission.READ_CALENDAR)) add("agenda")
                if (!granted(android.Manifest.permission.READ_CONTACTS)) add("contatos")
                if (!com.joctaeng.jarvis.device.SystemSettings.isIgnoringBatteryOptimizations(this@SettingsActivity)) add("bateria sem restrições")
                if (!getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted) add("acesso a Não perturbe (para silenciar o bip)")
                if (!com.joctaeng.jarvis.overlay.OverlayBus.running.value) add("personagem na tela (ligue em Meu Euno)")
                if (settings.phoneControl && com.joctaeng.jarvis.control.EunoAccessibilityService.isDeclared(this@SettingsActivity) &&
                    !com.joctaeng.jarvis.control.EunoAccessibilityService.isEnabled(this@SettingsActivity)) add("serviço de acessibilidade \"Euno - controle do celular\"")
            }
        }
        fun applied() {
            tick++
            onChanged()
            if (com.joctaeng.jarvis.overlay.OverlayBus.running.value) com.joctaeng.jarvis.overlay.OverlayService.start(this@SettingsActivity)
        }
        LaunchedEffect(Unit) {
            // Volta para esta tela depois de dar uma permissão: atualiza o que ainda falta.
            while (true) {
                delay(2_000)
                tick++
            }
        }
        Section("Teste completo") {
            Hint("Um toque liga tudo o que é preciso para testar e mostra o que só você pode liberar (permissões e bateria).")
            Button(onClick = {
                if (settings.testSnapshot.isBlank()) {
                    settings.testSnapshot = switches.joinToString(",") { "${it.key}=${if (it.get()) 1 else 0}" }
                }
                switches.forEach { it.set(true) }
                app.events.info("ajustes", "habilitar tudo para teste completo")
                val ask = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.READ_CONTACTS)
                    .filterNot(::granted)
                if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 11)
                applied()
            }, modifier = Modifier.fillMaxWidth()) { Text("Habilitar tudo para teste completo") }
            if (settings.testSnapshot.isNotBlank()) {
                OutlinedButton(onClick = {
                    val saved = settings.testSnapshot.split(',').mapNotNull { it.split('=').takeIf { p -> p.size == 2 }?.let { p -> p[0] to (p[1] == "1") } }.toMap()
                    switches.forEach { sw -> saved[sw.key]?.let(sw.set) }
                    settings.testSnapshot = ""
                    app.events.info("ajustes", "restaurado o que estava antes do teste completo")
                    applied()
                }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar como estava antes") }
            }
            if (pending.isEmpty()) {
                Hint("Tudo liberado: microfone, agenda, contatos, bateria e personagem.")
            } else {
                Text("Falta você liberar: ${pending.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (pending.any { it.startsWith("serviço de acessibilidade") }) {
                    TextButton(onClick = {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Abrir acessibilidade do Android") }
                }
                if (pending.any { it.startsWith("acesso a Não perturbe") }) {
                    TextButton(onClick = {
                        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }) { Text("Dar acesso a Não perturbe") }
                }
                if ("bateria sem restrições" in pending) {
                    TextButton(onClick = { com.joctaeng.jarvis.device.SystemSettings.requestIgnoreBatteryOptimizations(this@SettingsActivity) }) { Text("Liberar bateria") }
                }
            }
            switches.forEach { sw ->
                Toggle(sw.label, sw.get()) {
                    sw.set(it)
                    if (sw.key == "wake" && it && !granted(android.Manifest.permission.RECORD_AUDIO)) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
                    }
                    applied()
                }
                Hint(sw.hint)
            }
        }
    }

    @Composable
    private fun ConversationSection() {
        var listenOnOpen by remember { mutableStateOf(settings.listenOnOpen) }
        var continuous by remember { mutableStateOf(settings.continuousVoice) }
        var speakReplies by remember { mutableStateOf(settings.speakReplies) }
        var patience by remember { mutableFloatStateOf(settings.listenPatienceMs / 1000f) }
        var barge by remember { mutableStateOf(settings.bargeIn) }
        var caption by remember { mutableStateOf(settings.captionMode) }
        var wake by remember { mutableStateOf(settings.wakeWord) }
        var wakeName by remember { mutableStateOf(settings.wakeName) }
        var muteBeep by remember { mutableStateOf(settings.muteMicBeep) }
        var reportChat by remember { mutableStateOf(settings.reportIncludeChat) }
        Section("Conversa") {
            Text("Paciência: espera ${"%.1f".format(patience)} s de silêncio antes de entender que terminei", style = MaterialTheme.typography.labelLarge)
            Slider(value = patience, onValueChange = { patience = it }, onValueChangeFinished = {
                settings.listenPatienceMs = (patience * 1000).toInt()
            }, valueRange = 1.2f..6f)
            Hint("Se eu parar no meio para pensar e ele já responder, aumente. Frases que terminam em \"e\", \"mas\", \"porque\" ganham mais tempo sozinhas.")
            Toggle("Chamar pelo nome (\"Oi $wakeName\")", wake) {
                wake = it
                settings.wakeWord = it
                if (it && androidx.core.content.ContextCompat.checkSelfPermission(this@SettingsActivity, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
                }
                // Reinicia o serviço do personagem para aplicar (precisa estar com ele ligado).
                if (com.joctaeng.jarvis.overlay.OverlayBus.running.value) com.joctaeng.jarvis.overlay.OverlayService.start(this@SettingsActivity)
            }
            if (wake) {
                OutlinedTextField(
                    value = wakeName, onValueChange = { wakeName = it; settings.wakeName = it.trim().ifBlank { "Joca" } },
                    label = { Text("Nome que me chama") }, singleLine = true,
                )
            }
            Hint("Experimental. O microfone fica atento com a tela ligada (gasta bateria) e qualquer voz que diga o nome me chama; ainda não reconheço quem fala. Se o Android negar o microfone em segundo plano, o relatório avisa.")
            Toggle("Conversar só com legenda (sem abrir o chat)", caption) {
                caption = it
                settings.captionMode = it
            }
            Hint("Ao tocar nele, aparece só um balão com o que ele fala; o app que você usa continua clicável. O botão \"Expandir\" abre o chat completo.")
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
            Toggle("Ouvir comandos enquanto ele fala (teste)", barge) {
                barge = it
                settings.bargeIn = it
            }
            Hint("Com isso ligado, \"pera aí\", \"espera\", \"para\" ou \"tchau\" funcionam enquanto ele fala. Com fone de ouvido ele também recebe frases inteiras e as põe na fila.")
            Toggle("Silenciar o \"bip\" do microfone", muteBeep) {
                muteBeep = it
                settings.muteMicBeep = it
            }
            Hint("Abaixa por instantes os sons de sistema/notificação ao começar a ouvir. Não mexe no volume da música.")
            Toggle("Incluir o texto da conversa no relatório de erros", reportChat) {
                reportChat = it
                settings.reportIncludeChat = it
            }
            Hint("Desligado por padrão: o relatório só leva eventos e tempos. Ligado, leva também o que foi dito na conversa atual, para eu entender o erro.")
        }
    }

    @Composable
    private fun SummariesSection() {
        var items by remember { mutableStateOf(app.summaries.all()) }
        var editing by remember { mutableStateOf<com.joctaeng.jarvis.mind.memory.ConversationSummary?>(null) }
        val date = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }
        Section("Resumos de conversa") {
            Hint("Ao tocar em \"Nova\" na conversa, posso guardar um resumo. Os marcados podem ser levados para uma conversa nova (você escolhe na hora). Resumos ocupam bem menos que a conversa inteira.")
            if (items.isEmpty()) Hint("Nenhum resumo ainda.")
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.labelLarge)
                            Hint("${date.format(Date(item.createdAtMillis))} · ${item.text.length} caracteres")
                        }
                        Switch(checked = item.useInNewChats, onCheckedChange = {
                            app.summaries.update(item.id, item.title, item.text, it)
                            items = app.summaries.all()
                        })
                    }
                    Text(item.text.take(240) + if (item.text.length > 240) "…" else "", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { editing = item }) { Text("Editar") }
                        TextButton(onClick = {
                            app.summaries.remove(item.id)
                            items = app.summaries.all()
                        }) { Text("Apagar") }
                    }
                }
            }
        }
        editing?.let { item ->
            var title by remember(item.id) { mutableStateOf(item.title) }
            var text by remember(item.id) { mutableStateOf(item.text) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Editar resumo") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true)
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Resumo") }, minLines = 4, maxLines = 10)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        app.summaries.update(item.id, title.trim().ifBlank { item.title }, text.trim(), item.useInNewChats)
                        items = app.summaries.all()
                        editing = null
                    }) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
            )
        }
    }

    @Composable
    private fun PhoneControlSection() {
        var on by remember { mutableStateOf(settings.phoneControl) }
        var enabled by remember { mutableStateOf(com.joctaeng.jarvis.control.EunoAccessibilityService.isEnabled(this)) }
        LaunchedEffect(Unit) {
            while (true) {
                enabled = com.joctaeng.jarvis.control.EunoAccessibilityService.isEnabled(this@SettingsActivity)
                delay(1_500)
            }
        }
        Section("Controle do celular") {
            Hint("Deixa ${settings.displayName} ler a tela e tocar, digitar, rolar e navegar quando você pedir (por exemplo: \"toca em pesquisar\", \"o que está na minha tela?\", \"volta\"). Senhas nunca são lidas e botões como enviar, pagar e apagar pedem a sua confirmação. Nada acontece sem você pedir.")
            Toggle("Permitir o controle do celular", on) {
                on = it
                settings.phoneControl = it
            }
            Text(
                if (enabled) "Serviço de acessibilidade: LIGADO" else "Serviço de acessibilidade: desligado no Android",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!com.joctaeng.jarvis.control.EunoAccessibilityService.isDeclared(this@SettingsActivity)) {
                Text("Este APK de teste (0.13.1) não inclui o serviço de acessibilidade. Ele existe só para descobrir por que a 0.13.0 não instalou.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else if (!enabled) {
                Button(onClick = { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Ligar em Acessibilidade") }
                Hint("Na tela que abrir, procure \"Euno - controle do celular\" (pode estar em Apps instalados/Serviços) e ative. O Android mostra um aviso: é normal.")
            }
        }
    }

    @Composable
    private fun CharacterSection() {
        var selected by remember { mutableStateOf(settings.character.id) }
        var name by remember { mutableStateOf(settings.characterName) }
        Section("Personagem") {
            val (ready, coming) = CharacterCatalog.all.partition { CharacterArt.hasArt(it.id) }
            ready.forEach { profile ->
                CharacterRow(profile, selected == profile.id) {
                    selected = profile.id
                    settings.characterId = profile.id
                    name = ""
                    settings.characterName = ""
                }
            }
            if (coming.isNotEmpty()) {
                Hint("Arte em produção: " + coming.joinToString { it.defaultName } + ". Cada um aparece aqui assim que a arte chegar.")
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
                    label = { Text(if (preset.baseUrl.isNotEmpty()) "Endereço do serviço (já preenchido)" else "Endereço (termina em /v1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset.location == ProviderLocation.EXTERNAL_CLOUD && preset.baseUrl.isNotEmpty() && baseUrl.trim() != preset.baseUrl) {
                    Hint("Este endereço foi alterado. O padrão do ${preset.label} é ${preset.baseUrl}")
                    TextButton(onClick = {
                        baseUrl = preset.baseUrl
                        settings.cloudBaseUrl = preset.baseUrl
                    }) { Text("Restaurar endereço padrão") }
                }
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
                        app.secrets.put(SecretStore.CLOUD_API_KEY, key.trim())
                        keySaved = key.isNotBlank()
                        key = ""
                        status = if (keySaved) "Chave salva com segurança (Android Keystore)." else "Chave removida."
                    }) { Text("Salvar chave") }
                    OutlinedButton(onClick = {
                        status = "Buscando modelos…"
                        scope.launch {
                            status = try {
                                models = provider().listModels().sortedByDescending { "flash" in it }
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
                    isError = !looksLikeModelId(model),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!looksLikeModelId(model)) {
                    Hint("Use o nome técnico do modelo, sem espaços (ex.: gemini-2.5-flash). Toque em Buscar modelos e escolha da lista.")
                }
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

            LocalBrain()
        }
    }

    @Composable
    private fun LocalBrain() {
        val scope = rememberCoroutineScope()
        var localPath by remember { mutableStateOf(settings.localModelPath) }
        var backend by remember { mutableStateOf(settings.localBackend) }
        var localModels by remember { mutableStateOf(app.modelStore.list()) }
        var download by remember { mutableStateOf(app.modelDownload.current()) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        fun choose(path: String) {
            localPath = path
            settings.localModelPath = path
        }

        fun refresh() {
            localModels = app.modelStore.list()
            if (localPath.isNotEmpty() && localModels.none { it.path == localPath }) choose("")
            if (localPath.isEmpty()) localModels.firstOrNull { ModelStore.isGguf(it) }?.let { choose(it.path) }
        }

        LaunchedEffect(download is ModelDownload.State.Running) {
            if (download !is ModelDownload.State.Running) return@LaunchedEffect
            while (download is ModelDownload.State.Running) {
                delay(1000)
                download = app.modelDownload.current()
            }
            refresh()
        }

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                status = try {
                    val file = app.modelStore.import(uri) { copied, total ->
                        status = "Copiando: ${copied shr 20} MB" + if (total > 0) " de ${total shr 20} MB" else ""
                    }
                    refresh()
                    choose(file.path)
                    "Modelo pronto: ${file.name}"
                } catch (e: Exception) {
                    "Falha ao importar: ${e.message}"
                }
                busy = false
            }
        }

        Text("Cérebro no celular (offline)", style = MaterialTheme.typography.labelLarge)
        Hint("Funciona sem internet e sem chave. É mais lento que o online: a primeira resposta pode levar meio minuto; as seguintes saem mais rápido.")

        val recommended = app.modelDownload.target
        if (!recommended.isFile) {
            when (val d = download) {
                is ModelDownload.State.Running -> {
                    val pct = if (d.totalBytes > 0) " (${d.downloadedBytes * 100 / d.totalBytes}%)" else ""
                    Hint("Baixando Qwen3-4B: ${d.downloadedBytes shr 20} de ${if (d.totalBytes > 0) d.totalBytes shr 20 else 2400} MB$pct" + (d.waitingReason?.let { " — $it" } ?: ""))
                    OutlinedButton(onClick = {
                        app.modelDownload.cancel()
                        download = app.modelDownload.current()
                    }) { Text("Cancelar download") }
                }
                else -> {
                    if (d is ModelDownload.State.Failed) Hint("O download falhou: ${d.reason}")
                    Button(onClick = {
                        app.modelDownload.start()
                        download = app.modelDownload.current()
                    }) { Text("Baixar Qwen3-4B (2,4 GB, só no Wi-Fi)") }
                    Hint("Mesmo modelo do EduMath. Pode fechar o app: o download continua e aparece nas notificações.")
                }
            }
        }

        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Importar arquivo .gguf ou .litertlm") }
        Hint(
            "O Android não deixa um app abrir os arquivos de outro, então o Euno não enxerga o modelo guardado pelo EduMath. " +
                "Para não baixar de novo: copie Qwen3-4B-Q4_K_M.gguf do PC (pasta %APPDATA%\\codigomaker\\modelos\\celular) para a pasta Download do celular e toque em Importar.",
        )
        if (status.isNotEmpty()) Hint(status)

        if (localModels.isEmpty()) {
            Hint("Nenhum modelo no celular ainda.")
        } else {
            Choice(listOf("") + localModels.map { it.path }, localPath, { path ->
                if (path.isEmpty()) "Não usar" else localModels.first { it.path == path }.let { "${it.name} (${it.length() shr 20} MB)" }
            }) { choose(it) }
        }

        val selected = localModels.firstOrNull { it.path == localPath }
        if (selected != null && !ModelStore.isGguf(selected)) {
            Choice(LocalBackend.entries, backend, { if (it == LocalBackend.GPU) "GPU (mais rápido)" else "CPU (mais compatível)" }) {
                backend = it
                settings.localBackend = it
            }
        }
        if (selected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val brain = app.conversation.localBrain() ?: return@OutlinedButton
                    busy = true
                    status = "Carregando o modelo e pensando… (a primeira vez demora)"
                    scope.launch {
                        val start = System.currentTimeMillis()
                        var first = -1L
                        val text = StringBuilder()
                        var error: String? = null
                        brain.generate(LlmRequest("Responda em português, em uma frase curta.", listOf(ChatMessage(Role.USER, "Diga oi.")), 40)).collect {
                            when (it) {
                                is LlmChunk.Text -> {
                                    if (first < 0) first = System.currentTimeMillis() - start
                                    text.append(it.text)
                                    status = "Respondendo: ${text.toString().trim().take(80)}"
                                }
                                is LlmChunk.Error -> error = it.message
                                else -> Unit
                            }
                        }
                        val total = System.currentTimeMillis() - start
                        status = error?.let { "Falhou: $it" }
                            ?: "Funcionou: \"${text.toString().trim().take(80)}\" · 1ª palavra em ${first / 1000.0} s · total ${total / 1000.0} s"
                        busy = false
                    }
                }) { Text("Testar IA do celular") }
                TextButton(enabled = !busy, onClick = {
                    app.conversation.releaseLocalModel()
                    app.modelStore.delete(selected)
                    status = "Apagado: ${selected.name}"
                    choose("")
                    download = app.modelDownload.current()
                    refresh()
                }) { Text("Apagar") }
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
    private fun ProfileSection() {
        var bio by remember { mutableStateOf(settings.userBio) }
        var statusMsg by remember { mutableStateOf("") }

        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().use { r ->
                            val buf = CharArray(MAX_BIO_CHARS + 1)
                            val n = r.read(buf)
                            if (n <= 0) "" else String(buf, 0, minOf(n, MAX_BIO_CHARS))
                        }
                    }
                }.onSuccess { text ->
                    if (!text.isNullOrBlank()) {
                        bio = text
                        settings.userBio = text
                        statusMsg = "Arquivo carregado (${text.length} caracteres; limite $MAX_BIO_CHARS)."
                    }
                }.onFailure {
                    statusMsg = "Falha ao ler o arquivo: ${it.localizedMessage}"
                }
            }
        }

        Section("Sobre Mim (Dossiê do Professor)") {
            Hint("Ensine ao Euno tudo sobre você, suas disciplinas, rotina e métodos fora do chat. Esse dossiê é injetado como contexto permanente nas conversas e, se o cérebro for online (nuvem), é enviado ao provedor — não coloque dados sensíveis (documentos, endereço, senhas).")
            OutlinedTextField(
                value = bio,
                onValueChange = {
                    val v = it.take(MAX_BIO_CHARS)
                    bio = v
                    settings.userBio = v
                    statusMsg = ""
                },
                label = { Text("Dossiê / Perfil Pessoal") },
                placeholder = {
                    Text("Ex: Professor de Matemática, prefere explicações passo a passo...")
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                minLines = 4,
                maxLines = 10,
            )
            if (statusMsg.isNotBlank()) {
                Text(statusMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Carregar arquivo (.md / .txt)")
                }
                if (bio.isBlank()) {
                    OutlinedButton(
                        onClick = {
                            val template = """
                                Profissão e áreas de atuação: (preencha)
                                Como prefiro ser atendido: direto, organizado, passo a passo.
                                Projetos e ferramentas que uso: (preencha)
                            """.trimIndent()
                            bio = template
                            settings.userBio = template
                            statusMsg = "Modelo preenchido: edite com os seus dados."
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Preencher modelo")
                    }
                }
            }
        }
    }

    @Composable
    private fun ScreenSection() {
        var placement by remember { mutableStateOf(settings.placementMode) }
        var size by remember { mutableFloatStateOf(settings.characterSizeDp.toFloat()) }
        var autoDismiss by remember { mutableStateOf(settings.autoPortalDismiss) }
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
            Toggle("Auto-recolher para o portal dimensional após inatividade", autoDismiss) {
                autoDismiss = it
                settings.autoPortalDismiss = it
            }
            Hint("Quando inativo, o personagem desce para dentro do portal dimensional e fica flutuando apenas o círculo holográfico. Um toque ou duplo toque alterna.")
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
            var naturalEngine by remember { mutableStateOf(settings.voiceEngine) }
            var naturalVoice by remember { mutableStateOf(settings.geminiVoice) }
            var kokoroSpeaker by remember { mutableIntStateOf(settings.kokoroSpeaker) }
            var kokoroState by remember { mutableStateOf<KokoroVoice.State>(KokoroVoice.State.NotInstalled) }
            val voiceScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                while (true) {
                    kokoroState = voice.kokoro.poll()
                    if (kokoroState !is KokoroVoice.State.Downloading) break
                    delay(1000)
                }
            }
            Text("Voz natural", style = MaterialTheme.typography.labelLarge)
            Choice(VoiceEngine.entries, naturalEngine, { it.label }) {
                naturalEngine = it
                settings.voiceEngine = it
                voice.stop()
            }
            if (naturalEngine == VoiceEngine.AUTO || naturalEngine == VoiceEngine.GEMINI) {
                if (settings.cloudPreset != CloudPreset.GEMINI) {
                    Hint("A voz do Gemini usa a mesma chave do Google Gemini do Cérebro. Escolha o Gemini lá para ativá-la.")
                } else {
                    val default = GeminiSpeech.defaultVoiceFor(settings.character.id)
                    Choice(listOf("") + GeminiSpeech.VOICES, naturalVoice, { if (it.isEmpty()) "Gemini: padrão de ${settings.displayName} ($default)" else "Gemini: $it" }) {
                        naturalVoice = it
                        settings.geminiVoice = it
                    }
                    Hint("Cada fala consome cota da sua chave do Gemini.")
                }
            }
            if (naturalEngine != VoiceEngine.ANDROID && naturalEngine != VoiceEngine.GEMINI) {
                when (val k = kokoroState) {
                    KokoroVoice.State.Ready -> {
                        val default = KokoroVoice.defaultSpeakerFor(settings.character.id, settings.character.gender == Gender.FEMALE)
                        Choice(listOf(-1) + KokoroVoice.VOICES.keys, kokoroSpeaker, {
                            if (it < 0) "Kokoro: padrão de ${settings.displayName} (${KokoroVoice.VOICES[default]})" else "Kokoro: ${KokoroVoice.VOICES[it]}"
                        }) {
                            kokoroSpeaker = it
                            settings.kokoroSpeaker = it
                        }
                        TextButton(onClick = {
                            voice.kokoro.delete()
                            kokoroState = KokoroVoice.State.NotInstalled
                        }) { Text("Apagar voz offline") }
                    }
                    is KokoroVoice.State.Downloading -> {
                        Hint("Baixando voz offline: ${k.bytes shr 20} de ${if (k.total > 0) k.total shr 20 else 130} MB")
                        OutlinedButton(onClick = { voice.kokoro.cancel(); kokoroState = KokoroVoice.State.NotInstalled }) { Text("Cancelar") }
                    }
                    KokoroVoice.State.Extracting -> Hint("Preparando a voz…")
                    else -> {
                        if (k is KokoroVoice.State.Failed) Hint("Falhou: ${k.reason}")
                        Button(onClick = {
                            voice.kokoro.startDownload()
                            voiceScope.launch {
                                while (true) {
                                    delay(1000)
                                    kokoroState = voice.kokoro.poll()
                                    if (kokoroState !is KokoroVoice.State.Downloading) break
                                }
                            }
                        }) { Text("Baixar voz offline Kokoro (~130 MB, Wi-Fi)") }
                        Hint("Voz natural sem internet, em português do Brasil (vozes Dora, Alex e Santa).")
                    }
                }
            }
            Text("Voz do Android (reserva)", style = MaterialTheme.typography.labelLarge)
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
            Button(onClick = { voice.resetGeminiCooldown(); voice.speak("Oi, ${settings.userName}! Eu sou ${settings.displayName}. Assim fica bom?") }) { Text("Testar voz") }
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
            Hint("No Modo Privado só o cérebro do celular é usado e nada é memorizado.")
        }
    }

    @Composable
    private fun ActionsSection() {
        val scope = rememberCoroutineScope()
        var autonomy by remember { mutableStateOf(settings.autonomy) }
        var refresh by remember { mutableIntStateOf(0) }
        var servers by remember { mutableStateOf(app.toolbox.mcp.discover()) }
        var serverTools by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var network by remember { mutableStateOf(settings.networkMcpServersRaw) }
        Section("O que ${settings.displayName} pode fazer") {
            Text("Autonomia", style = MaterialTheme.typography.labelLarge)
            Choice(
                listOf(AutonomyLevel.OBSERVER, AutonomyLevel.ASSISTANT, AutonomyLevel.OPERATOR, AutonomyLevel.PERSONAL_AGENT),
                autonomy,
                {
                    when (it) {
                        AutonomyLevel.OBSERVER -> "Observador: só conversa, não age"
                        AutonomyLevel.ASSISTANT -> "Assistente: pergunta antes de qualquer ação"
                        AutonomyLevel.OPERATOR -> "Operador: consulta sozinho, pergunta antes de alterar (recomendado)"
                        else -> "Agente: faz sozinho o que dá para desfazer; pergunta só o crítico"
                    }
                },
            ) {
                autonomy = it
                settings.autonomy = it
            }
            Hint("Ações críticas (apagar, enviar em seu nome, pagar) sempre pedem confirmação. Tudo fica no Histórico de ações.")

            Text("Agenda", style = MaterialTheme.typography.labelLarge)
            var calendarGranted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            }
            val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarGranted = it }
            if (calendarGranted) {
                Hint("Acesso à agenda concedido: pergunte \"como está meu dia?\". Só leitura.")
            } else {
                OutlinedButton(onClick = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Permitir ler a agenda")
                }
            }
            var contactsGranted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            }
            val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }
            if (!contactsGranted) {
                OutlinedButton(onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Permitir ler os contatos")
                }
            }
            Hint("Agenda e contatos são lidos no celular, só leitura. Com cérebro online, só o que você pedir vai ao provedor. Se faltar permissão, ele mesmo abre o pedido na tela.")

            Text("No celular", style = MaterialTheme.typography.labelLarge)
            app.toolbox.native.forEach { tool ->
                var on by remember(refresh) { mutableStateOf(settings.toolEnabled(tool.name)) }
                Toggle(tool.description.substringBefore(" (").substringBefore(". "), on) {
                    on = it
                    settings.setToolEnabled(tool.name, it)
                }
            }

            Text("Apps e servidores conectados (MCP)", style = MaterialTheme.typography.labelLarge)
            if (servers.isEmpty()) Hint("Nenhum app do celular oferece ferramentas ao ${settings.displayName} ainda. O EduMath passa a aparecer aqui quando estiver atualizado.")
            servers.forEach { server ->
                var on by remember(server.id, refresh) { mutableStateOf(settings.mcpServerEnabled(server.id)) }
                Toggle("${server.name} · ${if (server.kind == McpServerInfo.Kind.ON_DEVICE) "no celular" else "na rede"}", on) {
                    on = it
                    settings.setMcpServerEnabled(server.id, it)
                }
                serverTools[server.id]?.let { Hint(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    servers = app.toolbox.mcp.discover()
                    scope.launch {
                        serverTools = servers.associate { server ->
                            server.id to runCatching { app.toolbox.mcp.listTools(server, refresh = true) }.fold(
                                { tools -> "${tools.size} ferramentas: " + tools.joinToString { it.title ?: it.name } },
                                { e -> "Não respondeu: ${e.message}. Se for um app, abra-o uma vez e tente de novo." },
                            )
                        }
                    }
                }) { Text("Procurar e testar") }
            }
            OutlinedTextField(
                value = network,
                onValueChange = {
                    network = it
                    settings.networkMcpServersRaw = it
                },
                label = { Text("Servidores MCP na rede (um por linha: nome|url)") },
                placeholder = { Text("PC|http://192.168.0.10:5001/mcp") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
    }

    @Composable
    private fun HistorySection() {
        val entries by app.toolbox.audit.entries.collectAsState()
        var expanded by remember { mutableStateOf(false) }
        val date = remember { SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR")) }
        Section("Histórico de ações") {
            if (entries.isEmpty()) Hint("Nenhuma ação executada ainda.")
            entries.reversed().take(if (expanded) 200 else 5).forEach { e ->
                Column {
                    Text("${e.toolName.replace('_', ' ')} — ${e.outcome}", style = MaterialTheme.typography.bodyMedium)
                    Hint("${date.format(Date(e.timestampMillis))} · ${e.reason}")
                }
            }
            if (entries.size > 5) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Mostrar menos" else "Mostrar tudo (${entries.size})") }
        }
    }

    @Composable
    private fun MemorySection() {
        var items by remember { mutableStateOf(app.memory.all()) }
        var confirmClear by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf("") }
        var editing by remember { mutableStateOf<com.joctaeng.jarvis.mind.memory.MemoryItem?>(null) }
        var adding by remember { mutableStateOf(false) }
        val date = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }
        Section("Minha Memória") {
            Hint("O que ${settings.displayName} sabe sobre você, por assunto. Diga \"lembre que…\" ou \"anota aí que…\" na conversa para acrescentar, e \"esqueça isto\" para apagar. Aqui você também pode acrescentar e corrigir à mão.")
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Acrescentar uma memória") }
            if (items.size > 5) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Procurar na memória") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val shown = items.filter { filter.isBlank() || it.text.contains(filter, ignoreCase = true) || it.category.contains(filter, ignoreCase = true) }
            if (shown.isEmpty()) Hint(if (items.isEmpty()) "Nada memorizado ainda." else "Nada encontrado.")
            shown.groupBy { it.category }.toSortedMap().forEach { (category, group) ->
                Text("${category.replaceFirstChar { it.uppercase() }} (${group.size})", style = MaterialTheme.typography.labelLarge)
                group.reversed().forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.text)
                            Hint("${date.format(Date(item.createdAtMillis))} · ${item.source}")
                        }
                        TextButton(onClick = { editing = item }) { Text("Editar") }
                        TextButton(onClick = {
                            app.memory.remove(item.id)
                            items = app.memory.all()
                        }) { Text("Esquecer") }
                    }
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
        val target = editing
        if (adding || target != null) {
            var text by remember(target?.id, adding) { mutableStateOf(target?.text.orEmpty()) }
            var category by remember(target?.id, adding) { mutableStateOf(target?.category ?: com.joctaeng.jarvis.mind.memory.MemoryCategory.GERAL) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editing = null; adding = false },
                title = { Text(if (target == null) "Nova memória" else "Editar memória") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("O que ${settings.displayName} deve saber") }, minLines = 2, maxLines = 6)
                        Text("Assunto", style = MaterialTheme.typography.labelLarge)
                        com.joctaeng.jarvis.mind.memory.MemoryCategory.ALL.chunked(3).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                rowItems.forEach { c ->
                                    androidx.compose.material3.FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        if (target == null) app.memory.add(text, "manual", "escrito por você em Minha Memória", category)
                        else app.memory.update(target.id, text, category)
                        items = app.memory.all()
                        editing = null
                        adding = false
                    }) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { editing = null; adding = false }) { Text("Cancelar") } },
            )
        }
    }
}

/** Identificadores de API não têm espaços ("Gemini 3.8 flash" é nome comercial, não ID). */
private fun looksLikeModelId(name: String): Boolean = name.none { it.isWhitespace() }
