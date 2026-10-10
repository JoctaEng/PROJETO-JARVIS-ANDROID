package com.joctaeng.jarvis.system.resources

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmailMessageTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun readsHeadersAndPlainBody() {
        val raw = """{"id":"abc","snippet":"Sua fatura chegou","payload":{"mimeType":"multipart/alternative","headers":[
            {"name":"From","value":"Banco <fatura@banco.com>"},{"name":"Subject","value":"Fatura de outubro"},{"name":"Date","value":"Fri, 10 Oct 2026"}],
            "parts":[{"mimeType":"text/plain","body":{"data":"${b64("Valor: R$ 1.234,56\nVencimento 15/10")}"}},
                     {"mimeType":"text/html","body":{"data":"${b64("<p>html</p>")}"}}]}}"""
        val m = GmailMessage.parse(raw)
        assertEquals("abc", m.id)
        assertEquals("Fatura de outubro", m.subject)
        assertTrue("R$ 1.234,56" in m.body)
        assertTrue("[id abc]" in m.shortLine())
    }

    @Test fun fallsBackToHtml() {
        val raw = """{"id":"x","payload":{"mimeType":"text/html","headers":[],"body":{"data":"${b64("<html><body><p>Olá &amp; bem-vindo</p></body></html>")}"}}}"""
        val m = GmailMessage.parse(raw)
        assertEquals("(sem assunto)", m.subject)
        assertTrue("Olá & bem-vindo" in m.body)
    }
}
