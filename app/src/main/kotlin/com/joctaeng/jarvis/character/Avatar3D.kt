package com.joctaeng.jarvis.character

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.presence.expression.VrmFace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Avatar 3D (modelo VRM) desenhado por three.js + three-vrm numa WebView transparente. Tudo vem do próprio APK
 * (assets/avatar3d) ou do avatar importado pelo usuário (arquivos do app); nenhum pedido sai para a internet.
 * O estado do personagem (fala, boca, emoção, olhar) é enviado à página ~30 vezes por segundo.
 */
class Avatar3D(context: Context) {
    private val app = JarvisApp.from(context)
    private val appContext = context.applicationContext

    @Volatile var ready = false
        private set
    @Volatile var failed = false
        private set

    /** Avisado quando o modelo carrega (true) ou falha (false), na thread principal. */
    var onStatus: (Boolean) -> Unit = {}

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = LocalOnly()
        addJavascriptInterface(Bridge(), "EunoAvatar")
    }

    private var loadedModel = ""

    /**
     * Modelo a usar: o .vrm importado pelo usuário; senão o 3D do personagem escolhido (Joctã Casual, Luna...);
     * senão (ou se o usuário pediu) o avatar de exemplo.
     */
    private fun modelPath(): String {
        val custom = customFile(appContext)
        if (custom.isFile) return "files/${custom.name}"
        val id = app.settings.avatarModel
        return if (hasModel(appContext, id)) "models/$id.vrm" else "models/avatar.vrm"
    }

    fun load() {
        loadedModel = modelPath()
        ready = false
        failed = false
        last = ""
        view.loadUrl("https://$HOST/index.html?model=$loadedModel")
    }

    private var job: Job? = null
    private var last = ""

    /** Liga o avatar ao personagem: envia só quando algo muda. */
    fun drive(scope: CoroutineScope, renderer: ComposeCharacterRenderer) {
        job?.cancel()
        job = scope.launch {
            var framing = ""
            while (isActive) {
                // Trocou de personagem ou de modelo nos ajustes: recarrega o 3D sozinho.
                if (modelPath() != loadedModel) {
                    framing = ""
                    load()
                }
                if (ready) {
                    val f = app.settings.avatarFraming
                    if (f != framing) {
                        framing = f
                        view.evaluateJavascript("Avatar.setFraming('$f');", null)
                    }
                    val js = VrmFace.script(renderer.state, renderer.emotion, renderer.mouthViseme, renderer.mouthLevel, renderer.look.x, renderer.look.y)
                    if (js != last) {
                        last = js
                        view.evaluateJavascript(js, null)
                    }
                }
                delay(33)
            }
        }
    }

    /** Escondido (recolhido, desligado): para de desenhar para poupar bateria. */
    fun setActive(active: Boolean) {
        view.visibility = if (active) View.VISIBLE else View.GONE
        if (ready) view.evaluateJavascript("Avatar.setPaused(${!active});", null)
    }

    /**
     * Entra (cresce saindo do portal) ou sai (encolhe e afunda no portal) com animação, em vez de sumir de uma vez —
     * antes, ao dizer "tchau", o 3D sumia e o desenho 2D aparecia no lugar.
     */
    fun appear(visible: Boolean) {
        val v = view
        v.animate().cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height * 0.95f
        if (visible) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.scaleX = 0.25f
                v.scaleY = 0.25f
            }
            setActive(true)
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_MS).start()
        } else {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).setDuration(ANIM_MS).withEndAction { setActive(false) }.start()
        }
    }

    fun destroy() {
        job?.cancel()
        view.destroy()
    }

    /** Fotografa o avatar importado para usar a mesma imagem no chat e na tela inicial. */
    private fun saveCustomThumb() {
        if (!ready) return
        view.evaluateJavascript("Avatar.snapshot()") { raw ->
            val data = raw?.trim('"')?.substringAfter("base64,", "").orEmpty()
            if (data.isBlank()) return@evaluateJavascript
            runCatching {
                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                customThumb(appContext).writeBytes(bytes)
                app.settings.touch()
            }.onFailure { app.events.warn("avatar3d", "não salvei a miniatura do avatar: ${it.message}") }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(name: String) {
            ready = true
            app.events.info("avatar3d", "modelo 3D carregado: ${name.take(60)}")
            view.post {
                onStatus(true)
                if (loadedModel.startsWith("files/")) view.postDelayed({ saveCustomThumb() }, 1_500)
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            failed = true
            app.events.warn("avatar3d", "o avatar 3D não carregou: ${message.take(200)}; usando o personagem 2D")
            view.post { onStatus(false) }
        }
    }

    /** Serve a página, o código e o modelo de dentro do APK; qualquer outro endereço é recusado (sem internet). */
    private inner class LocalOnly : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            if (url.host != HOST) return notFound()
            val path = url.path.orEmpty().trimStart('/')
            val stream = when {
                path.startsWith("files/") -> {
                    val dir = customDir(appContext)
                    val f = File(dir, path.removePrefix("files/"))
                    if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f.inputStream() else null
                }
                path.contains("..") -> null
                else -> runCatching { appContext.assets.open("avatar3d/$path") }.getOrNull()
            } ?: return notFound()
            val mime = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".vrm") || path.endsWith(".glb") -> "model/gltf-binary"
                else -> "application/octet-stream"
            }
            val text = mime.startsWith("text/") || mime.endsWith("javascript")
            return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-cache"), stream)
        }

        private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    companion object {
        /** Endereço interno (não existe na internet: tudo é respondido pelo app). */
        const val HOST = "avatar.euno.local"
        private const val ANIM_MS = 420L

        fun customDir(context: Context) = File(context.filesDir, "avatar").apply { mkdirs() }
        fun customFile(context: Context) = File(customDir(context), "meu-avatar.vrm")

        /** Miniatura do avatar importado (tirada da própria WebView quando ele carrega). */
        fun customThumb(context: Context) = File(customDir(context), "meu-avatar-miniatura.png")

        /** Miniatura (rosto) de cada avatar 3D do APK: a mesma em Meu Euno, no chat e na tela inicial. */
        fun thumbRes(id: String): Int = when (id) {
            "guardiao" -> com.joctaeng.jarvis.R.drawable.thumb3d_guardiao
            "victoria" -> com.joctaeng.jarvis.R.drawable.thumb3d_victoria
            "vita" -> com.joctaeng.jarvis.R.drawable.thumb3d_vita
            "vivi" -> com.joctaeng.jarvis.R.drawable.thumb3d_vivi
            "shino" -> com.joctaeng.jarvis.R.drawable.thumb3d_shino
            "fumiriya" -> com.joctaeng.jarvis.R.drawable.thumb3d_fumiriya
            "clara" -> com.joctaeng.jarvis.R.drawable.thumb3d_clara
            else -> com.joctaeng.jarvis.R.drawable.thumb3d_avatar
        }

        /** Descrição curta do avatar 3D escolhido (para a tela inicial). */
        fun describe(context: Context, id: String): String =
            if (customFile(context).isFile) "Seu avatar 3D importado"
            else MODELS.firstOrNull { it.first == id }?.second?.substringAfter("(", "")?.removeSuffix(")")?.replaceFirstChar { it.uppercase() }.orEmpty()

        /**
         * Modelos 3D que vêm no APK (id do arquivo → descrição). CC0 (VRoid), licença VRM (pixiv) ou próprios do Euno
         * (Guardião: escultura gerada por IA a partir da ficha do usuário, montada no Blender; ver docs/ADR/0013).
         */
        val MODELS = listOf(
            "guardiao" to "Guardião (guerreiro de armadura e capa azul)",
            "avatar" to "Exemplo (cabelo castanho longo, camiseta branca)",
            "victoria" to "Victoria (loira, vestido rosa)",
            "vita" to "Vita (cabelo prateado, roupa azul)",
            "vivi" to "Vivi (cabelo castanho curto, vestido verde)",
            "shino" to "Shino (cabelo azul-escuro, uniforme)",
            "fumiriya" to "Fumiriya (rapaz, cabelo ruivo, uniforme)",
            "clara" to "Clara (cabelo preto, orelhinhas de gato, vestido branco)",
        )

        /** O modelo existe dentro do APK? */
        fun hasModel(context: Context, id: String): Boolean = "$id.vrm" in models(context)

        @Volatile private var modelList: Set<String>? = null

        /** Modelos que vêm no APK (lido uma vez por processo). */
        private fun models(context: Context): Set<String> = modelList
            ?: runCatching { context.assets.list("avatar3d/models")?.toSet() }.getOrNull().orEmpty().also { modelList = it }
    }
}
