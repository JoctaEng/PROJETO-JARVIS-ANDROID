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
    /** Relatório completo, sem corte: todos os dias de registro guardados (para exportar como arquivo). */
    fun buildFull(): String = build(maxChars = 50_000_000, full = true)

    /** Relatório curto para colar numa conversa: resumo dos problemas + as últimas linhas, a mais nova primeiro. */
    fun buildShort(lines: Int = 150): String {
        val header = build(maxChars = 1, full = false, headerOnly = true)
        val log = app.events.readAll()
        val newest = log.lineSequence().filter { it.isNotBlank() && !it.startsWith("    ") }.toList().takeLast(lines).asReversed().joinToString("\n")
        val audit = runCatching { File(app.filesDir, "audit.jsonl").readLines().takeLast(10).asReversed().joinToString("\n") }.getOrDefault("")
        return header + "\n\n== Resumo dos problemas ==\n" + ErrorReport.problemSummary(log) +
            "\n\n== Últimos eventos (o MAIS NOVO primeiro) ==\n" + newest +
            "\n\n== Últimas ações das ferramentas (a mais nova primeiro) ==\n" + audit +
            "\n\n(Relatório curto. O completo está no arquivo exportado.)"
    }

    fun build(maxChars: Int = 120_000, full: Boolean = false, headerOnly: Boolean = false): String {
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            "${info.availMem / 1_048_576} MB livres de ${info.totalMem / 1_048_576} MB" + if (info.lowMemory) " (SISTEMA COM POUCA MEMÓRIA)" else ""
        }.getOrDefault("?")
        val s = app.settings
        val header = listOf(
            "Versão do app" to "${pkg?.versionName ?: "?"} (código ${pkg?.longVersionCode ?: "?"})",
            "Gerado em" to java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
            "Aparelho" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Assinatura do APK (SHA-256)" to signatureFingerprint(),
            "Memória" to memory,
            "Otimização de bateria ignorada" to batteryExempt(),
            "Voz do Gemini hoje" to "${if (s.geminiTtsDay == java.time.LocalDate.now().toString()) s.geminiTtsCount else 0} pedidos (limite diário da conta gratuita: 100)",
            "Voz do Gemini em descanso até" to (s.geminiTtsSkipUntil.takeIf { it > System.currentTimeMillis() }?.let { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "não"),
            "Última falha da voz do Gemini" to s.geminiTtsLastError.ifBlank { "nenhuma registrada" },
            "Voz do Azure este mês" to "${runCatching { app.voice.azureCharsThisMonth() }.getOrDefault(0)} caracteres de 500000 grátis; chave=${if (app.secrets.has(com.joctaeng.jarvis.settings.SecretStore.AZURE_SPEECH_KEY)) "salva" else "não"}; região=${s.azureRegion}; voz=${s.azureVoice.ifBlank { "padrão" }}",
            "Cérebro reserva" to "${s.backupPreset.name}; modelo=${s.backupModel.ifBlank { s.backupPreset.suggestedModel }.ifBlank { "-" }}; chave=${if (app.secrets.has(com.joctaeng.jarvis.settings.SecretStore.BACKUP_API_KEY)) "salva" else "não"}",
            "Voz offline Piper" to "escolhida=${s.piperVoice}; baixadas=${com.joctaeng.jarvis.voice.PiperVoice.VOICES.filter { runCatching { app.voice.piper.installed(it) }.getOrDefault(false) }.joinToString(",") { it.id }.ifBlank { "nenhuma" }}",
            "Bateria" to "${runCatching { DeviceState.batteryPercent(app) }.getOrDefault(-1)}%",
            "Configuração" to "cérebro=${s.brainPreference.name}; nuvem=${s.cloudPreset.name}; voz=${s.voiceEngine.name}; " +
                "personagem=${s.character.id}; autonomia=${s.autonomy.name}; privado=${s.privateMode}; kokoroLento=${s.kokoroTooSlow}; paciência=${s.listenPatienceMs} ms; ouvirEnquantoFala=${s.bargeIn}",
        )
        if (headerOnly) return header.joinToString("\n") { "${it.first}: ${it.second}" }
        val sections = buildList {
            val log = if (full) app.events.readAll() else app.events.tail(1_000_000)
            // A versão atual vem primeiro: o registro guarda 7 dias e pode ter linhas de versões anteriores.
            val marker = "iniciado: versão ${pkg?.versionName ?: "?"}"
            val cut = log.indexOf(marker).let { i -> if (i < 0) -1 else log.lastIndexOf('\n', i).coerceAtLeast(0) }
            val current = if (cut < 0) log else log.substring(cut)
            val older = if (cut <= 0) "" else log.substring(0, cut)
            add("Resumo dos avisos, erros e quedas DESTA versão (${pkg?.versionName ?: "?"})" to ErrorReport.problemSummary(current))
            add("Eventos, erros e quedas DESTA versão (desde a 1ª abertura; mais recente no fim)" to current)
            if (older.isNotBlank()) add("Registro de versões ANTERIORES (só para comparar; não é desta versão)" to older)
            Poc.all.forEach { poc ->
                val lines = app.diagnostics.read(poc).takeLast(25).joinToString("\n") { rec -> rec.entries.joinToString(" ") { "${it.key}=${it.value}" } }
                add("Medições $poc" to lines)
            }
            if (s.reportIncludeChat) {
                add("Conversa atual (incluída porque você ligou em Ajustes → Conversa)" to app.conversation.entries.value.filter { !it.note }
                    .joinToString("\n") { "${if (it.role == com.joctaeng.jarvis.core.model.Role.USER) "Eu" else "Euno"}: ${it.text.take(600)}" })
            }
            add("Log do Android deste app (logcat)" to androidLog())
            add("Últimas ações das ferramentas" to runCatching { File(app.filesDir, "audit.jsonl").readLines().takeLast(30).joinToString("\n") }.getOrDefault(""))
        }
        return ErrorReport.build(header, sections, maxChars)
    }

    /** O próprio app pode ler as linhas do logcat do seu processo (erros do sistema que o registro não vê). */
    private fun androidLog(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${android.os.Process.myPid()}"))
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.lineSequence().filter { line ->
            // Só o que interessa para achar erro: avisos/erros e as partes de voz, áudio e reconhecimento.
            Regex("^\\S+ \\S+ [WEF]/").containsMatchIn(line) ||
                listOf("Speech", "Recogn", "Audio", "TextToSpeech", "euno", "jarvis").any { line.contains(it, ignoreCase = true) }
        }.joinToString("\n")
    }.getOrElse { "não foi possível ler o logcat: ${it.message}" }

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
