package com.joctaeng.jarvis.poc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Modelos locais (`.gguf` para o llama.cpp, `.litertlm` para o LiteRT-LM) em Android/data/com.joctaeng.jarvis/files/models.
 * Entram por download no app, importação (seletor de arquivos) ou copiando direto para a pasta (adb push).
 */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    val directory: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models").apply { mkdirs() }

    fun list(): List<File> =
        directory.listFiles { f -> f.isFile && EXTENSIONS.any { f.name.endsWith(it) } }.orEmpty().sortedBy { it.name }

    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        var name = "modelo.litertlm"
        var total = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) total = c.getLong(1)
            }
        }
        require(EXTENSIONS.any { name.endsWith(it) }) { "Escolha um arquivo .gguf ou .litertlm (recebido: $name)" }
        val target = File(directory, name)
        val temp = File(directory, "$name.part")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir o arquivo" }
            temp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (copied - lastReport > 32L shl 20) {
                        lastReport = copied
                        onProgress(copied, total)
                    }
                }
                onProgress(copied, total)
            }
        }
        check(temp.renameTo(target)) { "Falha ao salvar ${target.name}" }
        target
    }

    fun delete(file: File) {
        if (file.parentFile == directory) file.delete()
    }

    companion object {
        val EXTENSIONS = listOf(".gguf", ".litertlm")

        fun isGguf(file: File): Boolean = file.name.endsWith(".gguf")
    }
}
