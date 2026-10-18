package com.joctaeng.jarvis.conversation

import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.action.gateway.AgentEvent
import com.joctaeng.jarvis.action.gateway.AgentRunner
import com.joctaeng.jarvis.action.gateway.ToolIntent
import com.joctaeng.jarvis.action.gateway.ToolProtocol
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.core.contracts.LocalLlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.mind.cloud.CloudConfig
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.mind.llama.LlamaCppProvider
import com.joctaeng.jarvis.mind.local.LiteRtLmProvider
import com.joctaeng.jarvis.mind.orchestrator.BrainPreference
import com.joctaeng.jarvis.mind.orchestrator.Orchestrator
import com.joctaeng.jarvis.mind.orchestrator.OrchestratorEvent
import com.joctaeng.jarvis.mind.orchestrator.RoutingHints
import com.joctaeng.jarvis.mind.intent.Ask
import com.joctaeng.jarvis.mind.intent.Ctx
import com.joctaeng.jarvis.mind.intent.FailureText
import com.joctaeng.jarvis.mind.intent.IntentEngine
import com.joctaeng.jarvis.mind.intent.NoMatch
import com.joctaeng.jarvis.mind.intent.Outcome
import com.joctaeng.jarvis.mind.intent.Reply
import com.joctaeng.jarvis.mind.intent.Run
import com.joctaeng.jarvis.mind.intent.Say
import com.joctaeng.jarvis.mind.intent.Translator
import com.joctaeng.jarvis.mind.persona.VoiceCommand
import com.joctaeng.jarvis.mind.persona.VoiceCommands
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.mind.persona.MemoryCommand
import com.joctaeng.jarvis.mind.persona.MemoryCommands
import com.joctaeng.jarvis.mind.persona.ConversationSummary
import com.joctaeng.jarvis.mind.persona.HistoryTrim
import com.joctaeng.jarvis.mind.persona.PersonaEngine
import com.joctaeng.jarvis.mind.persona.TextCleanup
import com.joctaeng.jarvis.mind.memory.ConversationSummary as ConversationSummaryItem
import com.joctaeng.jarvis.mind.persona.PromptContext
import com.joctaeng.jarvis.mind.persona.SelfInfo
import com.joctaeng.jarvis.mind.persona.SelfKnowledge
import com.joctaeng.jarvis.mind.persona.SentenceChunker
import com.joctaeng.jarvis.mind.persona.StreamingEmotionParser
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.poc.ModelStore
import com.joctaeng.jarvis.settings.CloudPreset
import com.joctaeng.jarvis.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma linha da conversa na tela. [note] = aviso do sistema (offline, troca de cérebro...). */
data class ChatEntry(
    val id: Long,
    val role: Role,
    val text: String,
    val brain: String? = null,
    val streaming: Boolean = false,
    val note: Boolean = false,
    /** Mensagem recebida durante a resposta: espera na fila para ser respondida junto. */
    val queued: Boolean = false,
    /** Arquivos anexados pelo usuário (imagem, áudio, PDF, DOCX, TXT, CSV). */
    val attachments: List<com.joctaeng.jarvis.core.model.Attachment> = emptyList(),
)

/**
 * Coração da Fase 1: recebe o que o usuário disse/escreveu, trata comandos de
 * memória, monta o prompt pela personalidade, pede ao orquestrador o melhor
 * cérebro disponível, mostra a resposta em streaming, fala frase a frase e
 * dirige as expressões do personagem.
 */
