package com.joctaeng.jarvis.mind.intent

import java.time.ZoneId
import java.time.ZonedDateTime

/** Domingo, 11/10/2026, 09:08 em Brasília — o dia da conversa de teste. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 11, 9, 8, 0, 0, zone)

    val tools = setOf(
        "abrir_app", "listar_apps", "agenda_consultar", "agenda_salvar", "agenda_editar", "agenda_apagar", "agenda_abrir", "agenda_criar",
        "estado_do_celular", "criar_alarme", "criar_timer", "lanterna", "nao_perturbe", "notificacoes_ler", "gmail_buscar", "gmail_ler",
        "whatsapp_mensagem", "tela_ler", "tela_ver", "tela_tocar", "tela_tocar_ponto", "tela_digitar", "tela_rolar", "tela_deslizar",
        "tela_pesquisar", "tela_navegar", "pesquisar_e_resumir", "pesquisar_na_web", "memoria_guardar", "contatos_buscar", "resumo_do_dia",
        "tarefa_criar", "tarefa_listar", "tarefa_pausar", "tarefa_apagar", "skill_listar",
        "joca_financas_resumo", "joca_financas_lancamentos", "joca_financas_fatura", "joca_financas_lancar", "joca_financas_conferir_documento",
        "edumath_perguntar", "edumath_listar_turmas", "edumath_sincronizar_agora", "edumath_lancar_nota", "edumath_estado_sincronizacao",
    )

    val apps = listOf(
        "Agenda" to "com.xiaomi.calendar", "Agenda" to "com.google.android.calendar", "Gmail" to "com.google.android.gm",
        "Instagram" to "com.instagram.android", "WhatsApp" to "com.whatsapp", "WhatsApp Business" to "com.whatsapp.w4b",
        "Calculadora" to "com.miui.calculator", "EduMath" to "com.joctaeng.edumath", "Joca Finanças" to "com.aistudio.jocafinancas.wxyzkp",
        "Câmera" to "com.android.camera", "Relógio" to "com.android.deskclock", "Chrome" to "com.android.chrome", "YouTube" to "com.google.android.youtube",
        "Maps" to "com.google.android.apps.maps", "Telegram" to "org.telegram.messenger", "Netflix" to "com.netflix.mediaclient",
        "Canva" to "com.canva.editor", "Binance" to "com.binance.dev", "CAIXA" to "br.com.gabba.Caixa", "Bíblia" to "com.sirma.mobile.bible.android",
        "Configurações" to "com.android.settings", "Galeria" to "com.miui.gallery", "Spotify" to "com.spotify.music",
    )

    fun ctx(
        online: Boolean = true, gmail: Boolean = true, tools: Set<String> = Fixtures.tools, now: ZonedDateTime = Fixtures.now, brain: String? = "Gemma 4 E2B (no celular)",
    ) = Ctx(now, online, tools, gmail, apps, "Joctã", brain)

    fun engine() = IntentEngine(names = listOf("Guardião", "Joca", "Euno", "Clara", "Luna"))

    /** Resultado "ok" de uma ferramenta. */
    fun ok(step: Step, text: String) = StepResult(step, true, text)
    fun bad(step: Step, text: String) = StepResult(step, false, text)

    /** Roda um plano de um estágio com respostas falsas das ferramentas e devolve a fala. */
    fun play(out: Outcome, answers: (Step) -> StepResult): Reply {
        val run = out as? Run ?: error("não é Run: $out")
        var plan = run.plan
        repeat(4) {
            val results = plan.steps.map { answers(it) }
            when (val n = plan.then(results)) {
                is Finish -> return n.reply
                is More -> plan = n.plan
            }
        }
        error("plano sem fim")
    }
}
