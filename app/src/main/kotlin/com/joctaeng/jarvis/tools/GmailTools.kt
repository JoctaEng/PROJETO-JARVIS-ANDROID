package com.joctaeng.jarvis.tools

import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.system.resources.GmailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gmail em segundo plano (pedido 72): o usuário entra com a conta Google em Meu Euno → Gmail e permite SÓ LEITURA.
 * O token fica com o Google Play Services (o Euno não guarda senha). Enviar e-mail continua só pela tela, com confirmação.
 */
object GmailAuth {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Pede o token sem mostrar nada (já autorizado antes). Null = precisa conectar em Meu Euno. */
    suspend fun silentToken(context: Context): Result<String?> = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { r: AuthorizationResult ->
                if (cont.isActive) cont.resumeWith(Result.success(Result.success(if (r.hasResolution()) null else r.accessToken)))
            }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWith(Result.success(Result.failure(e))) }
    }
}

object GmailTools {
    fun all(context: Context): List<Tool> = listOf(GmailSearch(context), GmailRead(context))
}

private suspend fun gmailGet(context: Context, path: String): Result<String> {
    val token = GmailAuth.silentToken(context).getOrElse { return Result.failure(IllegalStateException("o Google recusou o acesso: ${it.message}")) }
        ?: return Result.failure(IllegalStateException("o Gmail ainda não foi conectado: peça ao usuário para abrir Meu Euno → Gmail → Conectar"))
    return withContext(Dispatchers.IO) {
        val conn = (URL("https://gmail.googleapis.com/gmail/v1/users/me/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Result.failure(IllegalStateException("o Gmail respondeu ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()?.take(160)}"))
            } else Result.success(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }
}

private class GmailSearch(context: Context) : BackgroundTool(
    context, "gmail_buscar",
    "Procura e-mails no Gmail do usuário EM SEGUNDO PLANO (só leitura), com a busca do Gmail (ex.: 'fatura', 'from:banco is:unread', " +
        "'newer_than:7d'). Devolve remetente, assunto, data, trecho e id. Para ler um inteiro, use gmail_ler.",
    RiskLevel.READ,
    """"consulta":{"type":"string","description":"busca do Gmail; vazio = mais recentes"},"quantos":{"type":"integer","minimum":1,"maximum":15}""",
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val q = text(args, "consulta", "query", "busca")
        val n = args.optInt("quantos", 8).coerceIn(1, 15)
        val list = gmailGet(context, "messages?maxResults=$n" + if (q.isBlank()) "" else "&q=" + Uri.encode(q))
            .getOrElse { return ToolResult.Failure(it.message ?: "falha no Gmail") }
        val ids = JSONObject(list).optJSONArray("messages")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") } }.orEmpty()
        if (ids.isEmpty()) return ToolResult.Success("Nenhum e-mail encontrado${if (q.isBlank()) "" else " para \"$q\""}.")
        // Busca de metadados em paralelo ( From, Subject, Date ) para não baixar corpos full nem bloquear em série.
        val lines = coroutineScope {
            ids.map { id ->
                async(Dispatchers.IO) {
                    val path = "messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
                    gmailGet(context, path).getOrNull()?.let { runCatching { GmailMessage.parse(it, maxBody = 0).shortLine() }.getOrNull() }
                }
            }.awaitAll().filterNotNull()
        }
        JarvisApp.from(context).events.info("ferramentas", "gmail_buscar: ${lines.size} e-mails (paralelo)")
        return ToolResult.Success(lines.joinToString("\n"))
    }
}

private class GmailRead(context: Context) : BackgroundTool(
    context, "gmail_ler", "Lê um e-mail inteiro do Gmail pelo id (de gmail_buscar), em segundo plano.", RiskLevel.READ,
    """"id":{"type":"string"}""", listOf("id"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val id = text(args, "id")
        if (id.isBlank()) return ToolResult.Failure("informe o id (veja com gmail_buscar)")
        val raw = gmailGet(context, "messages/${Uri.encode(id)}?format=full").getOrElse { return ToolResult.Failure(it.message ?: "falha no Gmail") }
        val m = GmailMessage.parse(raw)
        return ToolResult.Success(JSONObject().put("de", m.from).put("assunto", m.subject).put("data", m.date).put("texto", m.body).toString())
    }
}
