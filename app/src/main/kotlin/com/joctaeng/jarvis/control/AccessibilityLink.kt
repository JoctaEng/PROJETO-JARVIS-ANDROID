package com.joctaeng.jarvis.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Abre direto a página do serviço do Euno em Acessibilidade (o Android não deixa um app se ligar sozinho). */
object AccessibilityLink {
    fun intent(context: Context): Intent {
        val component = ComponentName(context, EunoAccessibilityService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra("android.intent.extra.COMPONENT_NAME", component)
        return if (details.resolveActivity(context.packageManager) != null) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /**
     * Abre a página. Em alguns Androids (ex.: HyperOS) a página de detalhes exige uma permissão de sistema e dá
     * SecurityException; aí cai para a lista geral de Acessibilidade em vez de fechar o app.
     */
    fun open(context: Context) {
        val flags = if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent(context).addFlags(flags))
        } catch (e: Exception) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags)) }
        }
    }
}
