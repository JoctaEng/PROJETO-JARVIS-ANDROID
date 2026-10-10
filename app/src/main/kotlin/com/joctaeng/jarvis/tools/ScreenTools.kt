package com.joctaeng.jarvis.tools

import android.content.Context
import android.content.Intent
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.control.EunoAccessibilityService
import com.joctaeng.jarvis.control.EunoAccessibilityService.Outcome
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.overlay.OverlayBus
import org.json.JSONObject

/** Controle do celular por acessibilidade: ler a tela, olhar o print, tocar, digitar, rolar e navegar. */

/** Tempo para o Euno ir para o canto antes da 1ª ação na tela. */
private const val ACTING_SETTLE_MS = 450L
object ScreenTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(ScreenRead(app), ScreenLook(app), ScreenTap(app), ScreenTapPoint(app), ScreenType(app), ScreenScroll(app), ScreenSwipe(app), ScreenNav(app))
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
                com.joctaeng.jarvis.control.AccessibilityLink.open(this.context)
            }
            app.events.warn("controle", "tela_*: serviço ${if (EunoAccessibilityService.isEnabled(this.context)) "ligado no Android mas NÃO conectado ao Euno" else "desligado no Android"}")
            return ToolResult.Failure(
                if (EunoAccessibilityService.isEnabled(this.context)) {
                    "O serviço de acessibilidade do Euno está LIGADO no Android, mas não está conectado ao app agora (acontece depois de fechar o Euno por completo ou de atualizá-lo). " +
                        "Abri a página dele: peça ao usuário para DESLIGAR e LIGAR de novo \"Euno - controle do celular\" e depois pedir de novo."
                } else {
                    "O serviço de acessibilidade do Euno está desligado no Android. Abri a página dele: peça ao usuário para ligar \"Euno - controle do celular\" e depois pedir de novo."
                },
            )
        }
        if (!OverlayBus.acting.value) {
            // Se encolhe para o canto ANTES de agir (pedido 65): senão o 1º toque/leitura pega o Euno ainda no meio da tela.
            OverlayBus.acting.value = true
            kotlinx.coroutines.delay(ACTING_SETTLE_MS)
        }
        val args = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
        return run(service, args)
    }

    protected abstract suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult

    protected fun result(o: Outcome): ToolResult = when (o) {
        is Outcome.Done -> ToolResult.Success(JSONObject().put("feito", o.what).toString())
        is Outcome.Failed -> ToolResult.Failure(o.why)
        is Outcome.NeedsConfirmation -> ToolResult.Failure("\"${o.label}\" precisa de confirmação do usuário.")
    }
}

private class ScreenRead(context: Context) : ScreenTool(
    context, "tela_ler",
    "Lê o que está na tela agora (textos, botões e campos; senhas nunca são lidas). Espera o app terminar de carregar. " +
        "Use antes de tocar, para ver os nomes dos botões, e para 'o que está na minha tela?'.",
    RiskLevel.READ,
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        service.waitSettled(maxMs = 2_500)
        return ToolResult.Success(service.readScreen())
    }
}

private class ScreenLook(context: Context) : ScreenTool(
    context, "tela_ver",
    "Tira um print da tela e OLHA a imagem (visão): diz o que aparece e onde fica cada elemento, em % da largura e da altura. " +
        "Use quando tela_ler não bastar (ícones sem nome, calendário, imagens, mapas) ou quando um toque pelo nome falhar; depois use tela_tocar_ponto.",
    RiskLevel.READ,
    """"pergunta":{"type":"string","description":"o que você procura na tela (ex.: o dia 15, o botão de salvar)"}""",
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val app = JarvisApp.from(context)
        service.waitSettled(maxMs = 2_500)
        val jpeg = service.screenshotJpeg()
            ?: return ToolResult.Failure(
                "não consegui tirar o print da tela. Se for a primeira vez nesta versão, o Android pede para desligar e ligar " +
                    "\"Euno - controle do celular\" em Acessibilidade (permissão nova de captura). Enquanto isso, use tela_ler.",
            )
        val text = runCatching { service.readScreen() }.getOrDefault("")
        return runCatching { ScreenVision.look(app, jpeg, args.optString("pergunta"), text) }
            .fold(
                onSuccess = {
                    app.events.info("controle", "tela_ver: print de ${jpeg.size / 1024} KB descrito (${it.length} car.)")
                    ToolResult.Success(JSONObject().put("visao", it).toString())
                },
                onFailure = {
                    app.events.warn("controle", "tela_ver falhou: ${it.message}")
                    ToolResult.Failure("não consegui olhar o print: ${it.message}. Use tela_ler.")
                },
            )
    }
}

