package com.joctaeng.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.control.AccessibilityLink
import com.joctaeng.jarvis.control.EunoAccessibilityService
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.diagnostics.Exporter
import com.joctaeng.jarvis.overlay.OverlayBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** "Testar funções": um botão por função; roda sozinho e mostra passou/falhou e o motivo. Tudo vai para o registro (tag "teste"). */
class TestsActivity : ComponentActivity() {
    private class Outcome(val ok: Boolean, val detail: String)
    private class Case(val id: String, val title: String, val hint: String, val run: suspend () -> Outcome)

    private val app get() = JarvisApp.from(this)
    private var taps by mutableIntStateOf(0)
    private var fieldText by mutableStateOf("")
    private val results: SnapshotStateMap<String, Outcome> = mutableStateMapOf()
    private val running = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    private suspend fun tool(name: String, json: String = "{}"): Outcome {
        val tool = app.toolbox.native.firstOrNull { it.name == name } ?: return Outcome(false, "ferramenta \"$name\" não existe neste APK")
        return when (val r = tool.execute(json, ToolContext("teste", "teste rápido"))) {
            is ToolResult.Success -> Outcome(true, r.outputJson.take(300))
            is ToolResult.Failure -> Outcome(false, r.reason.take(300))
            is ToolResult.Denied -> Outcome(false, "negado: ${r.reason}".take(300))
            ToolResult.Cancelled -> Outcome(false, "cancelado")
        }
    }

