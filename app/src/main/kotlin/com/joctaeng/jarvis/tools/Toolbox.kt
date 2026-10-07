package com.joctaeng.jarvis.tools

import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.action.gateway.AuditEntry
import com.joctaeng.jarvis.action.gateway.AuditLog
import com.joctaeng.jarvis.action.gateway.PermissionState
import com.joctaeng.jarvis.action.gateway.ToolGateway
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/** Pedido de confirmação aberto na tela ("Euno quer: criar alarme às 07:00 — Cancelar / Confirmar"). */
class PendingConfirmation(val title: String, val details: String, val critical: Boolean) {
    internal val answer = CompletableDeferred<Boolean>()
    fun respond(confirmed: Boolean) {
        answer.complete(confirmed)
    }
}

/** Ferramentas do Euno: nativas do Android + MCP (apps do celular e rede), sempre pelo Tool Gateway. */
class Toolbox(private val app: JarvisApp) {
    private val settings get() = app.settings
    val audit = FileAuditLog(File(app.filesDir, "audit.jsonl"))
    val mcp = McpHub(app) { parseNetworkServers(settings.networkMcpServersRaw) }
    val native: List<Tool> = AndroidTools.all(app)

    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    /** Ferramentas habilitadas agora; nada se o usuário escolheu o modo Observador ou Privado sem ações. */
    suspend fun enabledTools(): List<Tool> {
        // App que não responde não pode atrasar a conversa: no máximo 4 s para reunir as ferramentas MCP.
        val mcpTools = runCatching { withTimeoutOrNull(4_000) { mcp.tools { settings.mcpServerEnabled(it.id) } } }.getOrNull().orEmpty()
        return (native + mcpTools).filter { settings.toolEnabled(it.name) }
    }

    fun gateway(tools: List<Tool>) = ToolGateway(
        tools = tools,
        auditLog = audit,
        autonomy = { settings.autonomy },
        permissions = { PermissionState(androidGranted = true, jarvisGranted = settings.toolEnabled(it.name)) },
    )

    /** Mostra o pedido na tela e espera a resposta (90 s sem resposta = cancelado). */
    suspend fun confirm(tool: Tool, call: ToolCall): Boolean {
        val request = PendingConfirmation(
            title = tool.description.substringBefore(". ").removeSuffix("."),
            details = describeArguments(call.argumentsJson),
            critical = tool.risk == RiskLevel.CRITICAL,
        )
        _pending.value = request
        return try {
            withTimeoutOrNull(90_000) { request.answer.await() } ?: false
        } finally {
            _pending.update { if (it === request) null else it }
        }
    }

    private fun describeArguments(json: String): String = runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().joinToString("\n") { key -> "${key.replace('_', ' ')}: ${obj.get(key)}" }
    }.getOrDefault(json)

    companion object {
        fun parseNetworkServers(raw: String): List<McpServerInfo> = raw.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 2).map { it.trim() }
            if (parts.size != 2 || !(parts[1].startsWith("http://") || parts[1].startsWith("https://"))) return@mapNotNull null
            McpServerInfo(id = parts[0], name = parts[0], kind = McpServerInfo.Kind.NETWORK, address = parts[1])
        }
    }
}

/** Histórico de ações em arquivo (só acrescenta; item 48 da especificação). Guarda as 500 mais recentes na tela. */
class FileAuditLog(private val file: File) : AuditLog {
    private val state = MutableStateFlow(load())
    override val entries: StateFlow<List<AuditEntry>> = state.asStateFlow()

    override fun append(entry: AuditEntry) {
        state.update { (it + entry).takeLast(MAX) }
        runCatching {
            file.appendText(
                JSONObject()
                    .put("t", entry.timestampMillis)
                    .put("tool", entry.toolName)
                    .put("reason", entry.reason)
                    .put("outcome", entry.outcome)
                    .toString() + "\n",
            )
        }
    }

    private fun load(): List<AuditEntry> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().takeLast(MAX).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                AuditEntry(o.getLong("t"), o.getString("tool"), o.optString("reason"), o.optString("outcome"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 500
    }
}