private class ScreenTapPoint(context: Context) : ScreenTool(
    context, "tela_tocar_ponto",
    "Toca num ponto da tela em porcentagem (x da esquerda, y de cima, de 0 a 100), com as posições que tela_ver devolveu. " +
        "Use quando tela_tocar pelo nome não funcionar.",
    RiskLevel.WRITE_REVERSIBLE,
    """"x":{"type":"number","description":"0-100, da esquerda"},"y":{"type":"number","description":"0-100, de cima"}""",
    listOf("x", "y"),
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        fun num(k: String): Double? = args.opt(k)?.toString()?.trim()?.removeSuffix("%")?.replace(',', '.')?.toDoubleOrNull()
        val x = num("x") ?: return ToolResult.Failure("informe x (0-100)")
        val y = num("y") ?: return ToolResult.Failure("informe y (0-100)")
        return result(service.tapPercent(x, y))
    }
}

private class ScreenTap(context: Context) : ScreenTool(
    context, "tela_tocar",
    "Toca no botão ou item da tela pelo nome que aparece nele (veja os nomes com tela_ler). Em botões como enviar, " +
        "pagar/Pix, excluir ou desinstalar, o Euno mostra um pedido de confirmação ao usuário sozinho; os demais toques são diretos.",
    RiskLevel.WRITE_REVERSIBLE,
    """"nome":{"type":"string","description":"texto do botão/item"}""",
    listOf("nome"),
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val name = listOf("nome", "texto", "alvo", "botao").firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }
            ?: return ToolResult.Failure("informe o nome do botão")
        val first = service.tap(name, confirmed = false)
        if (first !is Outcome.NeedsConfirmation) return result(first)
        // Pedido real na tela: o modelo não consegue "se confirmar" sozinho.
        val call = com.joctaeng.jarvis.core.model.ToolCall("tela", this.name, JSONObject().put("tocar no botão", first.label).toString())
        val yes = JarvisApp.from(context).toolbox.confirm(this@ScreenTap, call)
        return if (yes) result(service.tap(name, confirmed = true)) else ToolResult.Denied("o usuário não confirmou \"${first.label}\"")
    }
}

private class ScreenType(context: Context) : ScreenTool(
    context, "tela_digitar",
    "Escreve um texto no campo de texto da tela (o que está com o cursor, ou o primeiro). Nunca escreve em campo de senha. " +
        "Não envia: para enviar, toque no botão depois, com confirmação do usuário.",
    RiskLevel.WRITE_REVERSIBLE,
    """"texto":{"type":"string"}""", listOf("texto"),
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val text = args.optString("texto")
        if (text.isBlank()) return ToolResult.Failure("informe o texto")
        return result(service.type(text))
    }
}

private class ScreenScroll(context: Context) : ScreenTool(
    context, "tela_rolar",
    "Rola a tela no ritmo de uma pessoa (um pedaço por vez, esperando carregar): baixo, cima, direita ou esquerda. " +
        "Com 'procurar', continua rolando sozinho até o texto aparecer ou a lista acabar — use isso em vez de rolar várias vezes.",
    RiskLevel.WRITE_REVERSIBLE,
    """"direcao":{"type":"string","enum":["baixo","cima","direita","esquerda"]},"procurar":{"type":"string","description":"texto a achar rolando (opcional)"}""",
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val d = com.joctaeng.jarvis.tools.normalize(args.optString("direcao"))
        val direction = when {
            d.startsWith("c") -> "cima"
            d.startsWith("dir") || d.startsWith("prox") -> "proximo"
            d.startsWith("esq") || d.startsWith("ant") -> "anterior"
            else -> "baixo"
        }
        val find = args.optString("procurar").trim()
        return result(if (find.isNotEmpty()) service.scrollUntil(find, direction) else service.swipe(direction))
    }
}

private class ScreenSwipe(context: Context) : ScreenTool(
    context, "tela_deslizar",
    "Desliza para a próxima ('proximo') ou anterior ('anterior') página/semana/mês/foto, no ritmo de uma pessoa e esperando carregar. " +
        "Use para 'passa para a próxima semana', 'volta um mês'.",
    RiskLevel.WRITE_REVERSIBLE,
    """"direcao":{"type":"string","enum":["proximo","anterior","baixo","cima"]}""", listOf("direcao"),
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult {
        val d = com.joctaeng.jarvis.tools.normalize(args.optString("direcao"))
        val direction = when {
            d.startsWith("prox") || d.startsWith("esq") || d.startsWith("avan") || d.startsWith("frente") -> "proximo"
            d.startsWith("ant") || d.startsWith("dir") || d.startsWith("volt") || d.startsWith("tras") -> "anterior"
            d.startsWith("c") -> "cima"
            d.startsWith("b") -> "baixo"
            else -> return ToolResult.Failure("direcao deve ser proximo, anterior, baixo ou cima")
        }
        return result(service.swipe(direction))
    }
}

private class ScreenNav(context: Context) : ScreenTool(
    context, "tela_navegar",
    "Navegação do Android: voltar, ir para o início, abrir os apps recentes, as notificações ou as configurações rápidas.",
    RiskLevel.WRITE_REVERSIBLE,
    """"acao":{"type":"string","enum":["voltar","inicio","recentes","notificacoes","configuracoes_rapidas"]}""", listOf("acao"),
) {
    override suspend fun run(service: EunoAccessibilityService, args: JSONObject): ToolResult = result(service.system(args.optString("acao")))
}
