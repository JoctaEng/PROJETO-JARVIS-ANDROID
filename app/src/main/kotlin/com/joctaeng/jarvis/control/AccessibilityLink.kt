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
}
