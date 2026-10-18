package com.joctaeng.jarvis.mind.models

import com.joctaeng.jarvis.core.model.Attachment
import com.joctaeng.jarvis.core.model.AttachmentKind
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/** Formato de documento que o Euno lê sozinho (sem internet). */
enum class DocFormat { PDF, DOCX, TEXT, CSV }

/** Que tipo de anexo é, pelo tipo MIME do sistema ou, se ele não disser, pela extensão. */
object AttachmentTypes {
    /** Teto de texto extraído que vai ao cérebro do celular (janela pequena) e ao online. */
    const val LOCAL_TEXT_CHARS = 6_000
    const val CLOUD_TEXT_CHARS = 40_000
    /** Arquivos maiores que isto não são lidos (protege a memória do celular). */
    const val MAX_BYTES = 25L shl 20

    fun kind(mime: String?, name: String): AttachmentKind? {
        val m = mime.orEmpty().lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp") -> AttachmentKind.IMAGE
            m.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "ogg", "opus", "aac", "flac", "amr") -> AttachmentKind.AUDIO
            docFormat(mime, name) != null -> AttachmentKind.DOCUMENT
            else -> null
        }
    }

    fun docFormat(mime: String?, name: String): DocFormat? {
        val m = mime.orEmpty().lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m == "application/pdf" || ext == "pdf" -> DocFormat.PDF
            m == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext == "docx" -> DocFormat.DOCX
            m == "text/csv" || m == "text/comma-separated-values" || ext == "csv" -> DocFormat.CSV
            m.startsWith("text/") || m == "application/json" || ext in setOf("txt", "md", "json", "log", "xml", "html", "htm") -> DocFormat.TEXT
            else -> null
        }
    }

    /** O que vai junto do texto do usuário para o cérebro (o conteúdo dos documentos, com teto). */
    fun promptText(userText: String, attachments: List<Attachment>, maxChars: Int): String {
        val docs = attachments.filter { it.kind == AttachmentKind.DOCUMENT }
        val media = attachments.filter { it.kind != AttachmentKind.DOCUMENT }
        if (docs.isEmpty() && media.isEmpty()) return userText
        return buildString {
            append(userText.ifBlank { "Leia o anexo e me diga do que se trata." })
            val per = if (docs.isEmpty()) 0 else maxChars / docs.size
            docs.forEach { d ->
                append("\n\n[Arquivo anexado: ").append(d.name).append("]\n")
                val text = d.text.orEmpty()
                if (text.isBlank()) append("(não consegui tirar texto deste arquivo)") else append(DocumentText.limit(text, per))
            }
            media.forEach { a ->
                append("\n\n[").append(if (a.kind == AttachmentKind.IMAGE) "Imagem anexada: " else "Áudio anexado: ").append(a.name).append("]")
            }
        }
    }

    /** O que falta para o cérebro escolhido ler os anexos (null = consegue). */
    fun missing(attachments: List<Attachment>, vision: Boolean, audio: Boolean): String? {
        val needImage = attachments.any { it.kind == AttachmentKind.IMAGE }
        val needAudio = attachments.any { it.kind == AttachmentKind.AUDIO }
        return when {
            needImage && !vision && needAudio && !audio -> "ler imagens e ouvir áudios"
            needImage && !vision -> "ler imagens"
            needAudio && !audio -> "ouvir áudios"
            else -> null
        }
    }
}

/** Extração de texto local de documentos: tudo no aparelho, nada vai para a internet. */
object DocumentText {
    fun extract(format: DocFormat, bytes: ByteArray): String = when (format) {
        DocFormat.TEXT -> decode(bytes)
        DocFormat.CSV -> csv(decode(bytes))
        DocFormat.DOCX -> docx(bytes)
        DocFormat.PDF -> PdfText.extract(bytes)
    }.trim()

