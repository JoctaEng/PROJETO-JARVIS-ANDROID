package com.joctaeng.jarvis.diagnostics

import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.system.resources.ErrorReport
import java.io.File
import java.security.MessageDigest

/**
 * Junta tudo que ajuda a achar um problema: versão, aparelho, assinatura do APK, configuração (sem chaves),
 * o registro de eventos/erros/quedas, as medições e as últimas ações das ferramentas. Nenhuma conversa é incluída.
 */
class ReportCollector(private val app: JarvisApp) {
    fun build(maxChars: Int = 90_000): String {
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            "${info.availMem / 1_048_576} MB livres de ${info.totalMem / 1_048_576} MB" + if (info.lowMemory) " (SISTEMA COM POUCA MEMÓRIA)" else ""
        }.getOrDefault("?")
        val s = app.settings
        val header = listOf(
            "Versão do app" to "${pkg?.versionName ?: "?"} (código ${pkg?.longVersionCode ?: "?"})",
            "Aparelho" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Assinatura do APK (SHA-256)" to signatureFingerprint(),
            "Memória" to memory,
            "Otimização de bateria ignorada" to batteryExempt(),
            "Voz do Gemini hoje" to "${if (s.geminiTtsDay == java.time.LocalDate.now().toString()) s.geminiTtsCount else 0} pedidos (limite diário da conta gratuita: 100)",
            "Bateria" to "${runCatching { DeviceState.batteryPercent(app) }.getOrDefault(-1)}%",
            "Configuração" to "cérebro=${s.brainPreference.name}; nuvem=${s.cloudPreset.name}; voz=${s.voiceEngine.name}; " +
                "personagem=${s.character.id}; autonomia=${s.autonomy.name}; privado=${s.privateMode}; kokoroLento=${s.kokoroTooSlow}; paciência=${s.listenPatienceMs} ms; ouvirEnquantoFala=${s.bargeIn}",
        )
        val sections = buildList {
            add("Eventos, erros e quedas (mais recente no fim)" to app.events.tail(60_000))
            Poc.all.forEach { poc ->
                val lines = app.diagnostics.read(poc).takeLast(25).joinToString("\n") { rec -> rec.entries.joinToString(" ") { "${it.key}=${it.value}" } }
                add("Medições $poc" to lines)
            }
            add("Últimas ações das ferramentas" to runCatching { File(app.filesDir, "audit.jsonl").readLines().takeLast(30).joinToString("\n") }.getOrDefault(""))
        }
        return ErrorReport.build(header, sections, maxChars)
    }

    /** "não" = o HyperOS pode fechar o app em segundo plano (limpador de memória, economia de bateria). */
    private fun batteryExempt(): String =
        runCatching { if (app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) "sim" else "NÃO (o sistema pode fechar o app)" }
            .getOrDefault("?")

    private fun signatureFingerprint(): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching "indisponível"
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("indisponível")
}
