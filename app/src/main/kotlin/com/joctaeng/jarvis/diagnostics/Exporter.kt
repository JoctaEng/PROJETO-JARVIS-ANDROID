package com.joctaeng.jarvis.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exporta textos grandes (relatório, conversa) como arquivo .txt e .pdf: salva em Downloads/Euno e abre o compartilhar. */
object Exporter {
    fun exportAndShare(context: Context, baseName: String, title: String, text: String, asPdf: Boolean) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        val name = "$baseName-$stamp." + if (asPdf) "pdf" else "txt"
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val file = File(dir, name)
        runCatching {
            if (asPdf) writePdf(file, title, text) else file.writeText(text)
        }.onFailure {
            Toast.makeText(context, "Não consegui criar o arquivo: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        val saved = saveToDownloads(context, file, if (asPdf) "application/pdf" else "text/plain")
        Toast.makeText(
            context,
            (if (saved) "Salvo em Downloads/Euno/$name" else "Não consegui salvar em Downloads; use Compartilhar") + " (${file.length() / 1024} KB)",
            Toast.LENGTH_LONG,
        ).show()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.arquivos", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (asPdf) "application/pdf" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Enviar $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun saveToDownloads(context: Context, file: File, mime: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Euno")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    /** PDF paginado em A4, fonte monoespaçada pequena, quebrando linhas longas. */
    private fun writePdf(file: File, title: String, text: String) {
        val pageW = 595
        val pageH = 842
        val margin = 36f
        val paint = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 7.5f; isAntiAlias = true }
        val lineH = 9.5f
        val maxChars = ((pageW - 2 * margin) / paint.measureText("M")).toInt().coerceAtLeast(20)
        val lines = buildList {
            add(title); add("")
            text.lineSequence().forEach { raw ->
                if (raw.length <= maxChars) add(raw) else raw.chunked(maxChars).forEach { add(it) }
            }
        }
        val perPage = ((pageH - 2 * margin) / lineH).toInt()
        val pdf = PdfDocument()
        try {
            lines.chunked(perPage).forEachIndexed { index, chunk ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create())
                chunk.forEachIndexed { i, line -> page.canvas.drawText(line, margin, margin + (i + 1) * lineH, paint) }
                pdf.finishPage(page)
            }
            if (lines.isEmpty()) pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()))
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }
}
