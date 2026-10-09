package com.joctaeng.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.system.resources.ScreenText
import com.joctaeng.jarvis.system.resources.UiNode

/**
 * Controle do celular. O Android só liga este serviço se o usuário o ativar em Configurações > Acessibilidade.
 * Ele não faz nada sozinho: só executa o que as ferramentas `tela_*` pedem (e o usuário pediu na conversa).
 * Senhas nunca são lidas; botões como enviar/pagar/apagar exigem confirmação (ver ScreenTools).
 */
class EunoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        JarvisApp.from(this).events.info("controle", "serviço de acessibilidade conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        JarvisApp.from(this).events.info("controle", "serviço de acessibilidade desconectado")
        return super.onUnbind(intent)
    }

    /** Elemento da tela junto com o nó do Android (para agir sobre ele). */
    private class Entry(val ui: UiNode, val node: AccessibilityNodeInfo)

    private fun collect(): List<Entry> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<Entry>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
            if (n.isVisibleToUser) {
                out += Entry(
                    UiNode(
                        text = n.text?.toString().orEmpty(),
                        description = n.contentDescription?.toString().orEmpty(),
                        clickable = n.isClickable || n.isCheckable,
                        editable = n.isEditable,
                        password = n.isPassword,
                        scrollable = n.isScrollable,
                    ),
                    n,
                )
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** O que está na tela agora, em texto (sem senhas), e o app que está na frente. */
    fun readScreen(): String {
        val entries = collect()
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        return "App na frente: ${pkg.ifBlank { "desconhecido" }}\n" + ScreenText.render(entries.map { it.ui })
    }

    sealed interface Outcome {
        data class Done(val what: String) : Outcome
        data class Failed(val why: String) : Outcome

        /** O botão tem efeito real (enviar, pagar, apagar...): é preciso o usuário confirmar antes. */
        data class NeedsConfirmation(val label: String) : Outcome
    }

    /** Toca no elemento que melhor combina com [query]. [confirmed] libera botões sensíveis (depois de o usuário confirmar). */
    fun tap(query: String, confirmed: Boolean): Outcome {
        val entries = collect()
        val i = ScreenText.bestMatch(entries.map { it.ui }, query)
            ?: return Outcome.Failed("não achei \"$query\" na tela; use tela_ler para ver o que há")
        val target = entries[i]
        if (!confirmed && ScreenText.isSensitive(target.ui.label)) return Outcome.NeedsConfirmation(target.ui.label)
        var node: AccessibilityNodeInfo? = target.node
        var hops = 0
        while (node != null && !node.isClickable && hops < 4) { node = node.parent; hops++ }
        val ok = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        JarvisApp.from(this).events.info("controle", "toque em \"${target.ui.label.take(40)}\": ${if (ok) "feito" else "não aceito"}")
        return if (ok) Outcome.Done("toquei em \"${target.ui.label}\"") else Outcome.Failed("o app não aceitou o toque em \"${target.ui.label}\"")
    }

    /** Escreve no campo que está com o cursor (ou no primeiro campo de texto da tela). Nunca em campo de senha. */
    fun type(text: String): Outcome {
        val entries = collect()
        val field = entries.firstOrNull { it.node.isFocused && it.ui.editable }
            ?: entries.firstOrNull { it.ui.editable && !it.ui.password }
            ?: return Outcome.Failed("não há campo de texto na tela")
        if (field.ui.password) return Outcome.Failed("campo de senha: não escrevo em senhas")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        JarvisApp.from(this).events.info("controle", "digitar (${text.length} caracteres): ${if (ok) "feito" else "não aceito"}")
        return if (ok) Outcome.Done("escrevi ${text.length} caracteres no campo") else Outcome.Failed("o campo não aceitou o texto")
    }

    /** Rola a primeira área rolável da tela. */
    fun scroll(forward: Boolean): Outcome {
        val target = collect().firstOrNull { it.ui.scrollable } ?: return Outcome.Failed("não há nada para rolar")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (target.node.performAction(action)) Outcome.Done(if (forward) "rolei para baixo" else "rolei para cima") else Outcome.Failed("não deu para rolar mais")
    }

    fun system(action: String): Outcome {
        val code = when (action) {
            "voltar" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recentes" -> GLOBAL_ACTION_RECENTS
            "notificacoes" -> GLOBAL_ACTION_NOTIFICATIONS
            "configuracoes_rapidas" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return Outcome.Failed("ação desconhecida: $action")
        }
        val ok = performGlobalAction(code)
        JarvisApp.from(this).events.info("controle", "navegação \"$action\": ${if (ok) "feito" else "não aceito"}")
        return if (ok) Outcome.Done(action) else Outcome.Failed("o Android não aceitou \"$action\"")
    }

    companion object {
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 30

        /** O serviço conectado agora, ou null se o usuário não o ligou. */
        @Volatile var instance: EunoAccessibilityService? = null
            private set

        /** Este APK declara o serviço no manifesto? (a v0.13.1 de teste não declara, para isolar uma falha de instalação) */
        fun isDeclared(context: Context): Boolean = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES).services
                ?.any { it.name == EunoAccessibilityService::class.java.name } == true
        }.getOrDefault(false)

        /** O serviço está ligado nas configurações do Android? (mesmo antes de conectar neste processo) */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.endsWith("EunoAccessibilityService") }
        }
    }
}
