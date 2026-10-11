package com.joctaeng.jarvis.conversation

import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.mind.intent.Finish
import com.joctaeng.jarvis.mind.intent.More
import com.joctaeng.jarvis.mind.intent.Plan
import com.joctaeng.jarvis.mind.intent.Reply
import com.joctaeng.jarvis.mind.intent.Step
import com.joctaeng.jarvis.mind.intent.StepResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executa o plano do motor pelas MESMAS ferramentas e pela mesma política (autonomia, confirmação, registro de auditoria)
 * que a IA usa: o motor só decide o que fazer; quem faz é o Tool Gateway.
 */
class EngineExecutor(private val app: JarvisApp, private val sessionId: String) {
    private var counter = 0
    private val alwaysYes: suspend (Tool, ToolCall) -> Boolean = { _, _ -> true }

    suspend fun execute(first: Plan, tools: List<Tool>, reason: String, onStep: (Step) -> Unit = {}): Reply {
        val gateway = app.toolbox.gateway(tools)
        var plan = first
        var rounds = 0
        while (true) {
            val results = mutableListOf<StepResult>()
            var broken = false
            for (step in plan.steps) {
                if (broken) {
                    results += StepResult(step, false, "não executei porque o passo anterior falhou")
                    continue
                }
                onStep(step)
                val r = runStep(gateway, step, reason)
                results += r
                if (!r.ok) broken = true
            }
            when (val next = plan.then(results)) {
                is Finish -> return next.reply
                is More -> {
                    if (++rounds > 4) return Reply("Parei por segurança: o pedido ficou longo demais.", "concerned")
                    plan = next.plan
                }
            }
        }
    }

    private suspend fun runStep(gateway: com.joctaeng.jarvis.action.gateway.ToolGateway, step: Step, reason: String): StepResult {
        if (step.settleMs > 0) delay(step.settleMs)
        var last: StepResult? = null
        repeat(1 + step.retries.coerceIn(0, 3)) { attempt ->
            if (attempt > 0) delay(1_300)
            val call = ToolCall("motor-${++counter}", step.tool, toJson(step.args))
            val confirm: suspend (Tool, ToolCall) -> Boolean = if (step.confirmed) alwaysYes else app.toolbox::confirm
            val result = gateway.execute(call, ToolContext(sessionId, "Motor do Euno: $reason"), confirm = confirm)
            val r = when (result) {
                is ToolResult.Success -> StepResult(step, true, result.outputJson)
                is ToolResult.Failure -> StepResult(step, false, result.reason)
                is ToolResult.Denied -> StepResult(step, false, "não permitido: ${result.reason}")
                ToolResult.Cancelled -> StepResult(step, false, "o usuário não confirmou")
            }
            app.events.info("motor", "${step.tool} → ${if (r.ok) "feito" else "falhou (${r.text.take(160)})"}")
            last = r
            // Cancelado ou negado não se repete: só falha de tela/carregamento.
            if (r.ok || result is ToolResult.Cancelled || result is ToolResult.Denied) return r
        }
        return last!!
    }

    private fun toJson(args: Map<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in args) when (v) {
            null -> Unit
            is List<*> -> o.put(k, JSONArray(v))
            else -> o.put(k, v)
        }
        return o.toString()
    }
}
