package com.joctaeng.jarvis.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.mind.local.LiteRtLmProvider
import com.joctaeng.jarvis.mind.local.LocalBackend
import com.joctaeng.jarvis.mind.models.CatalogModel
import com.joctaeng.jarvis.mind.models.DeviceFit
import com.joctaeng.jarvis.mind.models.FitLevel
import com.joctaeng.jarvis.mind.models.ModelDraft
import com.joctaeng.jarvis.mind.models.ModelEngine
import com.joctaeng.jarvis.mind.models.ModelKind
import com.joctaeng.jarvis.mind.models.SwitchOutcome
import com.joctaeng.jarvis.mind.models.SwitchText
import com.joctaeng.jarvis.poc.ModelDownload
import com.joctaeng.jarvis.poc.ModelIntegrity
import com.joctaeng.jarvis.poc.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Catálogo de modelos offline (pedido do usuário, 18/10): tocar, baixar, pausar, retomar, cancelar, apagar, conferir e
 * TROCAR o cérebro do celular. Escolher não aplica: só o botão Salvar troca, depois de o motor abrir e responder a um
 * teste; a confirmação mostra o que o motor carregou. Se falhar, o anterior continua.
 */
class ModelCatalogActivity : ComponentActivity() {
    private val app get() = JarvisApp.from(this)
    private val settings get() = app.settings
    private val downloads get() = app.modelDownload

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun totalRam(): Long = runCatching {
        ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    private fun freeSpace(): Long = runCatching { StatFs(app.modelStore.directory.path).availableBytes }.getOrDefault(0L)

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        // Draft = (ativo, escolhido). Ativo só muda quando o Salvar dá certo.
        var draft by remember { mutableStateOf(ModelDraft(settings.localModelPath, settings.localModelPath)) }
        var backend by remember { mutableStateOf(settings.localBackend) }
        val backendDirty = backend != settings.localBackend && draft.pending.isNotBlank() && !draft.pending.endsWith(".gguf")
        val dirty = draft.dirty || backendDirty
        var tick by remember { mutableIntStateOf(0) }
        var saving by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var askExit by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf<File?>(null) }

