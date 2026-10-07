package com.joctaeng.jarvis

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.joctaeng.jarvis.conversation.ConversationController
import com.joctaeng.jarvis.diagnostics.Diagnostics
import com.joctaeng.jarvis.mind.memory.MemoryStore
import com.joctaeng.jarvis.poc.ModelDownload
import com.joctaeng.jarvis.tools.Toolbox
import com.joctaeng.jarvis.poc.ModelStore
import com.joctaeng.jarvis.settings.AppSettings
import com.joctaeng.jarvis.settings.SecretStore
import com.joctaeng.jarvis.voice.VoiceOutput
import java.io.File

/**
 * Composição manual de dependências (ver docs/ADR/0003-di-manual-na-fase-0.md).
 */
class JarvisApp : Application() {
    lateinit var diagnostics: Diagnostics
        private set
    lateinit var modelStore: ModelStore
    lateinit var modelDownload: ModelDownload
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var secrets: SecretStore
        private set
    val memory: MemoryStore by lazy { MemoryStore(File(filesDir, "memory/memory.json")) }
    val voice: VoiceOutput by lazy { VoiceOutput(this, settings) { secrets.get(SecretStore.CLOUD_API_KEY) }.also { it.start() } }
    val conversation: ConversationController by lazy { ConversationController(this) }
    val toolbox: Toolbox by lazy { Toolbox(this) }

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(this)
        modelStore = ModelStore(this)
        modelDownload = ModelDownload(this, modelStore)
        settings = AppSettings(this)
        secrets = SecretStore(this)
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW: ainda entregue em Android 13/14.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Regra 3.3: aviso de memória baixa descarrega o modelo local na hora.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) conversation.releaseLocalModel()
    }

    companion object {
        fun from(context: Context): JarvisApp = context.applicationContext as JarvisApp
    }
}