    private fun back() {
        startActivity(Intent(this, TestsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val cases: List<Case> by lazy {
        listOf(
            Case("celular", "Estado do celular", "bateria, rede, espaço") { tool("estado_do_celular") },
            Case("apps", "Listar apps", "precisa achar apps") { tool("listar_apps").let { if (it.ok && it.detail.contains("\"total\":0")) Outcome(false, "0 apps visíveis") else it } },
            Case("whats", "WhatsApp instalado?", "procura os pacotes do WhatsApp e do WhatsApp Business") {
                val pm = packageManager
                val found = listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business").filter { pm.getLaunchIntentForPackage(it.first) != null }
                val byName = tool("listar_apps", """{"filtro":"whats"}""").detail.contains("\"total\":0").not()
                Outcome(found.isNotEmpty(), if (found.isEmpty()) "nenhum pacote do WhatsApp visível ao Euno (outro espaço/duplicado?)" else "achei: ${found.joinToString { it.second }}; aparece na lista de apps pelo nome: ${if (byName) "sim" else "não (o rótulo do app é outro)"}")
            },
            Case("abrir", "Abrir app (\"Google Agenda\")", "abre a Agenda e volta para cá") {
                val r = tool("abrir_app", """{"nome":"Google Agenda"}""")
                delay(2000); back(); r
            },
            Case("agenda", "Consultar agenda de hoje", "precisa da permissão de calendário") { tool("agenda_consultar", """{"periodo":"hoje"}""") },
            Case("contatos", "Buscar contato", "busca por \"a\"; mostra só a quantidade (sem telefones)") {
                val r = tool("contatos_buscar", """{"nome":"a"}""")
                if (r.ok) Outcome(true, "${r.detail.lines().count { it.isNotBlank() }} linhas de resultado (conteúdo omitido)") else r
            },
            Case("memoria", "Memória: guardar, buscar, esquecer", "usa um fato fictício e apaga em seguida") {
                val fato = "Fato de teste do Euno zebra-quadrada-7"
                val a = tool("memoria_guardar", """{"fato":"$fato","categoria":"geral"}""")
                val b = tool("memoria_buscar", """{"consulta":"zebra-quadrada-7"}""")
                val c = tool("memoria_esquecer", """{"trecho":"zebra-quadrada-7"}""")
                val found = b.ok && b.detail.contains("zebra")
                Outcome(a.ok && found && c.ok, "guardar=${a.ok} buscar=${if (found) "achou" else "NÃO achou"} esquecer=${c.ok}: ${(if (!a.ok) a else if (!found) b else c).detail.take(120)}")
            },
            Case("lanterna", "Lanterna (liga e desliga)", "acende por 1 segundo") {
                val on = tool("lanterna", """{"ligar":true}"""); delay(1000); val off = tool("lanterna", """{"ligar":false}""")
                Outcome(on.ok && off.ok, "ligar=${on.detail.take(80)} desligar=${off.detail.take(80)}")
            },
            Case("acess", "Acessibilidade do Euno", "serviço incluído e ligado") {
                val declared = EunoAccessibilityService.isDeclared(this); val enabled = EunoAccessibilityService.isEnabled(this)
                val connected = EunoAccessibilityService.instance != null
                val hint = if (declared && enabled && !connected) " — LIGADO mas DESCONECTADO: desligue e ligue de novo \"Euno - controle do celular\" em Acessibilidade" else ""
                Outcome(declared && enabled && connected, "no APK=$declared; ligado no Android=$enabled; conectado=$connected; 'Controle do celular' nos Ajustes=${app.settings.phoneControl}$hint")
            },
            Case("ler", "Ler a tela de OUTRO app", "abre a Agenda e confere que o texto lido é da Agenda, não do Euno") {
                val open = tool("abrir_app", """{"nome":"Agenda"}""")
                delay(2500)
                val r = tool("tela_ler")
                back()
                delay(1500) // espera a tela de testes voltar antes do próximo teste (tocar/digitar usam esta tela)
                when {
                    !open.ok -> Outcome(false, "não consegui abrir a Agenda: ${open.detail}")
                    !r.ok -> r
                    r.detail.contains("com.joctaeng.jarvis") -> Outcome(false, "leu a própria tela do Euno: ${r.detail.take(120)}")
                    else -> Outcome(true, r.detail.take(160))
                }
            },
            Case("tocar", "Tocar num botão", "toca em \"Botão de teste do Euno\"") {
                val before = taps; val r = tool("tela_tocar", """{"nome":"Botão de teste do Euno"}"""); delay(500)
                Outcome(r.ok && taps > before, "toques antes=$before depois=$taps; ${r.detail.take(150)}")
            },
            Case("digitar", "Digitar num campo", "escreve no campo de teste") {
                fieldText = ""; val r = tool("tela_digitar", """{"texto":"teste do Euno 123"}"""); delay(500)
                Outcome(r.ok && fieldText.contains("teste do Euno"), "campo ficou: \"$fieldText\"; ${r.detail.take(120)}")
            },
            Case("rolar", "Rolar a tela", "rola para baixo e para cima") {
                val a = tool("tela_rolar", """{"direcao":"baixo"}"""); val b = tool("tela_rolar", """{"direcao":"cima"}""")
                Outcome(a.ok || b.ok, "baixo=${a.detail.take(100)} cima=${b.detail.take(100)} (basta um dos dois: no topo não há como subir)")
            },
            Case("voz", "Falar (voz)", "ele fala uma frase; confirme se ouviu") {
                app.voice.speak("Teste de voz do Euno. Se você me ouviu, está funcionando."); Outcome(true, "frase enviada à voz — passou só se você ouviu")
            },
            Case("mic", "Microfone e reconhecimento", "permissão e serviço de voz") {
                val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val rec = SpeechRecognizer.isRecognitionAvailable(this)
                Outcome(perm && rec, "permissão do microfone=$perm; reconhecedor disponível=$rec")
            },
            Case("dnd", "Acesso ao Não perturbe", "para silenciar o bip ao ouvir") {
                val ok = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
                Outcome(ok, if (ok) "liberado" else "NÃO liberado (Ajustes do Android → Acesso aos modos)")
            },
            Case("cerebro", "Cérebro (IA)", "pede um \"ok\" e mede o tempo") {
                val provider = app.conversation.configuredProviders().firstOrNull() ?: return@Case Outcome(false, "nenhum cérebro configurado")
                val t0 = System.currentTimeMillis(); val sb = StringBuilder(); var err: String? = null
                try {
                    withTimeout(40_000) {
                        provider.generate(LlmRequest("Responda só com a palavra ok.", listOf(ChatMessage(Role.USER, "diga ok")), 20)).collect {
                            when (it) { is LlmChunk.Text -> sb.append(it.text); is LlmChunk.Error -> err = it.message; else -> Unit }
                        }
                    }
                } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
                val ms = System.currentTimeMillis() - t0
                Outcome(err == null && sb.isNotBlank(), "${provider.displayName}: ${err ?: "respondeu \"${sb.toString().trim().take(40)}\""} em $ms ms")
            },
            Case("relatorio", "Gerar relatório completo", "mede tamanho e tempo") {
                val t0 = System.currentTimeMillis(); val text = com.joctaeng.jarvis.diagnostics.ReportCollector(app).buildFull()
                Outcome(text.length > 500, "${text.length / 1024} KB em ${System.currentTimeMillis() - t0} ms; registro em disco: ${app.events.stats().let { "${it.first} dia(s), ${it.second / 1024} KB" }}")
            },
        )
    }

    private suspend fun runCase(case: Case) {
        running.value = case.id
        EunoAccessibilityService.allowOwnWindow = case.id in setOf("tocar", "digitar", "rolar")
        val outcome = try {
            withTimeout(60_000) { case.run() }
        } catch (e: Exception) {
            Outcome(false, "erro: ${e.message ?: e.javaClass.simpleName}")
        }
        EunoAccessibilityService.allowOwnWindow = false
        OverlayBus.acting.value = false
        results[case.id] = outcome
        app.events.log(if (outcome.ok) com.joctaeng.jarvis.system.resources.LogLevel.INFO else com.joctaeng.jarvis.system.resources.LogLevel.WARN, "teste", "${case.title}: ${if (outcome.ok) "PASSOU" else "FALHOU"} — ${outcome.detail}")
        running.value = null
    }

    private fun summaryText(): String = buildString {
        appendLine("# Testes rápidos do Euno")
        cases.forEach { c -> results[c.id]?.let { appendLine("- ${c.title}: ${if (it.ok) "PASSOU" else "FALHOU"} — ${it.detail}") } ?: appendLine("- ${c.title}: não testado") }
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val busy = running.value != null
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Testar funções", style = MaterialTheme.typography.headlineSmall)
            Text("Toque em um botão para testar aquela função. O resultado e o motivo aparecem embaixo.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { scope.launch { cases.forEach { runCase(it) } } }) { Text("Testar tudo") }
                OutlinedButton(onClick = { AccessibilityLink.open(this@TestsActivity) }) { Text("Abrir acessibilidade") }
            }
            OutlinedButton(
                onClick = { Exporter.exportAndShare(this@TestsActivity, "euno-testes", "Testes rápidos do Euno", summaryText(), asPdf = false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enviar resultados dos testes") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Área de teste (usada pelos testes de tocar e digitar)", style = MaterialTheme.typography.labelMedium)
                    Button(onClick = { taps++ }) { Text("Botão de teste do Euno") }
                    Text("Toques: $taps")
                    OutlinedTextField(value = fieldText, onValueChange = { fieldText = it }, label = { Text("Campo de teste") }, modifier = Modifier.fillMaxWidth())
                }
            }
            cases.forEach { c ->
                val r = results[c.id]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text(c.hint, style = MaterialTheme.typography.bodySmall)
                        when {
                            running.value == c.id -> Text("Testando…")
                            r != null -> Text((if (r.ok) "PASSOU — " else "FALHOU — ") + r.detail, color = if (r.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(enabled = !busy, onClick = { scope.launch { runCase(c) } }) { Text("Testar") }
                    }
                }
            }
        }
    }
}