class ConversationController(private val app: JarvisApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = app.settings
    private val voice get() = app.voice
    private var nextId = 1L
    private var job: Job? = null
    private var calmDown: Job? = null
    private var local: LocalLlmProvider? = null
    private var localKey: String? = null
    /** Identificador da conversa: nova conversa, novo identificador (nada da anterior vaza para as ferramentas). */
    private var sessionId = java.util.UUID.randomUUID().toString()
    /** Primeiro turno de uma conversa começada do zero: sem caminhos aprendidos e sem resumo, a não ser que o usuário peça. */
    @Volatile private var freshSession = false

    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _turnFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Resposta terminou e a voz terminou de falar — hora de ouvir de novo (modo voz). */
    val turnFinished: SharedFlow<Unit> = _turnFinished.asSharedFlow()

    /** Mensagens recebidas enquanto ele ainda responde: esperam na fila e viram uma resposta só no fim. */
    /** Os resumos marcados valem só para a conversa que o usuário abriu pedindo isso (botão Nova conversa). */
    @Volatile private var useSummaries = false

    private val queue = ArrayDeque<String>()
    private var queuedSpeak = false
    /** Anexos de mensagens que estão na fila (vão juntos quando a fila for respondida). */
    private val queuedAttachments = mutableListOf<com.joctaeng.jarvis.core.model.Attachment>()

    fun send(text: String, speak: Boolean, attachments: List<com.joctaeng.jarvis.core.model.Attachment> = emptyList()) {
        val clean = text.trim()
        if (clean.isEmpty() && attachments.isEmpty()) return
        if (_busy.value && attachments.isNotEmpty()) {
            queue.addLast(clean)
            queuedSpeak = speak
            queuedAttachments += attachments
            add(ChatEntry(nextId++, Role.USER, clean, queued = true, attachments = attachments))
            app.events.info("conversa", "mensagem com ${attachments.size} anexo(s) na fila")
            return
        }
        if (attachments.isNotEmpty()) {
            startWithAttachments(clean, speak, attachments, addEntry = true)
            return
        }
        if (_busy.value) {
            // Ele está pensando ou falando: comandos valem na hora; o resto entra na fila (nada se perde).
            when (VoiceCommands.parse(clean)) {
                VoiceCommand.STOP -> {
                    app.events.info("conversa", "comando de voz: interromper")
                    cancel()
                    return
                }
                VoiceCommand.STOP_LISTENING -> {
                    OverlayBus.requestStopListening()
                    return
                }
                VoiceCommand.DISMISS -> {
                    val old = job
                    queue.clear()
                    old?.cancel()
                    voice.stop()
                    scope.launch {
                        old?.join()
                        start(clean, speak, addEntry = true)
                    }
                    return
                }
                null -> Unit
            }
            queue.addLast(clean)
            queuedSpeak = speak
            add(ChatEntry(nextId++, Role.USER, clean, queued = true))
            app.events.info("conversa", "mensagem na fila (${queue.size}): será respondida junto, ao fim da resposta atual")
            return
        }
        start(clean, speak, addEntry = true)
    }

    /**
     * Tarefa programada (v0.28): espera a conversa ficar livre e faz o pedido guardado. 0.31: primeiro o motor tenta o pedido
     * como o usuário falaria (sem o prefixo, que confundia); se não for com ele, a IA faz SEM as ferramentas de tarefa (no teste
     * de 14/10 ela "agendou de novo" a tarefa em vez de executá-la). Devolve a resposta final, para a notificação e a voz.
     */
    suspend fun runScheduled(name: String, instruction: String): String? = withContext(Dispatchers.Main) {
        if (!awaitFree()) return@withContext null
        val before = nextId
        add(ChatEntry(nextId++, Role.SYSTEM, "Tarefa programada \"$name\"", note = true))
        runCatching {
            val ctx = engineCtx()
            val out = engine.handle(instruction, ctx)
            if (out is Run && out.confirm == null) {
                _busy.value = true
                try {
                    val reply = executor.execute(out.plan, engineTools(), "Tarefa: $name")
                    val text = humanize(reply, instruction)
                    sayRich(text, reply.detail, emotionOf(reply.emotion), speak = false, brain = "motor do Euno")
                    return@withContext text
                } finally {
                    _busy.value = false
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val watcher = scope.launch { turnFinished.collect { finished.complete(Unit) } }
        scheduledRun = true
        start("[TAREFA PROGRAMADA \"$name\" — FAÇA AGORA, não agende nada] $instruction", speak = false, addEntry = false)
        kotlinx.coroutines.withTimeoutOrNull(4 * 60_000L) { finished.await() }
        watcher.cancel()
        scheduledRun = false
        _entries.value.lastOrNull { it.id >= before && it.role == Role.ASSISTANT && !it.note && it.text.isNotBlank() }?.text
    }

    /** Resumo programado ("me acorde com o resumo do dia e dos e-mails"): o motor busca tudo pelas ferramentas, a IA só deixa natural. */
    suspend fun runBriefing(name: String, parts: com.joctaeng.jarvis.mind.intent.Briefing.Parts): String? = withContext(Dispatchers.Main) {
        if (!awaitFree()) return@withContext null
        _busy.value = true
        try {
            withContext(Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { app.sync.run("tarefa $name") } } }
            add(ChatEntry(nextId++, Role.SYSTEM, "Tarefa programada \"$name\"", note = true))
            val ctx = engineCtx()
            val out = com.joctaeng.jarvis.mind.intent.Briefing.plan("day.briefing", ctx, parts)
            val reply = (out as? Run)?.let { executor.execute(it.plan, engineTools(), "Tarefa: $name") } ?: return@withContext null
            val text = humanize(reply, "resumo do dia para o usuário ouvir ao acordar")
            sayRich(text, reply.detail, Emotion.HAPPY, speak = false, brain = "motor do Euno")
            text
        } finally {
            _busy.value = false
        }
    }

    private suspend fun awaitFree(): Boolean {
        kotlinx.coroutines.withTimeoutOrNull(120_000L) { while (_busy.value) kotlinx.coroutines.delay(1_000) }
        return !_busy.value
    }

    /** Durante a tarefa programada a IA não pode criar/apagar tarefas (ela fazia isso em vez de executar). */
    @Volatile private var scheduledRun = false

    private fun start(clean: String, speak: Boolean, addEntry: Boolean) {
        voice.stop()
        job = scope.launch {
            _busy.value = true
            if (addEntry) add(ChatEntry(nextId++, Role.USER, clean))
            try {
                when (VoiceCommands.parse(clean)) {
                    VoiceCommand.DISMISS -> {
                        app.events.info("conversa", "comando de voz: despedida")
                        say("Até logo!", Emotion.HAPPY, speak)
                        if (speak) voice.awaitIdle()
                        OverlayBus.requestDismiss()
                        return@launch
                    }
                    VoiceCommand.STOP_LISTENING, VoiceCommand.STOP -> {
                        app.events.info("conversa", "comando de voz: parar de ouvir")
                        // Para de ouvir já; a conversa por voz só continua se a pessoa pedir de novo.
                        OverlayBus.requestStopListening()
                        say("Tudo bem, parei de ouvir.", Emotion.NEUTRAL, speak)
                        return@launch
                    }
                    null -> Unit
                }
                // Atalhos sem cérebro ("abra o Gmail", "tela inicial", "rola para baixo"): na hora e sempre iguais.
                val quick = com.joctaeng.jarvis.mind.persona.QuickCommands.parse(clean)
                if (quick != null && runQuick(quick, speak)) {
                    if (speak) voice.awaitIdle()
                    return@launch
                }
                // "Quando eu disser X, faça Y": ensina uma skill nova (v0.27).
                val lesson = com.joctaeng.jarvis.mind.memory.SkillTeaching.parse(clean)
                if (lesson != null && !settings.privateMode) {
                    val skill = withContext(Dispatchers.IO) { app.skills.teach(lesson.trigger, lesson.steps) }
                    app.events.info("skills", "aprendida: \"${skill.triggers.first()}\"")
                    say("Aprendi! Quando você disser \"${skill.triggers.first()}\", eu faço: ${skill.steps}", Emotion.HAPPY, speak)
                    if (speak) voice.awaitIdle()
                    return@launch
                }
                // Motor do próprio Euno (v0.29): entende e executa sem IA; se não entende, a IA só traduz o pedido para ele.
                if (tryEngine(clean, speak)) {
                    if (speak) voice.awaitIdle()
                    return@launch
                }
                val command = MemoryCommands.parse(clean)
                if (command != null) handleMemory(command, speak) else respond(speak)
                if (speak) voice.awaitIdle()
            } finally {
                _busy.value = false
                OverlayBus.acting.value = false
                // Depois do primeiro turno a conversa já é "em andamento": caminhos aprendidos voltam a entrar no prompt.
                freshSession = false
                relax()
                if (queue.isEmpty()) _turnFinished.tryEmit(Unit) else drainQueue()
            }
        }
    }

    /**
     * Mensagem com anexo: vai direto ao cérebro (o motor de pedidos e os atalhos não leem arquivos). Documentos já
     * chegam com o texto extraído no aparelho; imagem e áudio vão ao modelo do celular que os lê.
     */
    private fun startWithAttachments(clean: String, speak: Boolean, attachments: List<com.joctaeng.jarvis.core.model.Attachment>, addEntry: Boolean) {
        voice.stop()
        job = scope.launch {
            _busy.value = true
            if (addEntry) add(ChatEntry(nextId++, Role.USER, clean, attachments = attachments))
            try {
                app.events.info("conversa", "mensagem com anexos: " + attachments.joinToString { "${it.kind.name.lowercase()} ${it.mime}" })
                respond(speak)
                if (speak) voice.awaitIdle()
            } finally {
                _busy.value = false
                OverlayBus.acting.value = false
                freshSession = false
                relax()
                if (queue.isEmpty()) _turnFinished.tryEmit(Unit) else drainQueue()
            }
        }
    }

    /** Junta o que chegou durante a resposta e responde de uma vez (a pessoa já viu cada mensagem no chat). */
    private fun drainQueue() {
        val merged = queue.joinToString(" ").trim()
        val speak = queuedSpeak
        val files = queuedAttachments.toList()
        queue.clear()
        queuedAttachments.clear()
        _entries.update { list -> list.map { if (it.queued) it.copy(queued = false) else it } }
        app.events.info("conversa", "respondendo as mensagens da fila juntas")
        if (files.isNotEmpty()) {
            // A última mensagem da fila passa a carregar todos os anexos (é ela que vai ao cérebro).
            _entries.update { list ->
                val idx = list.indexOfLast { it.role == Role.USER }
                if (idx < 0) list else list.toMutableList().also { it[idx] = it[idx].copy(text = merged, attachments = files) }
            }
            startWithAttachments(merged, speak, files, addEntry = false)
        } else {
            start(merged, speak, addEntry = false)
        }
    }

    /** Interrompe a resposta e a fala. */
    fun cancel() {
        job?.cancel()
        voice.stop()
        OverlayBus.acting.value = false
        queue.clear()
        queuedAttachments.clear()
        _entries.update { list -> list.filterNot { it.queued }.map { if (it.streaming) it.copy(streaming = false) else it } }
        OverlayBus.anim.value = AnimState.IDLE
    }

    fun clearConversation() {
        cancel()
        engine = newEngine()
        pendingAsk = null
        _entries.value = emptyList()
        useSummaries = false
    }

    /** Há o que resumir? (pelo menos uma fala do usuário). */
    fun hasConversation(): Boolean = _entries.value.any { it.role == Role.USER && !it.note }

    /** Começa do zero. [loadSummaries]: a nova conversa já leva em conta os resumos marcados em "Resumos de conversa". */
    fun newConversation(loadSummaries: Boolean) {
        clearConversation()
        // Estado da conversa anterior não pode sobrar: sessão nova, fila vazia, cérebros em descanso/fracos zerados,
        // tarefa programada cancelada, motor sem "pergunta em aberto".
        queue.clear()
        queuedSpeak = false
        scheduledRun = false
        pendingAsk = null
        toolWeak.clear()
        sessionId = java.util.UUID.randomUUID().toString()
        executor = EngineExecutor(app, sessionId)
        freshSession = true
        useSummaries = loadSummaries
        app.events.info("conversa", "nova conversa (${if (loadSummaries) "com resumo curto" else "limpa"}; sessão nova)")
    }

    /**
     * Resume a conversa atual e guarda em "Resumos de conversa". Usa o cérebro configurado; sem cérebro ou com erro,
     * guarda um resumo simples só com as falas do usuário. Retorna o resumo salvo, ou null se não havia conversa.
     */
    suspend fun summarizeCurrent(): ConversationSummaryItem? {
        val talk = _entries.value.filter { !it.note && !it.streaming && it.text.isNotBlank() }
        val userMessages = talk.filter { it.role == Role.USER }.map { it.text }
        if (userMessages.isEmpty()) return null
        val date = SimpleDateFormat("dd/MM", Locale.forLanguageTag("pt-BR")).format(Date())
        val transcript = ConversationSummary.transcript(talk.map { (if (it.role == Role.USER) settings.userName.ifBlank { "Usuário" } else "Euno") to it.text })
        val providers = configuredProviders().map { if (it.location == ProviderLocation.ON_DEVICE) CompactProvider(it, ConversationSummary.SYSTEM_PROMPT, 1) else it }
        var text = ""
        if (providers.isNotEmpty()) {
            runCatching {
                val device = DeviceState.snapshot(app, privateMode = settings.privateMode)
                val request = LlmRequest(ConversationSummary.SYSTEM_PROMPT, listOf(ChatMessage(Role.USER, transcript)), 400)
                Orchestrator(providers).respond(request, device, RoutingHints(preference = routingPreference())).collect { event ->
                    if (event is OrchestratorEvent.Chunk && event.chunk is LlmChunk.Text) text += (event.chunk as LlmChunk.Text).text
                }
            }.onFailure { app.events.warn("conversa", "resumo pelo cérebro falhou", it) }
        }
        val body = text.trim().ifBlank { ConversationSummary.fallback(userMessages) }.take(PersonaEngine.MAX_SUMMARY_CHARS)
        val saved = app.summaries.add(ConversationSummary.title(userMessages.first(), date), body, useInNewChats = false)
        app.events.info("conversa", "resumo salvo (${body.length} car., via ${if (text.isBlank()) "reserva" else "cérebro"})")
        return saved
    }

    /**
     * Carrega o modelo do celular em segundo plano quando a pessoa abre a conversa, para a leitura de ~2,4 GB
     * acontecer enquanto ela fala. Só quando o cérebro local pode ser usado (evita ocupar memória à toa).
     */
    fun preloadLocalModel() {
        if (settings.brainPreference == BrainPreference.ONLINE_FIRST || settings.brainPreference == BrainPreference.AUTO) return
        val provider = localProvider() as? LlamaCppProvider ?: return
        if (provider.isLoaded) return
        scope.launch { runCatching { provider.load() }.onFailure { app.events.warn("llama", "pré-carga do modelo falhou", it) } }
    }

    /** Libera o modelo local da memória (onTrimMemory). */
    fun releaseLocalModel() {
        val provider = local ?: return
        scope.launch { provider.unload() }
    }

    /**
     * O cérebro do celular é lento; ele só entra quando a pessoa o escolheu (local primeiro/somente), quando não há
     * cérebro online configurado ou quando não há internet. Antes ele virava a reserva de qualquer erro da nuvem
     * (cota, limite de gasto) e a resposta demorava muito.
     */
    fun configuredProviders(): List<LlmProvider> {
        val cloud = cloudProviders()
        val localWanted = cloud.isEmpty() || settings.brainPreference == BrainPreference.LOCAL_FIRST ||
            settings.brainPreference == BrainPreference.LOCAL_ONLY || !DeviceState.snapshot(app, privateMode = false).online
        // Com "nuvem primeiro", o cérebro do celular antes nunca entrava: se a nuvem toda falhava, o Euno ficava mudo (pedido 78).
        val lastResort = settings.localAsLastResort
        return cloud + listOfNotNull(if (localWanted || lastResort) localProvider() else null)
    }

    @Volatile private var reserveLookup = false

    /**
     * Antes, em "Automático", o cérebro do celular só entrava sem nuvem. Agora ele entra sempre como último recurso, então
     * "Automático" com nuvem vira "nuvem primeiro" na ordem — senão o local (lento) passaria à frente nos pedidos simples.
     */
    private fun routingPreference(): BrainPreference =
        if (settings.brainPreference == BrainPreference.AUTO && app.brains.all().any { app.brains.ready(it) }) BrainPreference.ONLINE_FIRST
        else settings.brainPreference

    /**
     * Cérebros que recebem o prompt enxuto: todos, menos o Gemini. Groq, Cerebras, Mistral e OpenRouter têm limite de
     * tokens por minuto (o Groq grátis aceita ~8 mil) e a versão 0.31.0 mandava ~46 mil caracteres por pedido: 413 e 429.
     */
    private fun leanIds(): Set<String> = app.brains.all()
        .filter { it.preset != com.joctaeng.jarvis.settings.CloudPreset.GEMINI.name }
        .map { "brain_${it.id}" }.toSet()

    /** Cérebros que bateram limite (429) descansam o tempo pedido pelo provedor e o próximo da lista responde. */
    private val cooldown = com.joctaeng.jarvis.mind.orchestrator.ProviderCooldown()

    /** Cérebros que voltaram vazios em pedidos com ferramentas nesta sessão: ficam por último quando há ação. */
    private val toolWeak = mutableSetOf<String>()

    /** Cérebros online na ordem escolhida pelo usuário, sem os que estão descansando (se todos descansam, tenta todos). */
    private fun cloudProviders(): List<LlmProvider> {
        // Reserva automática do Gemini (outro modelo, mesma chave) logo depois do principal (pedido 78).
        if (settings.geminiReserveModel.isBlank() && !reserveLookup) {
            reserveLookup = true
            scope.launch { runCatching { app.brains.ensureGeminiReserve() }.getOrNull()?.let { app.events.info("cerebro", "reserva automática do Gemini: $it") } }
        }
        val reserve = app.brains.geminiReserve()
        val all = app.brains.all().mapNotNull { app.brains.provider(it) }.flatMap { p ->
            if (reserve != null && reserve.id == p.id + "_reserva") listOf(p, reserve) else listOf(p)
        }
        val now = System.currentTimeMillis()
        return all.filterNot { cooldown.resting(it.id, now) }.ifEmpty { all }
    }

    // ---------------------------------------------------------------- motor de pedidos (v0.29, pedido 81)

    private fun engineNames(): List<String> =
        (listOf(settings.displayName, "Euno") + settings.wakeNames + settings.wakeNames.flatMap { com.joctaeng.jarvis.mind.persona.WakeWord.aliasesOf(it) }).filter { it.isNotBlank() }

    private fun newEngine() = IntentEngine(names = engineNames())
    private var engine = newEngine()
    private var engineKey = engineNames()
    private var pendingAsk: Pair<Ask, Long>? = null
    private var executor = EngineExecutor(app, sessionId)
    private var toolsCache: Pair<Long, List<com.joctaeng.jarvis.core.contracts.Tool>>? = null
    private var appsCache: Pair<Long, List<Pair<String, String>>>? = null

    /** As ferramentas ligadas, guardadas por 45 s (reunir as do MCP custa até 4 s e o motor roda em todo pedido). */
    private suspend fun engineTools(): List<com.joctaeng.jarvis.core.contracts.Tool> {
        val now = System.currentTimeMillis()
        toolsCache?.takeIf { now - it.first < 45_000 }?.let { return it.second }
        val tools = if (settings.autonomy == AutonomyLevel.OBSERVER) emptyList() else app.toolbox.enabledTools()
        toolsCache = now to tools
        return tools
    }

    private suspend fun engineCtx(): Ctx {
        val brain = configuredProviders().firstOrNull()?.displayName
        // Leituras pesadas (lista de apps, ferramentas do MCP) fora da thread principal: a 0.29.0 travou (ANR) num dia de teste.
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val apps = appsCache?.takeIf { now - it.first < 300_000 }?.second
                ?: runCatching { com.joctaeng.jarvis.tools.installedApps(app) }.getOrDefault(emptyList()).also { appsCache = now to it }
            Ctx(
                now = java.time.ZonedDateTime.now(),
                online = DeviceState.snapshot(app, privateMode = false).online,
                tools = engineTools().map { it.name }.toSet(),
                gmailConnected = settings.gmailConnected,
                apps = apps,
                userName = settings.userName,
                brainName = brain,
                phoneControl = settings.phoneControl,
                selfName = settings.displayName,
            )
        }
    }

    /**
     * 1º o motor próprio entende e faz (sem IA, igual online e offline); 2º, se não entendeu e o pedido parece ação, a IA
     * só TRADUZ para uma intenção que o motor executa; 3º, se nada serviu, volta o caminho antigo (conversa / IA com ferramentas).
     * Devolve true se o pedido foi atendido aqui.
     */
    private suspend fun tryEngine(clean: String, speak: Boolean): Boolean {
        // Skill ensinada pelo usuário ("quando eu disser X, faça Y") vale mais que o motor.
        app.skills.match(clean)?.let { if (!it.builtIn) return false }
        val t0 = System.nanoTime()
        return try {
            if (engineKey != engineNames()) {
                engine = newEngine()
                engineKey = engineNames()
            }
            val ctx = engineCtx()
            var path = EngineStats.Path.ENGINE
            var outcome: Outcome? = null
            val pending = pendingAsk?.takeIf { System.currentTimeMillis() - it.second < 120_000 }
            pendingAsk = null
            if (pending != null) outcome = engine.answer(pending.first, clean, ctx)
            if (outcome == null) outcome = engine.handle(clean, ctx)
            if (outcome is NoMatch) {
                outcome = translate(clean, ctx)
                if (outcome != null) path = EngineStats.Path.TRANSLATOR
            }
            if (outcome == null || outcome is NoMatch) return false
            present(outcome, speak, path, t0, clean)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.events.error("motor", "erro no motor (o pedido segue pelo caminho antigo): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun present(outcome: Outcome, speak: Boolean, path: EngineStats.Path, t0: Long, request: String) {
        val brain = if (path == EngineStats.Path.TRANSLATOR) "IA traduziu → motor" else "motor do Euno"
        var intent = "?"
        val now = System.currentTimeMillis()
        when (outcome) {
            is Say -> {
                intent = outcome.intentId
                say(outcome.text, emotionOf(outcome.emotion), speak, brain)
            }
            is Ask -> {
                intent = outcome.intentId
                pendingAsk = outcome to now
                say(outcome.question, Emotion.NEUTRAL, speak, brain)
            }
            is Run -> {
                intent = outcome.intentId
                OverlayBus.acting.value = true
                OverlayBus.anim.value = AnimState.THINKING
                val reply = try {
                    executor.execute(outcome.plan, engineTools(), request.take(100))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Reply("Não consegui terminar: ${e.message ?: e.javaClass.simpleName}", "concerned")
                }
                reply.ask?.let { pendingAsk = it to System.currentTimeMillis() }
                val text = humanize(reply, request)
                sayRich(text, reply.detail, emotionOf(reply.emotion), speak, if (text != reply.text) "motor do Euno + IA" else brain)
            }
            NoMatch -> Unit
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        app.engineStats.note(path, ms)
        app.events.info("motor", "«${request.take(80)}» → $intent por ${path.label} em $ms ms")
    }

    /**
     * 0.31 (teste de 14/10: "o motor fala igual um robô"): os fatos vêm do motor (certos, das ferramentas); a IA da nuvem, se
     * houver e responder em até 8 s, só reescreve de um jeito natural e curto. Sem nuvem, sem internet ou se ela inventar
     * (texto vazio, longo demais, ferramenta no meio), fica o texto do motor. O cérebro do celular não entra: é lento.
     */
    private suspend fun humanize(reply: Reply, request: String): String {
        val facts = reply.facts ?: return reply.text
        if (!settings.naturalReplies || settings.privateMode) return reply.text
        val device = DeviceState.snapshot(app, privateMode = false)
        if (!device.online) return reply.text
        val providers = cloudProviders()
        if (providers.isEmpty()) return reply.text
        val name = settings.displayName.ifBlank { "Euno" }
        val system = "Você é $name, assistente pessoal de ${settings.userName.ifBlank { "o usuário" }}, falando em voz alta, em português do Brasil. " +
            "Reescreva a RESPOSTA PRONTA de um jeito natural, caloroso e curto (2 a 5 frases), como uma pessoa falaria. Regras: use SÓ os fatos " +
            "dados (nunca invente compromisso, horário, remetente ou número); não leia códigos, números de processo, barras nem hífens; " +
            "agrupe o que se repete; diga horários como \"às 15h\"; com muitos itens, destaque os mais importantes e diga que a lista completa " +
            "está na tela; sem markdown, sem listas, sem emojis, sem etiqueta de emoção. Responda só com o texto final."
        val user = "Pedido do usuário: ${request.take(200)}\n\nRESPOSTA PRONTA (correta): ${reply.text}\n\nDADOS:\n${facts.take(3_500)}"
        val out = StringBuilder()
        val t0 = System.nanoTime()
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            runCatching {
                Orchestrator(providers).respond(LlmRequest(system, listOf(ChatMessage(Role.USER, user)), 350), device, RoutingHints(preference = BrainPreference.ONLINE_FIRST)).collect { e ->
                    if (e is OrchestratorEvent.Chunk) (e.chunk as? LlmChunk.Text)?.let { out.append(it.text) }
                }
            }
        }
        val text = TextCleanup.clean(StreamingEmotionParser().let { p -> p.feed(out.toString()) + p.finish() }).trim()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val bad = text.length < 15 || text.length > 1_400 || "<tool" in text || "tool_call" in text
        app.events.info("motor", "resposta natural pela IA: ${if (bad) "descartada" else "usada"} (${text.length} car., $ms ms)")
        return if (bad) reply.text else text
    }

    /** Fala só o resumo; a tela mostra o resumo e, embaixo, a lista completa (títulos inteiros, horários). */
    private fun sayRich(text: String, detail: String?, emotion: Emotion, speak: Boolean, brain: String? = null) {
        val shown = if (detail.isNullOrBlank()) text else "$text\n\n$detail"
        add(ChatEntry(nextId++, Role.ASSISTANT, shown, brain = brain ?: settings.displayName))
        OverlayBus.emotion.value = emotion
        if (speak) voice.speak(text)
    }

    private fun emotionOf(name: String): Emotion = runCatching { Emotion.valueOf(name.uppercase()) }.getOrDefault(Emotion.NEUTRAL)

    /** A IA só traduz: recebe poucas intenções parecidas e devolve uma linha de JSON; quem executa é o motor. */
    private suspend fun translate(clean: String, ctx: Ctx): Outcome? {
        if (settings.autonomy == AutonomyLevel.OBSERVER || !ToolIntent.likely(clean)) return null
        val candidates = engine.candidates(clean, ctx)
        if (candidates.isEmpty()) return null
        val providers = configuredProviders()
        if (providers.isEmpty()) return null
        val (system, user) = Translator.prompt(clean, candidates, ctx)
        val device = DeviceState.snapshot(app, privateMode = settings.privateMode)
        val onlyLocal = providers.all { it.location == ProviderLocation.ON_DEVICE }
        val text = StringBuilder()
        OverlayBus.anim.value = AnimState.THINKING
        val t0 = System.nanoTime()
        val finished = kotlinx.coroutines.withTimeoutOrNull(if (onlyLocal) 45_000L else 15_000L) {
            Orchestrator(providers).respond(LlmRequest(system, listOf(ChatMessage(Role.USER, user)), 200), device, RoutingHints(preference = routingPreference())).collect { event ->
                if (event is OrchestratorEvent.Chunk) (event.chunk as? LlmChunk.Text)?.let { text.append(it.text) }
            }
            true
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val parsed = Translator.parse(text.toString(), engine.all)
        app.events.info("motor", "tradutor (${ms} ms${if (finished == null) ", tempo esgotado" else ""}): " + (parsed?.let { "${it.intentId} ${it.slots}" } ?: "sem tradução (${text.toString().take(80).replace('\n', ' ')})"))
        if (parsed == null) return null
        return engine.fromTranslation(parsed.intentId, parsed.slots, ctx, clean).takeIf { it !is NoMatch }
    }

    private suspend fun respond(speak: Boolean, retried: Boolean = false) {
        val startedAt = System.nanoTime()
        val device = DeviceState.snapshot(app, privateMode = settings.privateMode)
        // Imagem/áudio anexado: só o modelo do celular que lê isso responde (os online do Euno recebem só texto).
        val media = _entries.value.lastOrNull { it.role == Role.USER }?.attachments.orEmpty()
            .filter { it.kind != com.joctaeng.jarvis.core.model.AttachmentKind.DOCUMENT }
        val mediaBrain = if (media.isEmpty()) null else mediaProvider(media, speak) ?: return
        val providers = mediaBrain?.let { listOf(it) } ?: configuredProviders()
        if (providers.isEmpty()) {
            say(
                "Ainda não tenho um cérebro. Abra Meu Euno → Cérebro e configure uma API (por exemplo, Gemini) " +
                    "ou escolha um modelo local.",
                Emotion.CONFUSED, speak,
            )
            return
        }
        val onlyLocal = mediaBrain != null || providers.all { it.location == ProviderLocation.ON_DEVICE } || settings.brainPreference == BrainPreference.LOCAL_ONLY
        val lastUser = _entries.value.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val memories = app.memoryRecall.relevantLines(lastUser, k = 8)
        // Skill pedida ("modo aula", "preparar aula", ou uma ensinada): os passos dela entram no prompt deste turno.
        val activeSkill = if (settings.autonomy == AutonomyLevel.OBSERVER) null else app.skills.match(lastUser)
        activeSkill?.let { app.events.info("skills", "skill ativa: ${it.name}") }
        val tools = (if (settings.autonomy == AutonomyLevel.OBSERVER) emptyList() else app.toolbox.enabledTools())
            .let { all -> if (scheduledRun) all.filterNot { it.name.startsWith("tarefa_") } else all }
        // Retrato sincronizado (agenda, clima, e-mails): entra no prompt e, se velho, é renovado para a próxima.
        app.sync.refreshIfStale("conversa")
        val live = app.sync.liveSection(if (onlyLocal) LOCAL_LIVE_CHARS else LIVE_CHARS)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        // Resumo só quando a pessoa escolheu "com resumo", e curto: a IA recebe um parágrafo, não a conversa inteira.
        val summaries = if (useSummaries) app.summaries.activeContext(if (onlyLocal) LOCAL_SUMMARY_CHARS else SUMMARY_CHARS) else ""

        // Dois prompts: o completo (nuvem) e o enxuto (celular). Cada cérebro recebe só o que cabe nele.
        // Terceiro prompt (pedido 78): "enxuto" para cérebros com pouca cota por minuto (Groq grátis ~8 mil tokens/min):
        // só as ferramentas básicas e as do assunto, sem o catálogo de skills.
        fun buildPrompt(compact: Boolean, lean: Boolean = false, maxTools: Int = 8, noTools: Boolean = false): String = PersonaEngine.systemPrompt(
            userName = settings.userName,
            character = settings.persona,
            characterName = settings.characterName,
            mode = settings.personaMode,
            memories = memories,
            context = PromptContext(
                nowDescription = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR")).format(Date()),
                offline = !device.online,
                privateMode = settings.privateMode,
                speakingAloud = speak,
            ),
            compact = compact,
            // No cérebro local, o prompt precisa caber numa leitura rápida: ferramentas só quando o pedido sugere ação.
            toolsSection = if (noTools || tools.isEmpty() || !toolsNeeded(compact)) "" else ToolProtocol.systemSection(
                (when {
                    compact -> com.joctaeng.jarvis.mind.orchestrator.ToolSelector.forLocal(tools.map { it.name }, lastUser, activeSkill?.steps.orEmpty(), maxTools)
                    lean -> com.joctaeng.jarvis.mind.orchestrator.ToolSelector.select(tools.map { it.name }, lastUser, activeSkill?.steps.orEmpty(), max = LEAN_TOOLS)
                    else -> tools.map { it.name }
                }).let { names -> tools.filter { it.name in names } },
            ) +
                (activeSkill?.let { "\n" + app.skills.describe(it) }.orEmpty()) +
                (if (compact || lean) "" else "\nSKILLS (pedidos curtos que viram passos combinados; o usuário ensina novas com \"quando eu disser X, faça Y\" ou skill_ensinar): " + app.skills.catalogLine()) +
                (if (compact || activeSkill != null || freshSession) "" else app.recipes.find(lastUser)?.let { "\n" + app.recipes.describe(it) }.orEmpty()),
            userBio = if (compact) settings.userBio.take(LOCAL_BIO_CHARS) else settings.userBio,
            summaries = if (compact) summaries.take(LOCAL_SUMMARY_CHARS) else summaries,
            liveSection = if (compact) live.take(LOCAL_LIVE_CHARS) else live,
            selfSection = SelfKnowledge.section(
                SelfInfo(
                    versionName = version,
                    assistantName = settings.displayName,
                    article = settings.persona.gender.article,
                    brainNames = providers.map { it.displayName },
                    activeBrain = providers.firstOrNull()?.displayName.orEmpty(),
                    voiceName = settings.voiceEngine.name.lowercase(),
                    autonomyLabel = settings.autonomy.name.lowercase(),
                    toolNames = tools.map { it.name },
                    memoryCount = memories.size,
                    privateMode = settings.privateMode,
                    features = conversationFeatures(),
                ),
                compact = compact,
            ),
        )

        // Cérebro do celular: o maior prompt que cabe na janela de 4.096 tokens dele (calculado sob demanda).
        val localPrompt by lazy {
            listOf(8, 4).asSequence().map { buildPrompt(true, maxTools = it) }.firstOrNull { it.length <= LOCAL_PROMPT_MAX_CHARS }
                ?: buildPrompt(true, noTools = true)
        }
        val prompt = if (onlyLocal) localPrompt else buildPrompt(false)
        // Pedido com ferramentas: cérebros que já voltaram vazios com ferramentas nesta sessão vão para o fim da fila.
        val ordered = if (prompt.contains("<tools>")) providers.sortedBy { it.id in toolWeak } else providers
        val effective = if (onlyLocal) ordered else ordered.map { p ->
            // Cérebro do celular no meio da lista (fallback): recebe prompt/histórico enxutos, não os da nuvem.
            when {
                p.location == ProviderLocation.ON_DEVICE -> CompactProvider(p, localPrompt, LOCAL_HISTORY)
                p.id in leanIds() -> LeanProvider(p, buildPrompt(false, lean = true), LEAN_HISTORY)
                else -> p
            }
        }
        // Documentos anexados entram como texto (extraído no aparelho), com teto menor no cérebro do celular.
        val docChars = if (onlyLocal) LOCAL_DOC_CHARS else com.joctaeng.jarvis.mind.models.AttachmentTypes.CLOUD_TEXT_CHARS
        val allHistory = _entries.value.filter { !it.note && !it.streaming }.map {
            if (it.attachments.isEmpty()) ChatMessage(it.role, it.text)
            else ChatMessage(it.role, com.joctaeng.jarvis.mind.models.AttachmentTypes.promptText(it.text, it.attachments, docChars), attachments = it.attachments)
        }
        val history = if (onlyLocal) localHistory(allHistory) else allHistory.takeLast(HISTORY)
        val maxTokens = if (onlyLocal) 512 else null

        OverlayBus.anim.value = AnimState.THINKING
        OverlayBus.emotion.value = Emotion.THINKING
        val replyId = nextId++
        add(ChatEntry(replyId, Role.ASSISTANT, "", streaming = true))
        var parser = StreamingEmotionParser()
        val chunker = SentenceChunker()
        val names = providers.associate { it.id to it.displayName }
        var lastNote: String? = null
        var failure: String? = null
        var newRound = false
        var routedId: String? = null
        // Um só aviso de progresso ("Agindo… 3 passos") em vez de uma linha por tentativa (pedido 65).
        var stepNoteId: Long? = null
        var steps = 0
        // Passos que deram certo neste pedido: viram "caminho" para a próxima vez (memória de caminhos).
        val okSteps = mutableListOf<com.joctaeng.jarvis.mind.memory.RecipeStep>()

        val generate: (List<ChatMessage>) -> Flow<LlmChunk> = { messages ->
            flow {
                Orchestrator(effective).respond(LlmRequest(prompt, messages, maxTokens), device, RoutingHints(preference = routingPreference())).collect { event ->
                    when (event) {
                        is OrchestratorEvent.Notice -> if (event.text != lastNote) {
                            lastNote = event.text
                            app.events.info("conversa", "aviso: ${event.text}")
                            addNoteBefore(replyId, event.text)
                        }
                        is OrchestratorEvent.RoutedTo -> {
                            routedId = event.providerId
                            edit(replyId) { it.copy(brain = names[event.providerId]) }
                        }
                        is OrchestratorEvent.FellBack -> {
                            val hint = com.joctaeng.jarvis.system.resources.RetryHint.millis(event.reason)
                            cooldown.note(event.fromProviderId, event.reason, System.currentTimeMillis(), hint)
                            // Gemini principal sem cota: visão e pesquisa passam para a reserva automática até a cota voltar.
                            if (event.reason.contains("(429)")) app.brains.noteGeminiLimited(event.fromProviderId, System.currentTimeMillis() + (hint ?: 15 * 60_000L))
                            app.events.warn("conversa", "${names[event.fromProviderId]} falhou (${event.reason}); tentando outro cérebro")
                        }
                        is OrchestratorEvent.Failed -> {
                            app.events.error("conversa", "nenhum cérebro respondeu: ${event.reason}")
                            emit(LlmChunk.Error(event.reason))
                        }
                        is OrchestratorEvent.Chunk -> emit(event.chunk)
                    }
                }
            }
        }

        val request = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        var firstChunkAt = 0L
        val runner = AgentRunner(app.toolbox.gateway(tools), maxRounds = 10)
        // Com ferramentas no prompt, o texto de cada tentativa fica guardado: só a resposta final é mostrada e falada.
        val toolsInPrompt = prompt.contains("<tools>")
        runner.run(history, generate, ToolContext(sessionId, "Pedido: ${request.take(120)}"), app.toolbox::confirm, holdToolRoundText = toolsInPrompt, verifyClaims = toolsInPrompt).collect { event ->
            when (event) {
                is AgentEvent.Text -> {
                    if (firstChunkAt == 0L) firstChunkAt = System.nanoTime()
                    var visible = parser.feed(event.text)
                    parser.emotion?.let { OverlayBus.emotion.value = it }
                    if (visible.isNotEmpty()) {
                        if (newRound) {
                            val current = _entries.value.firstOrNull { it.id == replyId }?.text.orEmpty()
                            if (current.isNotEmpty() && !current.last().isWhitespace()) visible = " " + visible.trimStart()
                            newRound = false
                        }
                        OverlayBus.anim.value = AnimState.IDLE
                        edit(replyId) { it.copy(text = it.text + visible) }
                        if (speak || OverlayBus.acting.value) chunker.feed(visible).forEach(voice::speak)
                    }
                }
                is AgentEvent.Aside -> app.events.info("conversa", "não falado: ${event.text.take(200)}")
                is AgentEvent.ToolStarted -> {
                    OverlayBus.anim.value = AnimState.THINKING
                    steps++
                    val label = "$STEP_NOTE (${steps} ${if (steps == 1) "passo" else "passos"}: ${event.call.toolName.replace('_', ' ')})"
                    val id = stepNoteId
                    if (id == null) stepNoteId = addNoteBefore(replyId, label) else edit(id) { it.copy(text = label) }
                }
                is AgentEvent.ToolFinished -> {
                    newRound = true
                    // A rodada seguinte começa de novo com a etiqueta de emoção ([confuso]...): ela não pode virar texto.
                    parser = StreamingEmotionParser()
                    // O detalhe de cada tentativa vai para o relatório, não para a conversa.
                    app.events.info("ferramentas", "${event.call.toolName}: ${describe(event.result).take(200)}")
                    if (event.result is ToolResult.Success) okSteps += com.joctaeng.jarvis.mind.memory.RecipeStep(event.call.toolName, event.call.argumentsJson)
                }
                is AgentEvent.Error -> {
                    failure = event.message
                    app.events.error("conversa", "erro no agente: ${event.message}")
                }
                AgentEvent.Done -> Unit
            }
        }
        // Medição para diagnosticar a lentidão: tamanho do prompt, tempo até a 1ª palavra e até o fim.
        app.diagnostics.append(
            Poc.LOCAL_LLM, "event" to "turn", "provider" to (providers.firstOrNull()?.id ?: "none"),
            "prompt_chars" to prompt.length, "history_msgs" to history.size,
            "tools_in_prompt" to (if (tools.isNotEmpty() && prompt.contains("<tools>")) tools.size else 0),
            "first_ms" to (if (firstChunkAt == 0L) -1L else (firstChunkAt - startedAt) / 1_000_000),
            "total_ms" to (System.nanoTime() - startedAt) / 1_000_000,
        )
        app.events.info(
            "conversa",
            "turno: cérebro=${_entries.value.firstOrNull { it.id == replyId }?.brain ?: providers.firstOrNull()?.displayName ?: "nenhum"}; prompt=${prompt.length} car.; histórico=${history.size}; " +
                "ferramentas no prompt=${if (prompt.contains("<tools>")) tools.size else 0}; " +
                "1ª palavra=${if (firstChunkAt == 0L) "nunca" else "${(firstChunkAt - startedAt) / 1_000_000} ms"}; total=${(System.nanoTime() - startedAt) / 1_000_000} ms",
        )
        val rest = parser.finish()
        if (rest.isNotEmpty()) {
            edit(replyId) { it.copy(text = it.text + rest) }
            if (speak || OverlayBus.acting.value) chunker.feed(rest).forEach(voice::speak)
        }
        if (speak || OverlayBus.acting.value) chunker.flush()?.let(voice::speak)

        edit(replyId) { it.copy(text = TextCleanup.clean(it.text).trim()) }
        val finalText = _entries.value.firstOrNull { it.id == replyId }?.text.orEmpty()
        record(Role.ASSISTANT, finalText)
        val emptyFrom = routedId
        if (failure == null && finalText.isBlank() && !retried && emptyFrom != null && providers.size > 1) {
            // Alguns cérebros (ex.: gpt-oss no Groq grátis) voltam vazios em pedidos com ferramentas: ele descansa
            // um pouco e o próximo da lista responde agora, sem pedir para o usuário repetir.
            cooldown.rest(emptyFrom, EMPTY_REST_MS, System.currentTimeMillis())
            if (prompt.contains("<tools>")) toolWeak += emptyFrom
            app.events.warn("conversa", "${names[emptyFrom]} voltou vazio; ele descansa ${EMPTY_REST_MS / 1000} s e o próximo cérebro responde")
            _entries.update { list -> list.filterNot { it.id == replyId || it.id == stepNoteId } }
            respond(speak, retried = true)
            return
        }
        if (failure == null && finalText.isBlank()) {
            // Antes ficava um balão vazio, sem aviso nem registro.
            app.events.error("conversa", "o cérebro terminou sem texto (1ª palavra=${if (firstChunkAt == 0L) "nunca" else "sim"}); pedido de ${request.length} caracteres")
            edit(replyId) { it.copy(text = "Não recebi resposta do cérebro desta vez. Pode repetir?", streaming = false) }
            record(Role.ASSISTANT, "Não recebi resposta do cérebro desta vez. Pode repetir?")
            app.engineStats.note(EngineStats.Path.FAILED, (System.nanoTime() - startedAt) / 1_000_000)
            OverlayBus.emotion.value = Emotion.CONFUSED
            if (speak) voice.speak("Não recebi resposta desta vez. Pode repetir?")
        } else if (failure != null && finalText.isBlank()) {
            // Dizer o que houve e o que ainda funciona (antes: "todos os cérebros falharam"), e GRAVAR a fala (antes ficava de fora da conversa exportada).
            val localConfigured = settings.localModelPath.isNotBlank()
            val text = FailureText.build(failure.orEmpty(), device.online, providers.any { it.location == ProviderLocation.ON_DEVICE }, localConfigured)
            edit(replyId) { it.copy(text = text, streaming = false, brain = null) }
            record(Role.ASSISTANT, text)
            app.events.error("conversa", "sem resposta de IA: ${failure.orEmpty().take(300)}")
            app.engineStats.note(EngineStats.Path.FAILED, (System.nanoTime() - startedAt) / 1_000_000)
            OverlayBus.emotion.value = Emotion.CONCERNED
            if (speak) voice.speak(FailureText.spoken(device.online))
        } else {
            edit(replyId) { it.copy(streaming = false) }
            app.engineStats.note(EngineStats.Path.LEGACY, (System.nanoTime() - startedAt) / 1_000_000)
            if (failure != null) add(ChatEntry(nextId++, Role.SYSTEM, "Resposta interrompida: $failure", note = true))
            else if (!settings.privateMode) runCatching { app.recipes.record(request, okSteps) }
        }
    }

    /**
     * Executa um atalho direto pela ferramenta (com política, confirmação e registro, como qualquer ação).
     * Se não der (app não achado, controle desligado), devolve false e o cérebro cuida do pedido normalmente.
     */
    private suspend fun runQuick(cmd: com.joctaeng.jarvis.mind.persona.QuickCommand, speak: Boolean): Boolean {
        if (settings.autonomy == AutonomyLevel.OBSERVER) return false
        val (tool, args, done) = when (cmd) {
            is com.joctaeng.jarvis.mind.persona.QuickCommand.OpenApp -> Triple("abrir_app", org.json.JSONObject().put("nome", cmd.name), "Pronto, ${cmd.name} aberto.")
            com.joctaeng.jarvis.mind.persona.QuickCommand.Home -> Triple("tela_navegar", org.json.JSONObject().put("acao", "inicio"), "Pronto, tela inicial.")
            com.joctaeng.jarvis.mind.persona.QuickCommand.Back -> Triple("tela_navegar", org.json.JSONObject().put("acao", "voltar"), "Voltei.")
            is com.joctaeng.jarvis.mind.persona.QuickCommand.Scroll -> Triple(
                "tela_rolar",
                org.json.JSONObject().put("direcao", when (cmd.direction) { "proximo" -> "direita"; "anterior" -> "esquerda"; else -> cmd.direction }),
                "Pronto.",
            )
        }
        val tools = app.toolbox.enabledTools()
        if (tools.none { it.name == tool }) return false
        val call = com.joctaeng.jarvis.core.model.ToolCall("atalho-${nextId}", tool, args.toString())
        val result = app.toolbox.gateway(tools).execute(call, ToolContext(sessionId, "Atalho: $tool"), confirm = app.toolbox::confirm)
        app.events.info("conversa", "atalho sem cérebro: $tool → ${describe(result).take(160)}")
        if (result !is ToolResult.Success) return false
        say(done, Emotion.HAPPY, speak)
        return true
    }

    /** O que está ligado agora, para o Euno não negar recursos que tem (ex.: dizia que não podia ser chamado pelo nome). */
    private fun conversationFeatures(): List<String> = buildList {
        if (settings.wakeWord) add("o usuário pode te chamar dizendo \"Oi ${settings.wakeNames.joinToString("\" ou \"Oi ")}\" com a tela ligada (você ouve o chamado, não a conversa toda)")
        else add("chamado por voz (\"Oi ${settings.wakeName}\") existe mas está desligado em Ajustes → Conversa")
        if (settings.captionMode) add("ao tocar em você, aparece só uma legenda no pé da tela, sem abrir o chat")
        if (settings.bargeIn) add("você ouve comandos como \"pera aí\" e \"tchau\" enquanto fala")
        add("mensagens enviadas enquanto você responde entram numa fila e são respondidas juntas")
        if (settings.phoneControl && com.joctaeng.jarvis.control.EunoAccessibilityService.isDeclared(app)) {
            add(if (com.joctaeng.jarvis.control.EunoAccessibilityService.instance != null) "controle do celular LIGADO: você pode ler a tela, VER o print da tela (tela_ver, entende onde fica cada coisa) e tocar (pelo nome ou no ponto), digitar, rolar até achar e navegar com as ferramentas tela_*; antes de agir você vai sozinho para o canto"
            else "controle do celular permitido, mas o serviço de acessibilidade ainda não está ligado no Android")
        } else add("controle do celular por acessibilidade ainda não está disponível neste APK de teste")
        add(if (com.joctaeng.jarvis.control.EunoNotificationListener.isEnabled(app)) "acesso às notificações LIGADO: notificacoes_ler mostra o que chegou"
            else "acesso às notificações desligado (o usuário liga em Meu Euno → Notificações e Gmail)")
        add(if (settings.gmailConnected) "Gmail CONECTADO (só leitura): gmail_buscar e gmail_ler" else "Gmail ainda não conectado (Meu Euno → Notificações e Gmail → Conectar Gmail)")
        add("o botão Nova guarda um resumo da conversa e começa outra")
        add("tarefas programadas: ${app.tasks.all().count { it.enabled }} ligadas — rotinas que rodam sozinhas no horário (tarefa_criar/listar/pausar/apagar; Meu Euno → Tarefas programadas)")
        add("skills (habilidades): ${app.skills.all().count { it.enabled }} ligadas — ex.: modo aula, preparar aula, lista de exercícios, lista de compras, o que chegou, fechar o dia; o usuário ensina novas com \"quando eu disser X, faça Y\" ou em Meu Euno → Minhas skills")
        add("guardar fatos na memória só com as ferramentas memoria_guardar/memoria_esquecer ou quando ele diz \"lembre que…\"")
    }

    private fun describe(result: ToolResult): String = when (result) {
        is ToolResult.Success -> "feito"
        is ToolResult.Failure -> "falhou (${result.reason})"
        is ToolResult.Denied -> "não permitido (${result.reason})"
        ToolResult.Cancelled -> "cancelado"
    }

    private suspend fun handleMemory(command: MemoryCommand, speak: Boolean) {
        if (settings.privateMode) {
            say("Modo Privado ativo: não vou memorizar nem apagar memórias agora.", Emotion.NEUTRAL, speak)
            return
        }
        when (command) {
            is MemoryCommand.Remember -> {
                withContext(Dispatchers.IO) { app.memory.add(command.fact, source = "conversa", reason = "Você pediu para eu lembrar") }
                say("Pronto, vou lembrar: ${command.fact}", Emotion.HAPPY, speak)
            }
            is MemoryCommand.Forget -> {
                val forgotten = withContext(Dispatchers.IO) { app.memory.forget(command.query) }
                if (forgotten != null) {
                    say("Esqueci: ${forgotten.text}", Emotion.NEUTRAL, speak)
                } else {
                    say("Não encontrei nada parecido na minha memória.", Emotion.CONFUSED, speak)
                }
            }
        }
    }

    private fun say(text: String, emotion: Emotion, speak: Boolean, brain: String? = null) {
        add(ChatEntry(nextId++, Role.ASSISTANT, text, brain = brain ?: settings.displayName))
        OverlayBus.emotion.value = emotion
        if (speak) voice.speak(text)
    }

    /** Depois de alguns segundos, a expressão volta ao neutro (decaimento, seção 6.3). */
    private fun relax() {
        OverlayBus.anim.value = AnimState.IDLE
        calmDown?.cancel()
        calmDown = scope.launch {
            delay(6_000)
            OverlayBus.emotion.value = Emotion.NEUTRAL
        }
    }

    /** Cérebro do celular configurado (para o botão de teste em Meu Euno). */
    fun localBrain(): LlmProvider? = localProvider()

    private fun localProvider(): LocalLlmProvider? {
        val path = settings.localModelPath
        if (path.isBlank() || !File(path).isFile) return null
        val key = "$path|${settings.localBackend}"
        if (key != localKey) {
            local?.let { old -> scope.launch { old.unload() } }
            local = buildLocal(File(path), settings.localBackend)
            localKey = key
        }
        return local
    }

    private fun buildLocal(file: File, backend: com.joctaeng.jarvis.mind.local.LocalBackend): LocalLlmProvider =
        if (ModelStore.isGguf(file)) LlamaCppProvider(file) { app.events.info("llama", it) }
        else LiteRtLmProvider(file, backend, app.cacheDir, label = app.modelDownload.catalog.byFile(file.name)?.name) { app.events.info("litertlm", it) }

    /** Nome do modelo do celular configurado agora (catálogo ou nome do arquivo); vazio = desligado. */
    fun activeLocalName(): String {
        val path = settings.localModelPath
        if (path.isBlank()) return ""
        val f = File(path)
        return app.modelDownload.catalog.byFile(f.name)?.name ?: f.nameWithoutExtension
    }

    /** O que o motor carregado diz agora (nome, processador/placa, o que lê); null se nada carregado. */
    fun loadedLocalDescription(): String? {
        val p = local ?: return null
        return when (p) {
            is LiteRtLmProvider -> if (!p.isLoaded) null else buildString {
                append(p.displayName)
                if (p.nativeToolsReady) append(" · ferramentas nativas")
                if (p.visionReady) append(" · lê imagens")
                if (p.audioReady) append(" · ouve áudio")
            }
            is LlamaCppProvider -> if (p.isLoaded) p.displayName else null
            else -> null
        }
    }

    /**
     * Troca o modelo do celular de verdade (botão Salvar do catálogo): libera o anterior da memória, abre o novo, faz
     * uma geração curta de teste e só então grava a escolha. Se falhar, o anterior continua sendo o escolhido.
     * [path] vazio = desligar o cérebro do celular.
     */
    suspend fun switchLocalModel(path: String, backend: com.joctaeng.jarvis.mind.local.LocalBackend): com.joctaeng.jarvis.mind.models.SwitchOutcome =
        withContext(Dispatchers.Main) {
            val previous = activeLocalName()
            // Dois modelos de gigabytes juntos podem estourar a memória: o anterior sai primeiro.
            local?.let { old -> runCatching { withContext(Dispatchers.IO) { old.unload() } } }
            local = null
            localKey = null
            if (path.isBlank()) {
                settings.localModelPath = ""
                app.events.info("modelo", "cérebro do celular desligado (antes: ${previous.ifBlank { "nenhum" }})")
                return@withContext com.joctaeng.jarvis.mind.models.SwitchOutcome.Done(
                    com.joctaeng.jarvis.mind.models.EngineReport("", "", "", false, false, false, "", 0),
                )
            }
            val file = File(path)
            if (!file.isFile) return@withContext com.joctaeng.jarvis.mind.models.SwitchOutcome.Failed("o arquivo ${file.name} não está no celular", previous)
            val candidate = buildLocal(file, backend)
            try {
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    when (candidate) {
                        is LiteRtLmProvider -> candidate.load()
                        is LlamaCppProvider -> candidate.load()
                        else -> Unit
                    }
                }
                val loadMs = System.currentTimeMillis() - t0
                val reply = StringBuilder()
                var error: String? = null
                kotlinx.coroutines.withTimeout(120_000) {
                    candidate.generate(LlmRequest("Responda em português, em uma frase curta.", listOf(ChatMessage(Role.USER, "Diga oi.")), 24)).collect {
                        when (it) {
                            is LlmChunk.Text -> reply.append(it.text)
                            is LlmChunk.Error -> error = it.message
                            else -> Unit
                        }
                    }
                }
                error?.let { throw IllegalStateException(it) }
                if (reply.isBlank()) throw IllegalStateException("o modelo abriu mas não respondeu ao teste")
                settings.localModelPath = path
                settings.localBackend = backend
                local = candidate
                localKey = "$path|$backend"
                val lite = candidate as? LiteRtLmProvider
                val report = com.joctaeng.jarvis.mind.models.EngineReport(
                    fileName = file.name,
                    displayName = app.modelDownload.catalog.byFile(file.name)?.name ?: file.nameWithoutExtension,
                    backend = lite?.backendLabel() ?: "CPU, llama.cpp",
                    vision = lite?.visionReady == true,
                    audio = lite?.audioReady == true,
                    nativeTools = lite?.nativeToolsReady == true,
                    testReply = TextCleanup.clean(reply.toString()),
                    loadMillis = loadMs,
                )
                app.events.info("modelo", "troca concluída: ${report.displayName} (${report.backend}); visão=${report.vision} áudio=${report.audio} nativas=${report.nativeTools}; antes: ${previous.ifBlank { "nenhum" }}" + (lite?.mediaNote?.let { "; $it" } ?: ""))
                com.joctaeng.jarvis.mind.models.SwitchOutcome.Done(report)
            } catch (e: Exception) {
                runCatching { withContext(Dispatchers.IO) { candidate.unload() } }
                val reason = if (e is kotlinx.coroutines.TimeoutCancellationException) "o teste passou de 2 minutos sem resposta" else (e.message ?: e.javaClass.simpleName)
                app.events.warn("modelo", "troca para ${file.name} falhou: $reason; continua: ${previous.ifBlank { "nenhum" }}")
                com.joctaeng.jarvis.mind.models.SwitchOutcome.Failed(reason, previous)
            }
        }

    /**
     * Cérebro para uma mensagem com imagem/áudio: o modelo do celular, se ele lê isso. Senão, diz com honestidade o que
     * falta e devolve null (a conversa não finge que viu a imagem).
     */
    private suspend fun mediaProvider(media: List<com.joctaeng.jarvis.core.model.Attachment>, speak: Boolean): LlmProvider? {
        val p = localProvider() as? LiteRtLmProvider
        if (p != null) runCatching { withContext(Dispatchers.IO) { p.load() } }.onFailure { app.events.warn("conversa", "modelo do celular não abriu para o anexo", it) }
        val missing = com.joctaeng.jarvis.mind.models.AttachmentTypes.missing(media, p?.visionReady == true, p?.audioReady == true)
        if (p != null && missing == null) return p
        val who = activeLocalName().ifBlank { "nenhum modelo do celular escolhido" }
        val text = "Não consigo $missing com o cérebro atual ($who): os cérebros online do Euno recebem só texto. " +
            "Em Meu Euno → Cérebro → Catálogo de modelos, baixe e salve um que leia isso (Gemma 4 E2B ou E4B). PDF, Word, TXT e CSV eu leio com qualquer cérebro."
        say(text, Emotion.CONCERNED, speak)
        return null
    }

    /**
     * Cérebro online: ferramentas SEMPRE no prompt. Antes elas só iam quando a frase "parecia pedido de ação" e, num
     * "e agora?" ou "continua", o cérebro ficava sem mãos e dizia que não sabia fazer (relatório da 0.24, pedido 69).
     * Cérebro do celular (lento): só quando o último pedido sugere ação, para a leitura caber.
     */
    private fun toolsNeeded(compact: Boolean): Boolean {
        if (!compact || OverlayBus.acting.value) return true
        val last = _entries.value.lastOrNull { it.role == Role.USER && !it.note }?.text ?: return false
        return ToolIntent.likely(last) || app.skills.match(last) != null
    }

    private fun add(entry: ChatEntry) {
        _entries.update { it + entry }
        if (!entry.streaming) record(entry.role, entry.text, entry.note)
    }

    /** Grava o turno em disco (um arquivo por dia) para poder exportar a conversa inteira; o modo privado não grava. */
    private fun record(role: Role, text: String, note: Boolean = false) {
        if (note || settings.privateMode || text.isBlank()) return
        when (role) {
            Role.USER -> app.transcripts.append("Eu", text)
            Role.ASSISTANT -> app.transcripts.append("Euno", text)
            else -> Unit
        }
    }

    private fun addNoteBefore(id: Long, text: String): Long {
        val noteId = nextId++
        _entries.update { list ->
            val index = list.indexOfFirst { it.id == id }.let { if (it < 0) list.size else it }
            list.toMutableList().apply { add(index, ChatEntry(noteId, Role.SYSTEM, text, note = true)) }
        }
        return noteId
    }

    private fun edit(id: Long, change: (ChatEntry) -> ChatEntry) =
        _entries.update { list -> list.map { if (it.id == id) change(it) else it } }

    private companion object {
        const val HISTORY = 20
        /** Cérebro local: menos histórico e dossiê menor, para o prefill caber num celular comum. */
        const val LOCAL_HISTORY = 8
        /** Teto do prompt do cérebro do celular (≈3,5 caracteres por token; sobra espaço para o histórico e a resposta). */
        const val LOCAL_PROMPT_MAX_CHARS = 9_000
        const val LEAN_HISTORY = 10
        const val LOCAL_BIO_CHARS = 800
        const val LOCAL_SUMMARY_CHARS = 800
        const val STEP_NOTE = "Agindo"
        /** Retrato sincronizado no prompt: nuvem e celular (este cabe na janela de 4.096 tokens). */
        const val LIVE_CHARS = 1_600
        /** Nuvem com prompt enxuto: ferramentas do pedido (no máximo 12) e resumo curto da conversa anterior. */
        const val LEAN_TOOLS = 12
        const val SUMMARY_CHARS = 600
        const val LOCAL_LIVE_CHARS = 500
        /** Descanso do cérebro que voltou vazio. */
        const val EMPTY_REST_MS = 90_000L
        /** Texto de documento anexado que cabe no cérebro do celular (janela pequena). */
        const val LOCAL_DOC_CHARS = 4_000
    }
}

/** Histórico do cérebro do celular: por tamanho (≈3.000 caracteres), não só por número de mensagens. */
private fun localHistory(messages: List<ChatMessage>, keep: Int = 8): List<ChatMessage> =
    HistoryTrim.keepRecent(messages, { it.text.length }, 3_000, keep)

/** Cérebro com pouca cota por minuto: prompt com menos ferramentas e histórico mais curto (pedido 78). */
private class LeanProvider(private val inner: LlmProvider, private val leanPrompt: String, private val keep: Int) : LlmProvider by inner {
    override fun generate(request: LlmRequest): Flow<LlmChunk> = inner.generate(
        LlmRequest(leanPrompt, HistoryTrim.keepRecent(request.messages, { it.text.length }, 6_000, keep), request.maxOutputTokens ?: 1_024),
    )
}

/** Troca prompt e histórico do pedido por versões enxutas quando o cérebro é o do celular (leitura rápida). */
private class CompactProvider(private val inner: LlmProvider, private val compactPrompt: String, private val keep: Int) : LlmProvider by inner {
    override fun generate(request: LlmRequest): Flow<LlmChunk> = inner.generate(
        LlmRequest(compactPrompt, localHistory(request.messages, keep), request.maxOutputTokens ?: 512),
    )
}