    /** Corta com aviso (o cérebro precisa saber que não viu tudo). */
    fun limit(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n[…cortado: o arquivo tem ${text.length} caracteres; mostrei ${max}]"

    /** UTF-8; se não for UTF-8 válido, Latin-1 (arquivos antigos do Windows). Tira o BOM. */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
        return text.removePrefix("﻿")
    }

    /** CSV em linhas "a | b | c" (separador vírgula ou ponto e vírgula, aspas respeitadas). */
    fun csv(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val sep = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        return lines.joinToString("\n") { line -> splitCsv(line, sep).joinToString(" | ") { it.trim() } }
    }

    fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /** DOCX = ZIP com word/document.xml: parágrafos viram linhas; tabulação e quebra são mantidas. */
    fun docx(bytes: ByteArray): String {
        var xml: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
        }
        val doc = xml ?: return ""
        val out = StringBuilder()
        val token = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab\s*/>|<w:br\s*/>|</w:p>|<w:t(?:\s[^>]*)?/>""")
        token.findAll(doc).forEach { m ->
            val v = m.value
            when {
                v.startsWith("<w:tab") -> out.append('\t')
                v.startsWith("<w:br") || v == "</w:p>" -> out.append('\n')
                m.groupValues[1].isNotEmpty() -> out.append(unescapeXml(m.groupValues[1]))
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    fun unescapeXml(s: String): String = s
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#([0-9]+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/**
 * Leitor de texto de PDF simples e sem dependências: abre os fluxos de conteúdo (FlateDecode ou sem filtro) e junta o
 * texto dos operadores Tj/TJ/'/". Funciona com PDFs gerados por editores comuns (Word, Docs, bancos); PDF escaneado ou com
 * fonte sem mapa de caracteres volta vazio, e o app avisa (ou manda as páginas como imagem a um modelo que lê imagens).
 */
object PdfText {
    fun extract(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        if (!raw.startsWith("%PDF")) return ""
        val out = StringBuilder()
        var from = 0
        while (true) {
            val s = raw.indexOf("stream", from).takeIf { it >= 0 } ?: break
            if (s >= 3 && raw.regionMatches(s - 3, "end", 0, 3)) { from = s + 6; continue }
            var dataStart = s + 6
            if (raw.getOrNull(dataStart) == '\r') dataStart++
            if (raw.getOrNull(dataStart) == '\n') dataStart++
            val end = raw.indexOf("endstream", dataStart).takeIf { it >= 0 } ?: break
            val dictStart = raw.lastIndexOf("<<", s).coerceAtLeast(0)
            val dict = raw.substring(dictStart, s)
            from = end + 9
            if (dict.contains("/Image") || dict.contains("/FontFile") || dict.contains("/Length1") || dict.contains("/XRef") || dict.contains("/ObjStm")) continue
            val data = bytes.copyOfRange(dataStart, end)
            val content = when {
                dict.contains("/FlateDecode") -> inflate(data) ?: continue
                dict.contains("/Filter") -> continue
                else -> data
            }
            val text = contentText(String(content, Charsets.ISO_8859_1))
            if (text.isNotBlank()) out.append(text).append('\n')
        }
        val result = out.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (readable(result)) result else ""
    }

    /** Texto que parece gente escrevendo (não lixo de fonte sem mapa). */
    fun readable(text: String): Boolean {
        if (text.length < 4) return false
        val good = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?()-/%\$€'\"" }
        return good.toDouble() / text.length >= 0.75
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inf = Inflater()
        inf.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        var guard = 0
        while (!inf.finished() && guard++ < 100_000) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    /** Interpreta só o necessário dos operadores de texto. */
    fun contentText(c: String): String {
        val out = StringBuilder()
        val operands = ArrayList<Any>()
        var i = 0
        fun newline() { if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '(' -> { val (s, next) = literal(c, i); operands += s; i = next; continue }
                ch == '<' && c.getOrNull(i + 1) == '<' -> { i += 2; continue }
                ch == '>' && c.getOrNull(i + 1) == '>' -> { i += 2; continue }
                ch == '<' -> { val close = c.indexOf('>', i).takeIf { it > 0 } ?: c.length; operands += hex(c.substring(i + 1, close)); i = close + 1; continue }
                ch == '[' -> { operands += "["; i++; continue }
                ch == ']' -> {
                    val idx = operands.lastIndexOf("[")
                    val arr = if (idx >= 0) operands.subList(idx + 1, operands.size).toList() else emptyList()
                    if (idx >= 0) while (operands.size > idx) operands.removeAt(operands.size - 1)
                    operands.add(arr)
                    i++; continue
                }
                ch == '%' -> { while (i < c.length && c[i] != '\n' && c[i] != '\r') i++; continue }
                ch.isWhitespace() -> { i++; continue }
            }
            val start = i
            while (i < c.length && !c[i].isWhitespace() && c[i] !in "()<>[]/%") i++
            if (i == start) { i++; continue }
            val word = c.substring(start, i)
            if (word.toDoubleOrNull() != null) { operands += word.toDouble(); continue }
            when (word) {
                "Tj" -> (operands.lastOrNull() as? String)?.let { out.append(it) }
                "'", "\"" -> { newline(); (operands.lastOrNull() as? String)?.let { out.append(it) } }
                "TJ" -> (operands.lastOrNull() as? List<*>)?.forEach { part ->
                    when (part) {
                        is String -> out.append(part)
                        is Double -> if (part < -180 && out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    }
                }
                "Td", "TD" -> {
                    val ty = operands.getOrNull(operands.size - 1) as? Double ?: 0.0
                    val tx = operands.getOrNull(operands.size - 2) as? Double ?: 0.0
                    if (ty != 0.0) newline() else if (tx > 1 && out.isNotEmpty() && out.last() != ' ') out.append(' ')
                }
                "T*", "ET" -> newline()
                "Tm" -> newline()
            }
            operands.clear()
        }
        return out.toString()
    }

    private fun literal(c: String, open: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 1
        var i = open + 1
        while (i < c.length && depth > 0) {
            val ch = c[i]
            when (ch) {
                '\\' -> {
                    val n = c.getOrNull(i + 1)
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r', 'b', 'f' -> Unit
                        '(', ')', '\\' -> sb.append(n)
                        '\r', '\n' -> Unit
                        in '0'..'7' -> {
                            var j = i + 1
                            val oct = StringBuilder()
                            while (j < c.length && oct.length < 3 && c[j] in '0'..'7') { oct.append(c[j]); j++ }
                            sb.append(oct.toString().toInt(8).toChar())
                            i = j
                            continue
                        }
                        null -> Unit
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                '(' -> { depth++; sb.append(ch) }
                ')' -> { depth--; if (depth > 0) sb.append(ch) }
                else -> sb.append(ch)
            }
            i++
        }
        return winAnsi(sb.toString()) to i
    }

    /** Hexa: 1 byte por letra; 2 bytes (fonte Identity) só quando o 1º byte é 0 (ASCII), senão é glifo sem mapa. */
    private fun hex(h: String): String {
        val clean = h.filter { it.isLetterOrDigit() }.let { if (it.length % 2 == 1) it + "0" else it }
        val b = clean.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (b.size >= 2 && b.size % 2 == 0 && b.chunked(2).all { it[0] == 0 }) return b.chunked(2).map { it[1].toChar() }.joinToString("")
        if (b.any { it < 9 }) return ""
        return winAnsi(b.map { it.toChar() }.joinToString(""))
    }

    /** WinAnsi ≈ Latin-1 nas letras do português; corrige aspas e travessões da faixa 0x80–0x9F. */
    private fun winAnsi(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            append(
                when (ch.code) {
                    0x91, 0x92 -> '\''
                    0x93, 0x94 -> '"'
                    0x96, 0x97 -> '-'
                    0x95 -> '•'
                    0x80 -> '€'
                    else -> ch
                },
            )
        }
    }
}
