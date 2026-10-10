package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlTextTest {
    @Test fun extractsReadableText() {
        val html = """<html><head><title>Notícia &amp; Fatos</title><style>p{color:red}</style><script>var x=1;</script></head>
            <body><nav>Menu Início Contato</nav><h1>Título</h1><p>Primeiro par&aacute;grafo.</p><p>Segundo&nbsp;texto.</p>
            <footer>rodapé</footer></body></html>"""
        val page = HtmlText.extract(html)
        assertEquals("Notícia & Fatos", page.title)
        assertTrue("Primeiro parágrafo." in page.text && "Segundo texto." in page.text)
        assertFalse("var x" in page.text || "color" in page.text || "Menu" in page.text || "rodapé" in page.text)
    }

    @Test fun capsLength() {
        val page = HtmlText.extract("<p>" + "a ".repeat(5000) + "</p>", maxChars = 100)
        assertTrue(page.cut && page.text.length <= 100)
    }
}
