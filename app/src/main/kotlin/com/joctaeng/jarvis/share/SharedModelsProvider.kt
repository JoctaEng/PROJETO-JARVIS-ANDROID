package com.joctaeng.jarvis.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.joctaeng.jarvis.mind.models.ModelCatalog
import com.joctaeng.jarvis.mind.models.SharedModels
import com.joctaeng.jarvis.poc.ModelIntegrity
import com.joctaeng.jarvis.poc.ModelStore
import com.joctaeng.jarvis.settings.AppSettings
import java.io.File
import java.io.FileNotFoundException

/**
 * Porta SOMENTE LEITURA dos modelos já baixados no Euno para o Joca Finanças (contrato em docs/CONTRATO-MODELOS-JOCA.md):
 * - `content://com.joctaeng.jarvis.modelos/modelos` → lista (id, nome, arquivo, motor, tamanho, sha256, capacidades, ativo…);
 * - `content://com.joctaeng.jarvis.modelos/arquivo/<id>` → o arquivo do modelo, aberto só para leitura ("r").
 *
 * Segurança: quem chama é identificado pelo UID que o próprio Android informa (não pelo que o app diz ser) e só os
 * pacotes de [SharedModels.ALLOWED_PACKAGES] passam; o usuário pode desligar em Catálogo de modelos → Joca Finanças.
 * Nada é gravado, apagado ou alterado por aqui (insert/update/delete recusam). Chaves, memórias e conversas não passam.
 */
class SharedModelsProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(SharedModels.AUTHORITY, SharedModels.PATH_MODELS, LIST)
        addURI(SharedModels.AUTHORITY, "${SharedModels.PATH_FILE}/*", FILE)
    }

    // Criados sob demanda: o provedor nasce antes do Application.onCreate.
    private val catalog by lazy { ModelCatalog.bundled() }
    private val store by lazy { ModelStore(requireContext()) }
    private val settings by lazy { AppSettings(requireContext()) }

    override fun onCreate(): Boolean = true

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = requireContext().packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        if (!SharedModels.allowed(packages)) throw SecurityException("Euno: app sem permissão para ler os modelos")
        if (!settings.shareModelsWithJoca) throw SecurityException("Euno: o usuário desligou o compartilhamento de modelos")
    }

    private fun installed(): List<SharedModels.Installed> {
        val files = store.list()
        val byName = files.associateBy { it.name }
        val active = settings.localModelPath.takeIf { it.isNotBlank() }?.let { File(it).name }
        return SharedModels.installed(catalog, files.map { it.name to it.length() }, active) { name ->
            byName[name]?.let { ModelIntegrity.remembered(it) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        if (matcher.match(uri) != LIST) throw IllegalArgumentException("Euno: endereço desconhecido $uri")
        val cursor = MatrixCursor(SharedModels.COLUMNS)
        installed().forEach { cursor.addRow(SharedModels.row(it)) }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if (mode != "r") throw SecurityException("Euno: os modelos só abrem para leitura")
        if (matcher.match(uri) != FILE) throw FileNotFoundException("Euno: endereço desconhecido $uri")
        val id = uri.lastPathSegment.orEmpty()
        val item = installed().firstOrNull { it.model.id == id } ?: throw FileNotFoundException("Euno: modelo $id não está baixado")
        val file = File(store.directory, item.model.file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        LIST -> "vnd.android.cursor.dir/vnd.com.joctaeng.jarvis.modelo"
        FILE -> "application/octet-stream"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("somente leitura")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("somente leitura")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("somente leitura")

    private companion object {
        const val LIST = 1
        const val FILE = 2
    }
}