        // Atualiza progresso de download e espaço a cada segundo.
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                tick++
            }
        }

        BackHandler(enabled = dirty && !saving) { askExit = true }

        fun save() {
            saving = true
            result = "Carregando ${nameOf(draft.pending)} e fazendo um teste… (a primeira vez pode levar até um minuto)"
            scope.launch {
                val outcome = app.conversation.switchLocalModel(draft.pending, backend)
                when (outcome) {
                    is SwitchOutcome.Done -> {
                        draft = ModelDraft(settings.localModelPath, settings.localModelPath)
                        backend = settings.localBackend
                        result = if (draft.active.isBlank()) SwitchText.turnedOff() else SwitchText.confirmation(outcome.report)
                    }
                    is SwitchOutcome.Failed -> {
                        result = SwitchText.failure(outcome.reason, outcome.keptActive)
                        draft = draft.discard()
                    }
                }
                saving = false
            }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Catálogo de modelos offline", style = MaterialTheme.typography.headlineSmall)
            val ram = remember { totalRam() }
            val free = remember(tick / 5) { freeSpace() }
            val files = remember(tick) { app.modelStore.list() }
            val used = files.sumOf { it.length() }
            Hint(
                "Aparelho: ${DeviceFit.bytes(ram)} de RAM · livre: ${DeviceFit.bytes(free)} · modelos ocupam ${DeviceFit.bytes(used)} · " +
                    "LiteRT-LM ${LiteRtLmProvider.RUNTIME_VERSION} · catálogo conferido em ${downloads.catalog.verifiedAt.split('-').reversed().joinToString("/")}.",
            )
            Hint("Toque em um modelo baixado para escolhê-lo e depois em Salvar. Só um modelo fica na memória por vez: o anterior é liberado na troca.")

            ActiveCard(draft, dirty, saving, result, onSave = { save() }, onDiscard = {
                draft = draft.discard()
                backend = settings.localBackend
                result = ""
            })

            downloads.catalog.models.forEach { m ->
                val state = remember(tick) { downloads.stateOf(m) }
                ModelCard(
                    m, state, ram, free, draft.pending,
                    onPick = { if (!saving) draft = draft.select(it) },
                    onStatus = { status = it },
                    onDelete = { confirmDelete = it },
                    scope = scope,
                )
            }

            // Arquivos importados que não estão no catálogo continuam utilizáveis.
            val others = files.filter { f -> downloads.catalog.byFile(f.name) == null }
            if (others.isNotEmpty()) {
                Section("Outros arquivos (importados)") {
                    others.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.pending == f.path, onClick = { if (!saving) draft = draft.select(f.path) })
                            Text("${f.name} (${DeviceFit.bytes(f.length())})", Modifier.weight(1f))
                            TextButton(onClick = { confirmDelete = f }) { Text("Apagar") }
                        }
                    }
                }
            }

            Section("Como o cérebro do celular roda") {
                Row(Modifier.fillMaxWidth().selectable(draft.pending.isBlank()) { if (!saving) draft = draft.select("") }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.pending.isBlank(), onClick = { if (!saving) draft = draft.select("") })
                    Text("Desligado (não usar modelo no celular)")
                }
                if (draft.pending.isNotBlank() && !ModelStore.isGguf(File(draft.pending))) {
                    LocalBackend.entries.forEach { b ->
                        Row(Modifier.fillMaxWidth().selectable(backend == b) { backend = b }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = backend == b, onClick = { backend = b })
                            Text(if (b == LocalBackend.GPU) "Placa de vídeo (GPU, mais rápido; cai para CPU se o celular recusar)" else "Processador (CPU, mais compatível)")
                        }
                    }
                }
            }

            HfTokenSection { status = it }
            ShareSection()
            if (status.isNotEmpty()) Hint(status)

            Text("Fora do catálogo por enquanto (não confirmados para Android no LiteRT-LM ${LiteRtLmProvider.RUNTIME_VERSION}): Gemma 4 12B e 26B (arquivos para computador), Qwen3.5-4B, Agents-A1-4B, MiniCPM-V 4.7, Nemotron Nano 9B v2, Parakeet (fala→texto) e EmbeddingGemma 2 740M (exige LiteRT-LM 0.18).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (askExit) {
            AlertDialog(
                onDismissRequest = { askExit = false },
                title = { Text(SwitchText.PENDING) },
                text = { Text("Você escolheu ${nameOf(draft.pending)}, mas ainda não salvou. O Euno continua usando ${nameOf(draft.active)}.") },
                confirmButton = { TextButton(onClick = { askExit = false; save() }) { Text("Salvar") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { askExit = false; finish() }) { Text("Descartar") }
                        TextButton(onClick = { askExit = false }) { Text("Continuar aqui") }
                    }
                },
            )
        }

        confirmDelete?.let { f ->
            val isActive = f.path == settings.localModelPath
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Apagar ${f.name}?") },
                text = { Text(if (isActive) "É o modelo ativo: o cérebro do celular fica desligado até você salvar outro." else "Libera ${DeviceFit.bytes(f.length())}. Dá para baixar de novo depois.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        scope.launch {
                            if (isActive) app.conversation.switchLocalModel("", settings.localBackend)
                            withContext(Dispatchers.IO) { app.modelStore.delete(f) }
                            draft = ModelDraft(settings.localModelPath, if (draft.pending == f.path) settings.localModelPath else draft.pending)
                            status = "Apagado: ${f.name}"
                            tick++
                        }
                    }) { Text("Apagar") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
            )
        }
    }

    private fun nameOf(path: String): String {
        if (path.isBlank()) return SwitchText.NONE
        val f = File(path)
        return downloads.catalog.byFile(f.name)?.name ?: f.nameWithoutExtension
    }

    @Composable
    private fun ActiveCard(draft: ModelDraft, dirty: Boolean, saving: Boolean, result: String, onSave: () -> Unit, onDiscard: () -> Unit) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (dirty) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ativo agora: ${nameOf(draft.active)}", style = MaterialTheme.typography.titleMedium)
                val inMemory = app.conversation.loadedLocalDescription()
                if (inMemory != null) Hint("Na memória: $inMemory")
                else if (draft.active.isNotBlank()) Hint("Ainda não carregado (abre na primeira conversa offline).")
                if (dirty) {
                    Text("${SwitchText.PENDING}: escolhido ${nameOf(draft.pending)}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave, enabled = !saving) { Text("Salvar") }
                        OutlinedButton(onClick = onDiscard, enabled = !saving) { Text("Descartar") }
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun ModelCard(
        m: CatalogModel,
        state: ModelDownload.State,
        ram: Long,
        free: Long,
        pending: String,
        onPick: (String) -> Unit,
        onStatus: (String) -> Unit,
        onDelete: (File) -> Unit,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val file = downloads.fileOf(m)
        val downloaded = state is ModelDownload.State.Done
        val fit = DeviceFit.rate(m, ram, free, downloaded, LiteRtLmProvider.RUNTIME_VERSION)
        var checking by remember { mutableStateOf(false) }
        Section(m.name) {
            Text(
                "${fit.level.label} — ${fit.reason}",
                color = when (fit.level) {
                    FitLevel.RECOMMENDED -> MaterialTheme.colorScheme.primary
                    FitLevel.WORKS -> MaterialTheme.colorScheme.tertiary
                    FitLevel.TOO_HEAVY -> MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Hint(
                "${if (m.sizeApproximate) "~" else ""}${DeviceFit.bytes(m.sizeBytes)} · ${m.capabilities.joinToString(", ") { it.label }} · " +
                    "${if (m.engine == ModelEngine.GGUF) "llama.cpp" else "LiteRT-LM ≥ ${m.minLiteRtLm}"} · licença ${m.license}${if (m.gated) " · exige aceitar a licença no Hugging Face" else ""}",
            )
            if (m.notes.isNotBlank()) Hint(m.notes)
            when (state) {
                is ModelDownload.State.Running -> {
                    val total = if (state.totalBytes > 0) state.totalBytes else m.sizeBytes
                    LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Hint("${state.downloadedBytes shr 20} de ${total shr 20} MB" + (state.waitingReason?.let { " — $it" } ?: ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { downloads.pause() }) { Text("Pausar") }
                        TextButton(onClick = { downloads.cancel(m) }) { Text("Cancelar") }
                    }
                }
                is ModelDownload.State.Paused -> {
                    Hint("Pausado em ${state.downloadedBytes shr 20} de ${m.sizeBytes shr 20} MB.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (!downloads.start(m)) onStatus("Espere o download de ${downloads.model.name} terminar (um por vez).") }) { Text("Retomar") }
                        TextButton(onClick = { downloads.cancel(m) }) { Text("Cancelar") }
                    }
                }
                is ModelDownload.State.Done -> {
                    if (m.kind == ModelKind.CHAT) {
                        Row(Modifier.fillMaxWidth().selectable(pending == file.path) { onPick(file.path) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pending == file.path, onClick = { onPick(file.path) })
                            Text(if (file.path == settings.localModelPath) "Usar este (ativo)" else "Usar este")
                        }
                    } else {
                        Hint("Baixado. Ligue em Meu Euno → Minhas memórias → memória por sentido.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !checking, onClick = {
                            checking = true
                            onStatus("Conferindo ${m.file}… (leva uns 30 s por GB)")
                            scope.launch {
                                val v = withContext(Dispatchers.IO) { runCatching { ModelIntegrity.verify(file, m) }.getOrNull() }
                                onStatus(v?.let { ModelIntegrity.describe(it, m.file) } ?: "Não deu para conferir o arquivo.")
                                checking = false
                            }
                        }) { Text("Conferir integridade") }
                        TextButton(onClick = { onDelete(file) }) { Text("Apagar") }
                    }
                }
                is ModelDownload.State.Failed -> {
                    Hint("O download falhou: ${state.reason}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { downloads.start(m) }) { Text("Tentar de novo") }
                        TextButton(onClick = { downloads.cancel(m) }) { Text("Cancelar") }
                    }
                }
                ModelDownload.State.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = fit.level != FitLevel.TOO_HEAVY, onClick = {
                            if (!downloads.start(m)) onStatus("Espere o download de ${downloads.model.name} terminar (um por vez).")
                            else onStatus("Baixando ${m.name} (só no Wi-Fi; pode fechar o app, aparece nas notificações).")
                        }) { Text("Baixar") }
                        TextButton(onClick = { open(m.url) }) { Text("Pelo navegador") }
                    }
                }
            }
            TextButton(onClick = { open(m.pageUrl) }) { Text("Página do modelo") }
        }
    }

    /** Tutorial e campo do token do Hugging Face (modelos que exigem aceitar a licença). Fica no cofre do celular. */
    @Composable
    private fun HfTokenSection(onStatus: (String) -> Unit) {
        var token by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(app.secrets.get(ModelDownload.HF_TOKEN) != null) }
        Section("Modelo pediu login no Hugging Face?") {
            Hint("Alguns modelos só baixam depois de você aceitar a licença. Os do catálogo atual não exigem; se o download disser 401/403:")
            listOf(
                "Toque em \"Página do modelo\" e entre (ou crie) sua conta no Hugging Face.",
                "Na página do modelo, toque em aceitar a licença (\"Agree and access repository\").",
                "Abra huggingface.co/settings/tokens → Create new token → tipo Read → copie o token (começa com hf_).",
                "Cole abaixo e toque em Salvar token; depois toque em Tentar de novo/Retomar no modelo.",
            ).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                token, { token = it },
                label = { Text(if (saved) "Token do Hugging Face (salvo)" else "Token do Hugging Face") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = token.isNotBlank(), onClick = {
                    app.secrets.put(ModelDownload.HF_TOKEN, token.trim())
                    token = ""
                    saved = true
                    onStatus("Token salvo no cofre do celular.")
                }) { Text("Salvar token") }
                if (saved) TextButton(onClick = {
                    app.secrets.put(ModelDownload.HF_TOKEN, null)
                    saved = false
                    onStatus("Token apagado.")
                }) { Text("Apagar token") }
                TextButton(onClick = { open("https://huggingface.co/settings/tokens") }) { Text("Abrir tokens") }
            }
        }
    }

    /** Compartilhar com o Joca Finanças a lista dos modelos baixados (só leitura; docs/CONTRATO-MODELOS-JOCA.md). */
    @Composable
    private fun ShareSection() {
        var share by remember { mutableStateOf(settings.shareModelsWithJoca) }
        Section("Joca Finanças") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deixar o Joca Finanças usar os modelos já baixados aqui", Modifier.weight(1f))
                Switch(checked = share, onCheckedChange = { share = it; settings.shareModelsWithJoca = it })
            }
            Hint("Só o app Joca Finanças (conferido pelo Android) lê a lista e os arquivos, sem poder mudar nada. Assim ele não baixa de novo.")
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Hint(text: String) =
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
