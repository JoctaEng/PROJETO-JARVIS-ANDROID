package com.joctaeng.jarvis.mind.models

import com.joctaeng.jarvis.core.model.Attachment
import com.joctaeng.jarvis.core.model.AttachmentKind
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test
    fun `tipo pelo mime ou pela extensao`() {
        assertEquals(AttachmentKind.IMAGE, AttachmentTypes.kind("image/jpeg", "x"))
        assertEquals(AttachmentKind.IMAGE, AttachmentTypes.kind(null, "foto.PNG"))
        assertEquals(AttachmentKind.AUDIO, AttachmentTypes.kind("audio/ogg", "nota"))
        assertEquals(AttachmentKind.DOCUMENT, AttachmentTypes.kind("application/pdf", "a"))
        assertEquals(DocFormat.DOCX, AttachmentTypes.docFormat("application/octet-stream", "carta.docx"))
        assertEquals(DocFormat.CSV, AttachmentTypes.docFormat("text/csv", "x"))
        assertEquals(DocFormat.TEXT, AttachmentTypes.docFormat("text/plain", "x"))
        assertNull(AttachmentTypes.kind("application/zip", "a.zip"))
    }

    @Test
    fun `o que falta para ler`() {
        val img = Attachment(AttachmentKind.IMAGE, "f.jpg", "image/jpeg", path = "/x")
        val aud = Attachment(AttachmentKind.AUDIO, "a.ogg", "audio/ogg", path = "/y")
        assertNull(AttachmentTypes.missing(listOf(img, aud), vision = true, audio = true))
        assertEquals("ler imagens", AttachmentTypes.missing(listOf(img), vision = false, audio = true))
        assertEquals("ler imagens e ouvir áudios", AttachmentTypes.missing(listOf(img, aud), vision = false, audio = false))
    }

    @Test
    fun `texto do documento entra no pedido com teto`() {
        val doc = Attachment(AttachmentKind.DOCUMENT, "conta.txt", "text/plain", text = "a".repeat(100))
        val p = AttachmentTypes.promptText("resuma", listOf(doc), 40)
        assertTrue(p.startsWith("resuma\n\n[Arquivo anexado: conta.txt]\n"))
        assertTrue(p.contains("cortado"))
        val img = Attachment(AttachmentKind.IMAGE, "f.jpg", "image/jpeg", path = "/x")
        assertTrue(AttachmentTypes.promptText("", listOf(img), 100).endsWith("[Imagem anexada: f.jpg]"))
    }

    @Test
    fun `txt em latin-1 e utf-8 com BOM`() {
        assertEquals("Olá", DocumentText.decode("﻿Olá".toByteArray()))
        assertEquals("Ação", DocumentText.decode("Ação".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `csv com ponto e virgula e aspas`() {
        val csv = "data;descrição;valor\n01/10;\"Mercado; feira\";12,50\n"
        assertEquals("data | descrição | valor\n01/10 | Mercado; feira | 12,50", DocumentText.extract(DocFormat.CSV, csv.toByteArray()))
    }

    @Test
    fun `docx vira paragrafos`() {
        val xml = """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>""" +
            """<w:p><w:r><w:t>Fatura de outubro</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t xml:space="preserve">Total: </w:t></w:r><w:r><w:t>R$ 1.234,56 &amp; juros</w:t></w:r><w:r><w:tab/><w:t>ok</w:t></w:r></w:p>""" +
            "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<x/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        assertEquals("Fatura de outubro\nTotal: R$ 1.234,56 & juros\tok", DocumentText.extract(DocFormat.DOCX, out.toByteArray()))
    }

    private fun pdf(content: ByteArray, flate: Boolean): ByteArray {
        val data = if (flate) {
            val d = Deflater(); d.setInput(content); d.finish()
            val buf = ByteArray(content.size * 2 + 64); val n = d.deflate(buf); d.end(); buf.copyOf(n)
        } else content
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n1 0 obj\n<< /Length ${data.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun `pdf com texto simples e comprimido`() {
        val content = "BT /F1 12 Tf 72 700 Td (Extrato de Outubro) Tj 0 -14 Td [(Saldo:) -250 (R$ 10,00)] TJ T* (Fim \\(ok\\)) Tj ET"
            .toByteArray(Charsets.ISO_8859_1)
        val expected = "Extrato de Outubro\nSaldo: R$ 10,00\nFim (ok)"
        assertEquals(expected, DocumentText.extract(DocFormat.PDF, pdf(content, flate = false)))
        assertEquals(expected, DocumentText.extract(DocFormat.PDF, pdf(content, flate = true)))
    }

    @Test
    fun `pdf com acento octal e hexa`() {
        val content = "BT (Ol\\341) Tj T* <0041004200430044> Tj ET".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Olá\nABCD", DocumentText.extract(DocFormat.PDF, pdf(content, flate = true)))
    }

    @Test
    fun `pdf escaneado ou ilegivel volta vazio`() {
        assertEquals("", DocumentText.extract(DocFormat.PDF, "não é pdf".toByteArray()))
        val glyphs = "BT <0102030405060708> Tj ET".toByteArray(Charsets.ISO_8859_1)
        assertEquals("", DocumentText.extract(DocFormat.PDF, pdf(glyphs, flate = false)))
    }
}

class NativeToolsTest {
    private val prompt = "Regras\n## Ferramentas\nUse <tool_call>…\n<tools>\n" +
        """{"name":"abrir_app","description":"Abre um app","parameters":{"type":"object","properties":{"nome":{"type":"string"}}}}""" + "\n" +
        """{"name":"lanterna","description":"Liga","parameters":{}}""" + "\n</tools>\nDepois"

    @Test
    fun `tira o bloco e devolve as ferramentas`() {
        val s = NativeTools.split(prompt)
        assertEquals(listOf("abrir_app", "lanterna"), s.tools.map { it.name })
        assertTrue(!s.prompt.contains("<tools>") && s.prompt.contains("Ferramentas: abrir_app, lanterna.") && s.prompt.endsWith("Depois"))
        assertEquals(
            """{"name":"abrir_app","description":"Abre um app","parameters":{"type":"object","properties":{"nome":{"type":"string"}}}}""",
            s.tools.first().openApiJson(),
        )
    }

    @Test
    fun `sem bloco nada muda`() {
        val s = NativeTools.split("só conversa")
        assertEquals("só conversa", s.prompt)
        assertTrue(s.tools.isEmpty())
    }

    @Test
    fun `chamada nativa vira texto do protocolo`() {
        val args = NativeTools.argumentsJson(mapOf("nome" to "Gmail", "n" to 2, "ok" to true, "lista" to listOf("a"), "x" to null))
        assertEquals("""{"nome":"Gmail","n":2,"ok":true,"lista":["a"],"x":null}""", args)
        assertEquals("""<tool_call>{"name":"abrir_app","arguments":{"nome":"Gmail"}}</tool_call>""", NativeTools.protocolText("abrir_app", """{"nome":"Gmail"}"""))
    }
}
