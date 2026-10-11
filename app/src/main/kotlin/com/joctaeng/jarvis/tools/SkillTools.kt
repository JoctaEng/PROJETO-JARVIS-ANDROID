package com.joctaeng.jarvis.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** Ferramentas das skills (v0.27): listar, ensinar e apagar habilidades; e o "Não perturbe" que os modos aula/foco/dormir usam. */
object SkillTools {
    fun all(context: Context): List<Tool> = listOf(SkillList(context), SkillTeach(context), SkillRemove(context), DoNotDisturb(context))
}

private class SkillList(context: Context) : BackgroundTool(
    context, "skill_listar", "Lista as skills (habilidades) do Euno: as prontas e as que o usuário ensinou, com o comando de cada uma.",
    RiskLevel.READ, """"categoria":{"type":"string","description":"opcional: Professor, Agenda, Dia a dia, Comunicação, Informação, Finanças, Minhas"}""",
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val cat = text(args, "categoria").lowercase()
        val list = JarvisApp.from(context).skills.all().filter { cat.isBlank() || it.category.lowercase().startsWith(cat) }
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("nome", s.name).put("categoria", s.category).put("comando", s.triggers.first()).put("ligada", s.enabled).put("ensinada", !s.builtIn))
        }
        return ok("skills" to arr, "total" to list.size)
    }
}

private class SkillTeach(context: Context) : BackgroundTool(
    context, "skill_ensinar",
    "Ensina uma skill nova ao Euno: quando o usuário disser o 'comando', o Euno segue os 'passos'. Use quando ele pedir " +
        "'aprenda a…', 'quando eu disser X faça Y', 'cria uma rotina'. Escreva os passos citando as ferramentas (ex.: nao_perturbe, agenda_consultar).",
    RiskLevel.WRITE_REVERSIBLE,
    """"comando":{"type":"string","description":"a frase curta que ativa (ex.: bom dia professor)"},"passos":{"type":"string"},"nome":{"type":"string"}""",
    listOf("comando", "passos"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val app = JarvisApp.from(context)
        if (app.settings.privateMode) return ToolResult.Failure("Modo Privado ativo: não guardo skills novas agora")
        val skill = runCatching { app.skills.teach(text(args, "comando", "gatilho"), text(args, "passos", "acoes"), text(args, "nome")) }
            .getOrElse { return ToolResult.Failure("não deu para ensinar: ${it.message}") }
        app.events.info("skills", "aprendida pela ferramenta: \"${skill.triggers.first()}\"")
        return ok("aprendida" to skill.name, "comando" to skill.triggers.first(), "passos" to skill.steps)
    }
}

private class SkillRemove(context: Context) : BackgroundTool(
    context, "skill_apagar", "Apaga uma skill que o usuário ensinou (pelo nome ou comando). As prontas não se apagam: desligue em Minhas skills.",
    RiskLevel.WRITE_REVERSIBLE, """"nome":{"type":"string"}""", listOf("nome"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val removed = JarvisApp.from(context).skills.remove(text(args, "nome", "comando"))
            ?: return ToolResult.Failure("não achei uma skill ensinada com esse nome (as prontas só se desligam em Meu Euno → Minhas skills)")
        return ok("apagada" to removed.name)
    }
}

private class DoNotDisturb(context: Context) : BackgroundTool(
    context, "nao_perturbe", "Liga ou desliga o Não perturbe do celular (modo aula, foco, dormir). Ligado deixa passar só as prioridades.",
    RiskLevel.WRITE_REVERSIBLE, """"ligar":{"type":"boolean"}""", listOf("ligar"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return ToolResult.Failure("Ainda sem acesso ao Não perturbe. Abri a página: diga ao usuário para ligar o Euno ali e pedir de novo.")
        }
        val on = args.optBoolean("ligar", true)
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        val now = nm.currentInterruptionFilter
        val isOn = now != NotificationManager.INTERRUPTION_FILTER_ALL
        return if (isOn == on) ok("nao_perturbe" to if (on) "ligado" else "desligado") else ToolResult.Failure("o Android não mudou o Não perturbe")
    }
}
