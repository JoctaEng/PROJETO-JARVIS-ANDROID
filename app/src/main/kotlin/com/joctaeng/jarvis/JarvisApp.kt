package com.joctaeng.jarvis

import android.app.Application
import android.content.Context
import com.joctaeng.jarvis.diagnostics.Diagnostics
import com.joctaeng.jarvis.poc.ModelStore

/**
 * Composição manual de dependências na Fase 0 (ver docs/ADR/0003-di-manual-na-fase-0.md).
 */
class JarvisApp : Application() {
    lateinit var diagnostics: Diagnostics
        private set
    lateinit var modelStore: ModelStore
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(this)
        modelStore = ModelStore(this)
    }

    companion object {
        fun from(context: Context): JarvisApp = context.applicationContext as JarvisApp
    }
}
