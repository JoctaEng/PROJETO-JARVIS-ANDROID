package com.joctaeng.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.mind.orchestrator.BrainSlot
import com.joctaeng.jarvis.settings.CloudPreset
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Lista de cérebros online (a mesma em Meu Euno → Cérebro e em Configurar IA e voz): vários ao mesmo tempo, cada um com
 * a sua chave, modelo, botão Testar, liga/desliga e posição na ordem (o 1º responde; se falhar ou bater limite, o próximo).
 */
@Composable
fun BrainListEditor(app: JarvisApp, openUrl: (String) -> Unit) {
    val slots by app.brains.slots.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cérebros online (ordem de uso)", style = MaterialTheme.typography.labelLarge)
        SmallHint(
            "O 1º da lista responde. Se ele falhar ou bater o limite, o Euno passa sozinho para o próximo. " +
                "Cada um tem a sua chave: cadastrar um não apaga o outro.",
        )
        if (slots.isEmpty()) SmallHint("Nenhum cérebro online cadastrado ainda. Toque em Adicionar cérebro.")
        slots.forEachIndexed { i, slot ->
            key(slot.id) { BrainCard(app, slot, i, slots.size, openUrl) }
        }
        if (!adding) {
            Button(onClick = { adding = true }) { Text("Adicionar cérebro") }
        } else {
            Text("Qual provedor?", style = MaterialTheme.typography.labelLarge)
            CloudPreset.entries.filter { it != CloudPreset.NONE }.forEach { p ->
                Text(
                    p.label,
                    Modifier.fillMaxWidth().clickable {
                        app.brains.add(p)
                        adding = false
                    }.padding(vertical = 8.dp),
                )
            }
            TextButton(onClick = { adding = false }) { Text("Cancelar") }
        }
    }
}

@Composable
private fun SmallHint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun BrainCard(app: JarvisApp, slot: BrainSlot, index: Int, count: Int, openUrl: (String) -> Unit) {
    val brains = app.brains
    val preset = brains.preset(slot)
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(!brains.hasKey(slot.id) && preset.keyRequired) }
    var keyText by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(brains.hasKey(slot.id)) }
    var model by remember { mutableStateOf(slot.model) }
    var baseUrl by remember { mutableStateOf(slot.baseUrl) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    fun saveFields() = brains.update(slot.copy(model = model.trim(), baseUrl = baseUrl.trim()))

    fun test() {
        saveFields()
        val provider = brains.provider(brains.all().first { it.id == slot.id }, ignoreEnabled = true)
            ?: run { status = if (preset.keyRequired && !keySaved) "Cole e salve a chave primeiro." else "Falta o endereço ou o modelo."; return }
        status = "Testando…"
        scope.launch {
            val t0 = System.currentTimeMillis()
            val sb = StringBuilder()
            var err: String? = null
            try {
                // 512 tokens: modelos que "pensam" antes (ex.: gpt-oss) gastam parte disso raciocinando.
                withTimeout(40_000) {
                    provider.generate(LlmRequest("Responda só com a palavra ok.", listOf(ChatMessage(Role.USER, "diga ok")), 512)).collect {
                        when (it) {
                            is LlmChunk.Text -> sb.append(it.text)
                            is LlmChunk.Error -> err = it.message
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val ms = System.currentTimeMillis() - t0
            status = when {
                err != null -> "Não funcionou: $err"
                sb.isBlank() -> "Conectou, mas veio resposta vazia em $ms ms (o modelo pode precisar de mais espaço para pensar)."
                else -> "Funcionou em $ms ms: \"${sb.toString().trim().take(40)}\""
            }
            app.events.info("ajustes", "teste do cérebro ${brains.label(slot)}: ${status.take(160)}")
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}º · ${brains.label(slot)}", style = MaterialTheme.typography.titleSmall)
                    SmallHint(
                        listOf(
                            if (slot.enabled) "ligado" else "desligado",
                            if (!preset.keyRequired) "sem chave" else if (keySaved) "chave salva" else "FALTA A CHAVE",
                        ).joinToString(" · "),
                    )
                }
                Switch(checked = slot.enabled, onCheckedChange = { brains.update(slot.copy(enabled = it)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { brains.move(slot.id, -1) }, enabled = index > 0) { Text("↑ Subir") }
                TextButton(onClick = { brains.move(slot.id, 1) }, enabled = index < count - 1) { Text("↓ Descer") }
                TextButton(onClick = { open = !open }) { Text(if (open) "Fechar" else "Editar") }
                TextButton(onClick = { test() }) { Text("Testar") }
            }
            if (open) {
                SmallHint(preset.help)
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(if (keySaved) "Chave (salva — cole outra para trocar)" else "Chave de API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset == CloudPreset.CUSTOM || preset == CloudPreset.OWN_SERVER || baseUrl.trim() != preset.baseUrl) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Endereço (termina em /v1)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (keyText.isNotBlank()) {
                            brains.setKey(slot.id, keyText)
                            keySaved = true
                            keyText = ""
                        }
                        test()
                    }) { Text("Salvar e testar") }
                    OutlinedButton(onClick = {
                        saveFields()
                        val provider = brains.provider(brains.all().first { it.id == slot.id }, ignoreEnabled = true) as? OpenAiCompatibleProvider
                        if (provider == null) {
                            status = "Salve a chave primeiro."
                        } else scope.launch {
                            status = "Buscando modelos…"
                            status = try {
                                models = provider.listModels()
                                if (models.isEmpty()) "O serviço não listou modelos; digite o nome." else "Toque em um modelo para escolher."
                            } catch (e: Exception) {
                                "Não consegui listar: ${e.message}"
                            }
                        }
                    }) { Text("Ver modelos") }
                }
                if (models.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        models.forEach { m ->
                            Text(
                                m,
                                Modifier.fillMaxWidth().clickable {
                                    model = m
                                    models = emptyList()
                                    saveFields()
                                }.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keyLink(preset)?.let { url -> TextButton(onClick = { openUrl(url) }) { Text("Pegar a chave no site") } }
                    TextButton(onClick = { brains.remove(slot.id) }) { Text("Remover") }
                }
            }
            if (status.isNotEmpty()) SmallHint(status)
        }
    }
}

private fun keyLink(preset: CloudPreset): String? = when (preset) {
    CloudPreset.GEMINI -> "https://aistudio.google.com/apikey"
    CloudPreset.GROQ -> "https://console.groq.com/keys"
    CloudPreset.CEREBRAS -> "https://cloud.cerebras.ai/"
    CloudPreset.OPENAI -> "https://platform.openai.com/api-keys"
    CloudPreset.OPENROUTER -> "https://openrouter.ai/keys"
    else -> null
}
