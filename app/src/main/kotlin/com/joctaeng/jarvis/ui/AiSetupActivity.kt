package com.joctaeng.jarvis.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.mind.cloud.CloudConfig
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.settings.CloudPreset
import com.joctaeng.jarvis.settings.SecretStore
import com.joctaeng.jarvis.settings.VoiceEngine
import com.joctaeng.jarvis.system.resources.AzureSsml
import com.joctaeng.jarvis.voice.AzureSpeech
import com.joctaeng.jarvis.voice.KokoroVoice
import com.joctaeng.jarvis.voice.PcmPlayer
import com.joctaeng.jarvis.voice.PiperVoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Configurar IA e voz": passo a passo para criar as chaves grátis (Azure, Groq, Cerebras, Gemini) e baixar as vozes offline,
 * com os campos para colar e testar tudo aqui mesmo. As chaves ficam só no celular (SecretStore, Android Keystore).
 */
class AiSetupActivity : ComponentActivity() {
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
                        Text("Configurar IA e voz", style = MaterialTheme.typography.headlineSmall)
                        Hint(
                            "Tudo aqui é grátis. Siga cada passo, copie a chave no site e cole no campo. " +
                                "As chaves ficam guardadas só neste celular (cofre do Android) e nunca vão para outro lugar.",
                        )
                        EngineSection()
                        AzureSection()
                        BackupBrainSection()
                        PiperSection()
                    }
                }
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
    private fun Steps(vararg steps: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        }
    }

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
    private fun EngineSection() {
        var engine by remember { mutableStateOf(settings.voiceEngine) }
        Section("Qual voz usar") {
            Hint("Recomendado: Automático. Ele tenta o Azure; se faltar internet ou a cota, passa para o Gemini, depois para a voz offline baixada e, por fim, para a voz do Android.")
            Choice(VoiceEngine.entries, engine, { it.label }) {
                engine = it
                settings.voiceEngine = it
                app.voice.stop()
            }
        }
    }

    @Composable
    private fun AzureSection() {
        val scope = rememberCoroutineScope()
        var key by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(app.secrets.has(SecretStore.AZURE_SPEECH_KEY)) }
        var region by remember { mutableStateOf(settings.azureRegion) }
        var voiceName by remember { mutableStateOf(settings.azureVoice) }
        var voices by remember { mutableStateOf<List<AzureSpeech.AzureVoice>>(emptyList()) }
        var status by remember { mutableStateOf("") }
        val used = remember(status) { app.voice.azureCharsThisMonth() }

        fun test() {
            val k = app.secrets.get(SecretStore.AZURE_SPEECH_KEY)
            if (k.isNullOrBlank()) {
                status = "Cole a chave primeiro."
                return
            }
            status = "Testando a chave…"
            scope.launch {
                status = try {
                    voices = AzureSpeech(k, settings.azureRegion).voices()
                    app.voice.resetAzureCooldown()
                    if (voices.isEmpty()) "A chave funcionou, mas não vieram vozes pt-BR. Confira a região." else "Funcionou! ${voices.size} vozes brasileiras. Escolha uma abaixo e toque em Ouvir."
                } catch (e: Exception) {
                    "Não funcionou: ${e.message}. Confira se copiou a CHAVE 1 inteira e se a região está igual à do portal."
                }
            }
        }

        Section("1. Voz humana: Azure (1ª opção)") {
            Hint("Vozes neurais brasileiras da Microsoft. O plano grátis (F0) dá cerca de 500 mil caracteres por mês. No Automático o Euno para sozinho em ${"%,d".format(AzureSsml.AUTO_STOP_AT).replace(',', '.')} para não passar do grátis.")
            Steps(
                "Toque em Abrir o portal do Azure e entre com a sua conta Microsoft (Hotmail/Outlook). Se ainda não tiver, crie uma grátis.",
                "Se aparecer \"Você não tem uma assinatura?\", toque em Iniciar (avaliação gratuita do Azure). A Microsoft pede telefone e cartão só para confirmar a identidade; o plano F0 da voz não cobra. Sem assinatura o portal dá erro (AADSTS16000).",
                "Volte à página inicial do portal. Na busca do topo, digite \"Speech\" e toque em \"Fala\" (Marketplace). Toque em Criar.",
                "Preencha: Assinatura = a que você criou; Grupo de recursos = Criar novo (ex.: euno); Região = Brazil South; Nome = qualquer nome único; Tipo de preço = Free F0. Se aparecer algum valor em dinheiro, pare: não é o F0.",
                "Toque em Examinar + criar e depois em Criar. Espere terminar e toque em Ir para o recurso.",
                "No recurso, abra Chaves e Ponto de Extremidade (Keys and endpoint). Toque no botão de copiar da CHAVE 1 e confira a Localização/Região (ex.: brazilsouth).",
                "Cole a chave e a região aqui embaixo e toque em Salvar e testar.",
            )
            OutlinedButton(onClick = { open("https://portal.azure.com/") }) { Text("Abrir o portal do Azure") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (keySaved) "Chave do Azure (salva — cole outra para trocar)" else "Chave do Azure (CHAVE 1)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = region,
                onValueChange = {
                    region = it
                    settings.azureRegion = it.trim().lowercase().replace(" ", "")
                },
                label = { Text("Região (ex.: brazilsouth, eastus)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (key.isNotBlank()) {
                        app.secrets.put(SecretStore.AZURE_SPEECH_KEY, key.trim())
                        keySaved = true
                        key = ""
                    }
                    test()
                }) { Text("Salvar e testar") }
                if (keySaved) {
                    TextButton(onClick = {
                        app.secrets.put(SecretStore.AZURE_SPEECH_KEY, null)
                        keySaved = false
                        voices = emptyList()
                        status = "Chave do Azure apagada."
                    }) { Text("Apagar chave") }
                }
            }
            if (status.isNotEmpty()) Hint(status)
            if (voices.isNotEmpty()) {
                Text("Voz do Azure", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Choice(listOf("") + voices.map { it.shortName }, voiceName, { name ->
                        if (name.isEmpty()) "Padrão do personagem (Francisca ou Antonio)"
                        else voices.first { it.shortName == name }.let { "${it.localName} (${if (it.gender == "Female") "feminina" else "masculina"})" }
                    }) {
                        voiceName = it
                        settings.azureVoice = it
                    }
                }
            } else if (voiceName.isNotEmpty()) {
                Hint("Voz escolhida: $voiceName")
            }
            if (keySaved) {
                OutlinedButton(onClick = {
                    app.voice.resetAzureCooldown()
                    app.voice.speak("Oi, ${settings.userName}! Esta é a minha voz pelo Azure. Ficou natural?")
                }) { Text("Ouvir") }
                if (settings.voiceEngine != VoiceEngine.AUTO && settings.voiceEngine != VoiceEngine.AZURE) {
                    Hint("Atenção: em \"Qual voz usar\" escolha Automático ou Azure para ouvir esta voz.")
                }
            }
            Hint("Usado este mês: ${"%,d".format(used).replace(',', '.')} de ${"%,d".format(AzureSsml.FREE_MONTHLY_CHARS).replace(',', '.')} caracteres.")
        }
    }

    @Composable
    private fun BackupBrainSection() {
        Section("2. Cérebros: Gemini, Groq, Cerebras (vários juntos)") {
            Hint("Cadastre quantos quiser, cada um com a sua chave. A lista é a mesma de Meu Euno → Cérebro. O 1º responde; se falhar ou bater o limite, o próximo assume. Sugestão: Gemini em 1º, Groq e Cerebras depois.")
            Text("Google Gemini", style = MaterialTheme.typography.labelLarge)
            Steps(
                "Toque em Adicionar cérebro → Google Gemini e depois em Pegar a chave no site (AI Studio, conta Google).",
                "Toque em Get API key → Create API key, copie, volte e cole no campo Chave. Toque em Salvar e testar.",
            )
            Text("Groq", style = MaterialTheme.typography.labelLarge)
            Steps(
                "Adicionar cérebro → Groq → Pegar a chave no site (pode entrar com a conta Google).",
                "Em API Keys → Create API Key, dê o nome euno e copie na hora (ela só aparece uma vez). Cole e toque em Salvar e testar.",
            )
            Hint("Groq grátis tem limite por minuto (o modelo sugerido aceita cerca de 8 mil tokens por minuto, e um pedido com ferramentas usa quase isso). Por isso ele funciona melhor como 2º ou 3º da lista.")
            Text("Cerebras", style = MaterialTheme.typography.labelLarge)
            Steps(
                "Adicionar cérebro → Cerebras → Pegar a chave no site.",
                "Em API Keys, crie e copie a chave. Cole e toque em Salvar e testar.",
            )
            BrainListEditor(app) { open(it) }
        }
    }

    @Composable
    private fun PiperSection() {
        val scope = rememberCoroutineScope()
        val piper = app.voice.piper
        var chosen by remember { mutableStateOf(settings.piperVoice) }
        val states = remember { mutableStateMapOf<String, KokoroVoice.State>() }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(tick) {
            while (true) {
                PiperVoice.VOICES.forEach { states[it.id] = piper.poll(it) }
                if (states.values.none { it is KokoroVoice.State.Downloading }) break
                delay(1000)
            }
        }

        Section("3. Voz sem internet: Piper (offline, leve)") {
            Hint("Funciona sem internet e sem gastar cota. Cada voz tem cerca de 67 MB; baixe no Wi-Fi, uma de cada vez. São vozes masculinas brasileiras (licença livre). O progresso também aparece na barra de notificações.")
            PiperVoice.VOICES.forEach { o ->
                val st = states[o.id] ?: KokoroVoice.State.NotInstalled
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().selectable(chosen == o.id) {
                                chosen = o.id
                                settings.piperVoice = o.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == o.id, onClick = {
                                chosen = o.id
                                settings.piperVoice = o.id
                            })
                            Text(o.label)
                        }
                        when (st) {
                            KokoroVoice.State.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    chosen = o.id
                                    settings.piperVoice = o.id
                                    scope.launch {
                                        runCatching {
                                            val clip = piper.synthesize("Oi, ${settings.userName}! Esta é a voz ${o.label.substringBefore(" (")}, sem internet.", o, settings.ttsRate)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PcmPlayer.play(clip) { false } }
                                        }
                                    }
                                }) { Text("Ouvir") }
                                TextButton(onClick = {
                                    piper.delete(o)
                                    states[o.id] = KokoroVoice.State.NotInstalled
                                }) { Text("Apagar") }
                            }
                            is KokoroVoice.State.Downloading ->
                                Column {
                                    Hint("Baixando: ${st.bytes shr 20} de ${if (st.total > 0) st.total shr 20 else 67} MB… (a preparação no fim leva alguns segundos)")
                                    if (st.note.isNotEmpty()) Hint("Situação: ${st.note}")
                                    TextButton(onClick = {
                                        piper.delete(o)
                                        piper.cancel(o)
                                        states[o.id] = KokoroVoice.State.NotInstalled
                                    }) { Text("Cancelar") }
                                }
                            KokoroVoice.State.Extracting -> Hint("Preparando a voz…")
                            else -> {
                                if (st is KokoroVoice.State.Failed) Hint("Falhou: ${st.reason}")
                                Button(onClick = {
                                    piper.startDownload(o)
                                    states[o.id] = KokoroVoice.State.Downloading(0, 0)
                                    tick++
                                }) { Text("Baixar (~67 MB)") }
                            }
                        }
                    }
                }
            }
            Hint("Voz offline feminina: Kokoro (~130 MB), em Meu Euno → Voz.")
        }
    }
}
