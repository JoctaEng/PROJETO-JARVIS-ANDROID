package com.joctaeng.jarvis.device

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Atalhos para as telas de configuração que o usuário precisa visitar.
 *
 * As telas específicas da Xiaomi (MIUI/HyperOS) não são API pública: os nomes de
 * componentes abaixo são os usados pela comunidade e podem mudar entre versões.
 * Por isso cada atalho cai para a tela de detalhes do app se falhar.
 */
object SystemSettings {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) || Build.BRAND.equals("POCO", ignoreCase = true)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openOverlayPermission(context: Context) = launch(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
    )

    @SuppressLint("BatteryLife") // App pessoal (sideload): a exceção de bateria é essencial ao overlay.
    fun requestIgnoreBatteryOptimizations(context: Context) = launch(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )

    fun openAppDetails(context: Context) {
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    /** Xiaomi: "Inicialização automática". */
    fun openXiaomiAutostart(context: Context) = launch(
        context,
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
    )

    /** Xiaomi: "Outras permissões" (inclui abrir janelas em segundo plano e exibir na tela de bloqueio). */
    fun openXiaomiOtherPermissions(context: Context) = launch(
        context,
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
    )

    private fun launch(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
