package com.joctaeng.jarvis.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.control.EunoAccessibilityService
import com.joctaeng.jarvis.control.EunoAccessibilityService.Outcome
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import org.json.JSONObject

/** Controle do celular por acessibilidade: ler a tela, tocar, digitar, rolar e navegar. */
object ScreenTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(ScreenRead(app), ScreenTap(app), ScreenType(app), ScreenScroll(app), ScreenNav(app))
    }
}

private abstract class ScreenTool(
    protected val context: Context,
    final override val name: String,
    final override val description: String,
    final override val risk: RiskLevel,
    properties: String = "",
    required: List<String> = emptyList(),
) : Tool {
    final override val inputSchemaJson: String =
        """{"type":"object","properties":{$properties}${if (required.isEmpty()) "" else ",\"required\":" + org.json.JSONArray(required)}}"""

    final override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val app = JarvisApp.from(this.context)
        if (app.settings.privateMode) return ToolResult.Denied("Modo Privado ativo: não leio nem controlo a tela")
        if (!app.settings.phoneControl) {
            return ToolResult.Failure("O controle do celular está desligado. Peça ao usuário para ligar em Ajustes → Controle do celular.")
        }
        val service = EunoAccessibilityService.instance
        if (service == null && !EunoAccessibilityService.isDeclared(this.context)) {
            return ToolResult.Failure("Este APK de teste não inclui o serviço de acessibilidade; o controle do celular volta numa próxima versão.")
        }
        if (service == null) {
            runCatching {
                this.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return ToolResult.Failure(
                "O serviço de acessibilidade do Euno ainda não está ligado no Android. Abri as configurações de acessibilidade: " +
                    "diga ao usuário para ligar \"Euno - controle do celular\" e depois pedir de novo.",
            )
        }
        val args = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
        return run(service, args)
    }

    protected abstract fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult

    protected fun result(o: Outcome): ToolResult = when (o) {
        is Outcome.Done -> ToolResult.Success(JSONObject().put("feito", o.what).toString())
        is Outcome.Failed -> ToolResult.Failure(o.why)
        is Outcome.NeedsConfirmation -> ToolResult.Failure(
            "\"${o.label}\" tem efeito real (enviar, pagar, apagar...). Pergunte ao usuário se pode e só então repita com confirmado=true.",
        )
    }
}

private class ScreenRead(context: Context) : ScreenTool(
    context, "tela_ler",
    "Lê o que está na tela agora (textos, botões e campos; senhas nunca são lidas). Use antes de tocar, para ver os nomes dos botões, " +
        "e para 'o que está na minha tela?'.",
    RiskLevel.READ,
) {
    override fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult = ToolResult.Success(service.readScreen())
}

private class ScreenTap(context: Context) : ScreenTool(
    context, "tela_tocar",
    "Toca no botão ou item da tela pelo nome que aparece nele (veja os nomes com tela_ler). Para botões como enviar, pagar, comprar, " +
        "apagar, o usuário precisa confirmar antes: pergunte e só então use confirmado=true.",
    RiskLevel.WRITE_REVERSIBLE,
    """"nome":{"type":"string","description":"texto do botão/item"},"confirmado":{"type":"boolean","description":"true só depois de o usuário confirmar uma ação sensível"}""",
    listOf("nome"),
) {
    override fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val name = listOf("nome", "texto", "alvo", "botao").firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }
            ?: return ToolResult.Failure("informe o nome do botão")
        return result(service.tap(name, args.optBoolean("confirmado")))
    }
}

private class ScreenType(context: Context) : ScreenTool(
    context, "tela_digitar",
    "Escreve um texto no campo de texto da tela (o que está com o cursor, ou o primeiro). Nunca escreve em campo de senha. " +
        "Não envia: para enviar, toque no botão depois, com confirmação do usuário.",
    RiskLevel.WRITE_REVERSIBLE,
    """"texto":{"type":"string"}""", listOf("texto"),
) {
    override fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val text = args.optString("texto")
        if (text.isBlank()) return ToolResult.Failure("informe o texto")
        return result(service.type(text))
    }
}

private class ScreenScroll(context: Context) : ScreenTool(
    context, "tela_rolar", "Rola a tela para baixo ou para cima.", RiskLevel.WRITE_REVERSIBLE,
    """"direcao":{"type":"string","enum":["baixo","cima"]}""",
) {
    override fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult =
        result(service.scroll(forward = !args.optString("direcao").startsWith("c", ignoreCase = true)))
}

private class ScreenNav(context: Context) : ScreenTool(
    context, "tela_navegar",
    "Navegação do Android: voltar, ir para o início, abrir os apps recentes, as notificações ou as configurações rápidas.",
    RiskLevel.WRITE_REVERSIBLE,
    """"acao":{"type":"string","enum":["voltar","inicio","recentes","notificacoes","configuracoes_rapidas"]}""", listOf("acao"),
) {
    override fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult = result(service.system(args.optString("acao")))
}
