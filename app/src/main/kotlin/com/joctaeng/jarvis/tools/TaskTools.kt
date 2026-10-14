package com.joctaeng.jarvis.tools

import android.content.Context
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.mind.memory.Repeat
import com.joctaeng.jarvis.mind.memory.ScheduledTask
import com.joctaeng.jarvis.mind.memory.TaskSchedule
import com.joctaeng.jarvis.system.resources.AgendaRequest
import com.joctaeng.jarvis.tasks.TaskScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tarefas programadas (v0.28): criar, listar, pausar e apagar rotinas que rodam sozinhas no horário. */
object TaskTools {
    fun all(context: Context): List<Tool> = listOf(TaskCreate(context), TaskList(context), TaskPause(context), TaskRemove(context))
}

private val hm = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun nextText(task: ScheduledTask): String =
    TaskSchedule.next(task, Instant.now(), ZoneId.systemDefault())?.atZone(ZoneId.systemDefault())?.format(hm) ?: "nenhuma (desligada ou já rodou)"

private class TaskCreate(context: Context) : BackgroundTool(
    context, "tarefa_criar",
    "Cria uma TAREFA PROGRAMADA: no horário, o Euno faz o 'pedido' sozinho e avisa o resultado por notificação. Use para " +
        "'todo dia às 7h me diga a agenda e o clima', 'dias úteis às 18h leia as notificações', 'sexta às 20h lembre de…'. " +
        "O 'pedido' é escrito como o usuário falaria (pode citar skills). Enviar/pagar/apagar continua pedindo confirmação. " +
        "Pedidos de resumo (agenda, e-mails, clima) são executados de verdade no horário e falados em voz alta.",
    RiskLevel.WRITE_REVERSIBLE,
    """"nome":{"type":"string"},"pedido":{"type":"string","description":"o que fazer, como o usuário pediria"},""" +
        """"hora":{"type":"integer","minimum":0,"maximum":23},"minuto":{"type":"integer","minimum":0,"maximum":59},""" +
        """"repetir":{"type":"string","enum":["todo_dia","dias_uteis","dias","uma_vez"]},""" +
        """"dias":{"type":"array","items":{"type":"string"},"description":"para repetir=dias: seg, ter, qua, qui, sex, sab, dom"},""" +
        """"data":{"type":"string","description":"para uma_vez: DD/MM"},"falar":{"type":"boolean","description":"também falar o resultado em voz alta"}""",
    listOf("nome", "pedido", "hora"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val app = JarvisApp.from(context)
        val pedido = text(args, "pedido", "instrucao", "acao")
        if (pedido.isBlank()) return ToolResult.Failure("informe o pedido da tarefa")
        if (!args.has("hora")) return ToolResult.Failure("informe a hora (0–23)")
        val repeat = when (text(args, "repetir").lowercase()) {
            "dias_uteis", "dias úteis", "uteis" -> Repeat.WEEKDAYS
            "dias" -> Repeat.DAYS
            "uma_vez", "uma vez", "once" -> Repeat.ONCE
            else -> Repeat.DAILY
        }
        val days = args.optJSONArray("dias")?.let { arr -> (0 until arr.length()).mapNotNull { TaskSchedule.dayNumber(arr.optString(it)) }.toSet() }.orEmpty()
        if (repeat == Repeat.DAYS && days.isEmpty()) return ToolResult.Failure("diga os dias (ex.: seg, qua, sex)")
        val date = text(args, "data").takeIf { it.isNotBlank() }?.let {
            AgendaRequest.parseDate(it, Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()) ?: return ToolResult.Failure("data não entendida; use DD/MM")
        }
        val task = app.tasks.add(
            ScheduledTask(
                id = "", name = text(args, "nome").ifBlank { pedido.take(40) }, instruction = pedido,
                hour = args.optInt("hora").coerceIn(0, 23), minute = args.optInt("minuto", 0).coerceIn(0, 59),
                // "me acorde", "me diga", "resumo": quem pede isso quer ouvir (teste de 14/10: a tarefa só avisava em silêncio).
                repeat = repeat, days = days, date = date,
                speak = args.optBoolean("falar", false) || Regex("""(?i)\b(acord|despert|fal[ae]|diga|leia|resum|bom dia)""").containsMatchIn(pedido + " " + text(args, "nome")),
            ),
        )
        TaskScheduler.schedule(context, task)
        app.events.info("tarefas", "criada \"${task.name}\" (${TaskSchedule.describe(task)})")
        return ok("criada" to task.name, "quando" to TaskSchedule.describe(task), "proxima" to nextText(task))
    }
}

private class TaskList(context: Context) : BackgroundTool(
    context, "tarefa_listar", "Lista as tarefas programadas: nome, quando roda, próxima vez e se está ligada.", RiskLevel.READ,
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val arr = JSONArray()
        JarvisApp.from(context).tasks.all().forEach { t ->
            arr.put(JSONObject().put("nome", t.name).put("pedido", t.instruction).put("quando", TaskSchedule.describe(t)).put("ligada", t.enabled).put("proxima", nextText(t)))
        }
        return ok("tarefas" to arr, "total" to arr.length())
    }
}

private class TaskPause(context: Context) : BackgroundTool(
    context, "tarefa_pausar", "Liga ou desliga uma tarefa programada (pelo nome). Com nome='todas' e ligar=false, para todas.",
    RiskLevel.WRITE_REVERSIBLE, """"nome":{"type":"string"},"ligar":{"type":"boolean"}""", listOf("nome", "ligar"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val app = JarvisApp.from(context)
        val on = args.optBoolean("ligar", false)
        val name = text(args, "nome")
        if (!on && name.lowercase() in setOf("todas", "tudo", "todas as tarefas")) {
            app.tasks.disableAll()
            app.tasks.all().forEach { TaskScheduler.cancel(context, it.id) }
            return ok("paradas" to "todas")
        }
        val t = app.tasks.find(name) ?: return ToolResult.Failure("não achei a tarefa \"$name\"")
        app.tasks.setEnabled(t.id, on)
        app.tasks.get(t.id)?.let { TaskScheduler.schedule(context, it) }
        return ok("tarefa" to t.name, "ligada" to on)
    }
}

private class TaskRemove(context: Context) : BackgroundTool(
    context, "tarefa_apagar", "Apaga uma tarefa programada (pelo nome).", RiskLevel.WRITE_REVERSIBLE, """"nome":{"type":"string"}""", listOf("nome"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val app = JarvisApp.from(context)
        val t = app.tasks.remove(text(args, "nome")) ?: return ToolResult.Failure("não achei essa tarefa")
        TaskScheduler.cancel(context, t.id)
        return ok("apagada" to t.name)
    }
}
