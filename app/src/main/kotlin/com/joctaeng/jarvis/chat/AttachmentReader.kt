package com.joctaeng.jarvis.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.joctaeng.jarvis.core.model.Attachment
import com.joctaeng.jarvis.core.model.AttachmentKind
import com.joctaeng.jarvis.mind.models.AttachmentTypes
import com.joctaeng.jarvis.mind.models.DocFormat
import com.joctaeng.jarvis.mind.models.DocumentText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Lê o que o usuário anexou pelo seletor do sistema. Tudo fica no aparelho: imagem e áudio são copiados para a pasta
 * privada do app (o modelo do celular abre o arquivo); PDF, DOCX, TXT e CSV viram texto aqui mesmo. PDF sem texto
 * (escaneado) vira imagens das primeiras páginas, para um modelo que lê imagens.
 */
class AttachmentReader(context: Context) {
    private val app = context.applicationContext
    val dir: File = File(app.filesDir, "anexos").apply { mkdirs() }

    sealed interface Result {
        data class Ok(val attachments: List<Attachment>) : Result
        data class Refused(val reason: String) : Result
    }

    suspend fun read(uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        var name = "anexo"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri).orEmpty()
        val kind = AttachmentTypes.kind(mime, name) ?: return@withContext Result.Refused("$name: tipo de arquivo que eu ainda não leio")
        if (size > AttachmentTypes.MAX_BYTES) return@withContext Result.Refused("$name tem ${size shr 20} MB; o limite é ${AttachmentTypes.MAX_BYTES shr 20} MB")
        prune()
        val ext = name.substringAfterLast('.', "bin").take(5).lowercase()
        val copy = File(dir, "${UUID.randomUUID()}.$ext")
        runCatching {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "não abriu" }
                copy.outputStream().use { out ->
                    val n = input.copyTo(out, 1 shl 16)
                    require(n <= AttachmentTypes.MAX_BYTES) { "arquivo grande demais" }
                }
            }
        }.onFailure {
            copy.delete()
            return@withContext Result.Refused("$name: não consegui abrir (${it.message})")
        }
        when (kind) {
            AttachmentKind.IMAGE, AttachmentKind.AUDIO -> Result.Ok(listOf(Attachment(kind, name, mime, path = copy.absolutePath)))
            AttachmentKind.DOCUMENT -> {
                val format = AttachmentTypes.docFormat(mime, name) ?: DocFormat.TEXT
                val text = runCatching { DocumentText.extract(format, copy.readBytes()) }.getOrDefault("")
                val doc = Attachment(kind, name, mime.ifBlank { "application/octet-stream" }, path = copy.absolutePath, text = text)
                if (format == DocFormat.PDF && text.isBlank()) {
                    // PDF escaneado: as páginas viram imagens (o modelo que lê imagens consegue; os outros avisam).
                    val pages = renderPdf(copy, name)
                    Result.Ok(listOf(doc.copy(text = "(PDF sem texto selecionável${if (pages.isNotEmpty()) "; mandei as ${pages.size} primeiras páginas como imagem" else ""})")) + pages)
                } else {
                    Result.Ok(listOf(doc))
                }
            }
        }
    }

    private fun renderPdf(file: File, name: String): List<Attachment> = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, MAX_PDF_PAGES)).map { i ->
                    pdf.openPage(i).use { page ->
                        val scale = (1280f / maxOf(page.width, page.height)).coerceAtMost(2f)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = File(dir, "${UUID.randomUUID()}.jpg")
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        bmp.recycle()
                        Attachment(AttachmentKind.IMAGE, "$name (página ${i + 1})", "image/jpeg", path = out.absolutePath)
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Guarda só os anexos dos últimos 3 dias (a conversa nova não precisa dos antigos). */
    private fun prune() {
        val limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    companion object {
        const val MAX_PDF_PAGES = 3
        val MIME_TYPES = arrayOf(
            "image/*", "audio/*", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/*", "application/json",
        )
    }
}
