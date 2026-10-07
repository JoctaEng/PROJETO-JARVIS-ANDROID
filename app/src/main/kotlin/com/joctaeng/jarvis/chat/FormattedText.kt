package com.joctaeng.jarvis.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import org.json.JSONObject

/** Resposta com Markdown ou fórmula? Texto simples continua num Text comum, mais leve. */
fun needsFormatting(text: String): Boolean =
    Regex("[$*`|#]|\\\\[(\\[]|^\\s*([-•]|\\d+[.)])\\s", RegexOption.MULTILINE).containsMatchIn(text)

/**
 * Desenha a resposta com Markdown e fórmulas (assets/chat: marked + KaTeX + DOMPurify, tudo offline).
 * A página não acessa rede nem arquivos; links abrem no navegador.
 */
@OptIn(FlowPreview::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormattedText(text: String, textColor: Color, accent: Color, modifier: Modifier = Modifier) {
    var heightDp by remember { mutableIntStateOf(24) }
    var ready by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val current by rememberUpdatedState(text)
    val theme = remember(textColor, accent) {
        JSONObject()
            .put("fg", textColor.css())
            .put("muted", textColor.copy(alpha = 0.75f).css())
            .put("accent", accent.css())
            .put("code", textColor.copy(alpha = 0.10f).css())
            .toString()
    }

    LaunchedEffect(ready, theme) {
        val view = webView ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        // No streaming o texto muda a cada pedaço: redesenha no máximo ~7 vezes por segundo.
        view.render(current, theme)
        snapshotFlow { current }.sample(150).collect { view.render(it, theme) }
    }

    AndroidView(
        modifier = modifier.layout { measurable, constraints ->
            val h = heightDp.dp.roundToPx()
            val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
            layout(placeable.width, h) { placeable.place(0, 0) }
        },
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeight(css: Int) {
                            post { if (css > 0) heightDp = css + 2 }
                        }
                    },
                    "EunoBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        ready = true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "http" || uri.scheme == "https") {
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        return true
                    }
                }
                loadUrl("file:///android_asset/chat/bubble.html")
                webView = this
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun WebView.render(text: String, theme: String) {
    evaluateJavascript("window.eunoRender && eunoRender(${JSONObject.quote(text)}, $theme)", null)
}

private fun Color.css(): String =
    "rgba(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()},${"%.2f".format(java.util.Locale.US, alpha)})"
