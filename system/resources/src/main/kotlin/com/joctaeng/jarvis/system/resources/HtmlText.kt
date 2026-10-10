package com.joctaeng.jarvis.system.resources

/** Página da web → texto limpo para o cérebro ler (sem scripts, estilos, menus de código), com teto de tamanho. */
object HtmlText {
    data class Page(val title: String, val text: String, val cut: Boolean)

    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&ccedil;" to "ç", "&atilde;" to "ã", "&otilde;" to "õ", "&aacute;" to "á", "&eacute;" to "é", "&iacute;" to "í",
        "&oacute;" to "ó", "&uacute;" to "ú", "&acirc;" to "â", "&ecirc;" to "ê", "&ocirc;" to "ô", "&agrave;" to "à",
    )

    fun extract(html: String, maxChars: Int = 6_000): Page {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        var body = html
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        body = clean(body)
        val lines = body.lines().map { it.trim() }.filter { it.length > 1 }
        val text = lines.joinToString("\n")
        return Page(title, text.take(maxChars), text.length > maxChars)
    }

    private fun clean(s: String): String {
        var out = s
        entities.forEach { (k, v) -> out = out.replace(k, v, ignoreCase = true) }
        out = Regex("&#(\\d+);").replace(out) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        return out.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }
}
