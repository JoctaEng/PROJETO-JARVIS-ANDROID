package com.joctaeng.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.system.resources.HumanPace
import com.joctaeng.jarvis.system.resources.ScreenSettle
import com.joctaeng.jarvis.system.resources.ScreenText
import com.joctaeng.jarvis.system.resources.UiNode
import com.joctaeng.jarvis.system.resources.WindowInfo
import com.joctaeng.jarvis.system.resources.WindowPick

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

    /**
     * A janela a ler: a de outro app mais à frente, nunca a do próprio Euno (o chat e a legenda ficam por cima e,
     * antes, o Euno lia a própria conversa). Se a lista de janelas não vier, cai na janela em foco, desde que não seja a dele.
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val own = if (allowOwnWindow) "" else packageName
        val wins = runCatching { windows }.getOrNull().orEmpty()
        val withRoot = wins.mapNotNull { w -> w.root?.let { w to it } }
        val infos = withRoot.map { (w, r) ->
            WindowInfo(r.packageName?.toString().orEmpty(), isApp = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, layer = w.layer, active = w.isActive, focused = w.isFocused)
        }
        WindowPick.pick(infos, own)?.let { return withRoot[it].second }
        val active = rootInActiveWindow
        return active?.takeIf { (own.isEmpty() || it.packageName?.toString() != own) && !it.packageName.isNullOrBlank() }
    }

    private fun collect(): List<Entry> {
        val root = targetRoot() ?: return emptyList()
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

    /** Pacote do app que está na frente (nunca o Euno), ou null. */
    fun foregroundPackage(): String? = targetRoot()?.packageName?.toString()

    /** O que está na tela agora, em texto (sem senhas), e o app que está na frente. */
    fun readScreen(): String {
        val root = targetRoot()
            ?: return "Não consegui ver outro app: só há janelas do Euno na tela. Abra o app que quer ler (abrir_app) e tente de novo."
        val pkg = root.packageName?.toString().orEmpty()
        val entries = collect()
        return "App lido: ${pkg.ifBlank { "desconhecido" }} (nunca a tela do Euno)\n" + ScreenText.render(entries.map { it.ui })
    }

    sealed interface Outcome {
        data class Done(val what: String) : Outcome
        data class Failed(val why: String) : Outcome

        /** O botão tem efeito real (enviar, pagar, apagar...): é preciso o usuário confirmar antes. */
        data class NeedsConfirmation(val label: String) : Outcome
    }

    /**
     * Toca no elemento que melhor combina com [query]. [confirmed] libera botões sensíveis (depois de o usuário confirmar).
     * Primeiro pede ao app (clique de acessibilidade); se ele recusar (ex.: o dia 15 da agenda, que só aceita toque de
     * dedo), toca com o dedo virtual no meio do elemento, como uma pessoa faria.
     */
    suspend fun tap(query: String, confirmed: Boolean): Outcome {
        val entries = collect()
        val i = ScreenText.bestMatch(entries.map { it.ui }, query)
            ?: return Outcome.Failed(
                if (entries.isEmpty()) "não há outro app na tela para tocar (só o Euno); abra o app antes"
                else "não achei \"$query\" na tela; use tela_ler (ou tela_ver, que olha o print da tela) e tente outro nome",
            )
        val target = entries[i]
        if (!confirmed && ScreenText.isSensitive(target.ui.label)) return Outcome.NeedsConfirmation(target.ui.label)
        return click(target)
    }

    private suspend fun click(target: Entry): Outcome {
        var node: AccessibilityNodeInfo? = target.node
        var hops = 0
        while (node != null && !node.isClickable && hops < 4) { node = node.parent; hops++ }
        var ok = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        var how = "pelo app"
        if (!ok) {
            val r = android.graphics.Rect()
            target.node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                ok = tapAt(r.exactCenterX(), r.exactCenterY())
                how = "com o dedo"
            }
        }
        JarvisApp.from(this).events.info("controle", "toque em \"${target.ui.label.take(40)}\" ($how): ${if (ok) "feito" else "não aceito"}")
        if (ok) waitSettled()
        return if (ok) Outcome.Done("toquei em \"${target.ui.label}\"") else Outcome.Failed("o app não aceitou o toque em \"${target.ui.label}\"; tente tela_ver e tela_tocar_ponto")
    }

    /**
     * Toca num botão cujo rótulo é EXATAMENTE um de [labels] (sem acento/maiúscula), ex.: "Salvar" da agenda.
     * Diferente de [tap], não aceita parecidos: evita tocar no título do evento que contenha a palavra.
     */
    suspend fun tapExact(labels: List<String>): Outcome {
        val wanted = labels.map { com.joctaeng.jarvis.tools.normalize(it) }
        val target = collect().firstOrNull { e -> com.joctaeng.jarvis.tools.normalize(e.ui.label) in wanted }
            ?: return Outcome.Failed("não achei ${labels.joinToString("/")} na tela")
        return click(target)
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

    /**
     * Rola/desliza como uma pessoa (pedido 65): um terço de tela por vez, sem pressa, esperando a tela parar antes de
     * devolver. [direction]: baixo, cima, proximo (direita→esquerda), anterior. Primeiro pede ao app (ação de rolar);
     * se ele não oferecer, usa o dedo virtual.
     */
    suspend fun swipe(direction: String): Outcome {
        val before = signature()
        val wanted = when (direction) {
            "proximo" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            "anterior" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "cima" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
        }
        pace()
        val node = collect().firstOrNull { e -> e.node.actionList.any { it.id == wanted.id } }?.node
        var how = "pelo app"
        var ok = node != null && node.performAction(wanted.id)
        if (!ok) {
            val m = resources.displayMetrics
            val p = HumanPace.swipePath(direction, m.widthPixels.toFloat(), m.heightPixels.toFloat())
            val path = android.graphics.Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]) }
            ok = gesture(path, HumanPace.SCROLL_GESTURE_MS)
            how = "com o dedo"
        }
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        if (ok) waitSettled(maxMs = 2_000)
        val moved = ok && signature() != before
        JarvisApp.from(this).events.info("controle", "rolar/deslizar \"$direction\" ($how): ${if (!ok) "não aceito" else if (moved) "feito" else "nada mudou"}")
        return when {
            !ok -> Outcome.Failed("o Android não aceitou o gesto; se for a primeira vez nesta versão, desligue e ligue o Euno em Acessibilidade")
            !moved -> Outcome.Done("rolei ($direction), mas a tela não mudou: provavelmente chegou ao fim nessa direção")
            else -> Outcome.Done("rolei ($direction)")
        }
    }

    /**
     * Rola com paciência até [query] aparecer (no máximo [max] vezes) ou a tela parar de mudar (fim da lista).
     * Evita que o cérebro dispare várias rolagens seguidas às cegas.
     */
    suspend fun scrollUntil(query: String, direction: String, max: Int = 12): Outcome {
        repeat(max) { n ->
            val found = ScreenText.bestMatch(collect().map { it.ui }, query)
            if (found != null) return Outcome.Done("achei \"$query\" na tela depois de rolar $n vez(es)")
            val before = signature()
            val r = swipe(direction)
            if (r is Outcome.Failed) return r
            if (signature() == before) return Outcome.Failed("rolei até o fim ($direction) e não achei \"$query\"")
        }
        return Outcome.Failed("rolei $max vezes ($direction) e não achei \"$query\"")
    }

    /** Toque com o dedo virtual num ponto da tela (pixels). */
    suspend fun tapAt(x: Float, y: Float): Boolean {
        pace()
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val ok = gesture(path, HumanPace.TAP_MS)
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        return ok
    }

    /** Toca no ponto em porcentagem da tela (0–100), como o print descreve. */
    suspend fun tapPercent(xPct: Double, yPct: Double): Outcome {
        val m = resources.displayMetrics
        val (x, y) = HumanPace.pointFromPercent(xPct, yPct, m.widthPixels, m.heightPixels)
        val ok = tapAt(x, y)
        JarvisApp.from(this).events.info("controle", "toque no ponto (${xPct.toInt()}%, ${yPct.toInt()}%): ${if (ok) "feito" else "não aceito"}")
        if (ok) waitSettled()
        return if (ok) Outcome.Done("toquei no ponto (${xPct.toInt()}%, ${yPct.toInt()}%)") else Outcome.Failed("o Android não aceitou o toque")
    }

    private var lastGestureAt = 0L

    /** Não dispara gestos em rajada: espera o intervalo mínimo de uma pessoa. */
    private suspend fun pace() {
        val wait = HumanPace.waitBeforeGesture(lastGestureAt, android.os.SystemClock.uptimeMillis())
        if (wait > 0) kotlinx.coroutines.delay(wait)
    }

    private suspend fun gesture(path: android.graphics.Path, durationMs: Long): Boolean {
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resumeWith(Result.success(true)) }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resumeWith(Result.success(false)) }
            }, null)
            if (!sent && cont.isActive) cont.resumeWith(Result.success(false))
        }
    }

    /** "Impressão digital" do que está na tela agora (0 = nada legível). */
    fun signature(): Int {
        val labels = collect().map { it.ui.label }.filter { it.isNotBlank() }
        if (labels.isEmpty()) return ScreenSettle.EMPTY
        return (labels.hashCode() * 31 + labels.size).let { if (it == ScreenSettle.EMPTY) 1 else it }
    }

    /** Espera a tela parar de mudar (app abrindo, lista rolando, animação) — no máximo [maxMs]. */
    suspend fun waitSettled(maxMs: Long = 3_000) {
        val settle = ScreenSettle(maxMs = maxMs)
        while (true) {
            if (settle.observe(signature(), android.os.SystemClock.uptimeMillis())) return
            kotlinx.coroutines.delay(150)
        }
    }

    /** Print da tela (Android 11+; precisa da permissão de captura do serviço). JPEG reduzido, ou null. */
    suspend fun screenshotJpeg(maxSide: Int = 1024): ByteArray? {
        val bmp = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            runCatching {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hw = runCatching { android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) }.getOrNull()
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resumeWith(Result.success(soft))
                    }

                    override fun onFailure(errorCode: Int) {
                        JarvisApp.from(this@EunoAccessibilityService).events.warn("controle", "print da tela falhou (código $errorCode)")
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            }.onFailure {
                JarvisApp.from(this).events.warn("controle", "print da tela indisponível: ${it.message}")
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        } ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return out.toByteArray()
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
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        return if (ok) Outcome.Done(action) else Outcome.Failed("o Android não aceitou \"$action\"")
    }

    companion object {
        /** Só para a tela "Testar funções": deixa as ferramentas agirem na área de teste do próprio Euno. */
        @Volatile var allowOwnWindow: Boolean = false

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
