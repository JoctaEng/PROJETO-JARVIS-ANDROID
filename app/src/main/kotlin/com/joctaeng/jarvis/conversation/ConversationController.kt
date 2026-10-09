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
    private val sessionId = java.util.UUID.randomUUID().toString()

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

    fun send(text: String, speak: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
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
                val command = MemoryCommands.parse(clean)
                if (command != null) handleMemory(command, speak) else respond(speak)
                if (speak) voice.awaitIdle()
            } finally {
                _busy.value = false
                relax()
                if (queue.isEmpty()) _turnFinished.tryEmit(Unit) else drainQueue()
            }
        }
    }

    /** Junta o que chegou durante a resposta e responde de uma vez (a pessoa já viu cada mensagem no chat). */
    private fun drainQueue() {
        val merged = queue.joinToString(" ")
        val speak = queuedSpeak
        queue.clear()
        _entries.update { list -> list.map { if (it.queued) it.copy(queued = false) else it } }
        app.events.info("conversa", "respondendo as mensagens da fila juntas")
        start(merged, speak, addEntry = false)
    }

    /** Interrompe a resposta e a fala. */
    fun cancel() {
        job?.cancel()
        voice.stop()
        queue.clear()
        _entries.update { list -> list.filterNot { it.queued }.map { if (it.streaming) it.copy(streaming = false) else it } }
        OverlayBus.anim.value = AnimState.IDLE
    }

    fun clearConversation() {
        cancel()
        _entries.value = emptyList()
        useSummaries = false
    }

    /** Há o que resumir? (pelo menos uma fala do usuário). */
    fun hasConversation(): Boolean = _entries.value.any { it.role == Role.USER && !it.note }

    /** Começa do zero. [loadSummaries]: a nova conversa já leva em conta os resumos marcados em "Resumos de conversa". */
    fun newConversation(loadSummaries: Boolean) {
        clearConversation()
        useSummaries = loadSummaries
        app.events.info("conversa", "nova conversa (resumos=${if (loadSummaries) app.summaries.activeContext(PersonaEngine.MAX_SUMMARY_CHARS).length else 0} car.)")
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
                Orchestrator(providers).respond(request, device, RoutingHints(preference = settings.brainPreference)).collect { event ->
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
        val cloud = cloudProvider()
        val localWanted = cloud == null || settings.brainPreference == BrainPreference.LOCAL_FIRST ||
            settings.brainPreference == BrainPreference.LOCAL_ONLY || !DeviceState.snapshot(app, privateMode = false).online
        return listOfNotNull(cloud, if (localWanted) localProvider() else null)
    }

    private suspend fun respond(speak: Boolean) {
        val startedAt = System.nanoTime()
        val device = DeviceState.snapshot(app, privateMode = settings.privateMode)
        val providers = configuredProviders()
        if (providers.isEmpty()) {
            say(
                "Ainda não tenho um cérebro. Abra Meu Euno → Cérebro e configure uma API (por exemplo, Gemini) " +
                    "ou escolha um modelo local.",
                Emotion.CONFUSED, speak,
            )
            return
        }
        val onlyLocal = providers.all { it.location == ProviderLocation.ON_DEVICE } || settings.brainPreference == BrainPreference.LOCAL_ONLY
        val memories = app.memory.all().map { if (it.category == "geral") it.text else "(${it.category}) ${it.text}" }
        val lastUser = _entries.value.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val tools = if (settings.autonomy == AutonomyLevel.OBSERVER) emptyList() else app.toolbox.enabledTools()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val summaries = if (useSummaries) app.summaries.activeContext(if (onlyLocal) LOCAL_SUMMARY_CHARS else PersonaEngine.MAX_SUMMARY_CHARS) else ""

        // Dois prompts: o completo (nuvem) e o enxuto (celular). Cada cérebro recebe só o que cabe nele.
        fun buildPrompt(compact: Boolean): String = PersonaEngine.systemPrompt(
            userName = settings.userName,
            character = settings.character,
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
            toolsSection = if (tools.isEmpty() || (compact && !ToolIntent.likely(lastUser))) "" else ToolProtocol.systemSection(tools),
            userBio = if (compact) settings.userBio.take(LOCAL_BIO_CHARS) else settings.userBio,
            summaries = if (compact) summaries.take(LOCAL_SUMMARY_CHARS) else summaries,
            selfSection = SelfKnowledge.section(
                SelfInfo(
                    versionName = version,
                    brainNames = providers.map { it.displayName },
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

        val prompt = buildPrompt(onlyLocal)
        val effective = if (onlyLocal) providers else providers.map { p ->
            // Cérebro do celular no meio da lista (fallback): recebe prompt/histórico enxutos, não os da nuvem.
            if (p.location == ProviderLocation.ON_DEVICE) CompactProvider(p, buildPrompt(true), LOCAL_HISTORY) else p
        }
        val allHistory = _entries.value.filter { !it.note && !it.streaming }.map { ChatMessage(it.role, it.text) }
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

        val generate: (List<ChatMessage>) -> Flow<LlmChunk> = { messages ->
            flow {
                Orchestrator(effective).respond(LlmRequest(prompt, messages, maxTokens), device, RoutingHints(preference = settings.brainPreference)).collect { event ->
                    when (event) {
                        is OrchestratorEvent.Notice -> if (event.text != lastNote) {
                            lastNote = event.text
                            app.events.info("conversa", "aviso: ${event.text}")
                            addNoteBefore(replyId, event.text)
                        }
                        is OrchestratorEvent.RoutedTo -> edit(replyId) { it.copy(brain = names[event.providerId]) }
                        is OrchestratorEvent.FellBack -> {
                            app.events.warn("conversa", "${names[event.fromProviderId]} falhou (${event.reason}); tentando outro cérebro")
                            addNoteBefore(replyId, "${names[event.fromProviderId]} falhou (${event.reason}). Tentando outro cérebro…")
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
        val runner = AgentRunner(app.toolbox.gateway(tools))
        runner.run(history, generate, ToolContext(sessionId, "Pedido: ${request.take(120)}"), app.toolbox::confirm).collect { event ->
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
                        edit(replyId) { it.copy(text = TextCleanup.clean(it.text + visible)) }
                        if (speak) chunker.feed(visible).forEach(voice::speak)
                    }
                }
                is AgentEvent.ToolStarted -> {
                    OverlayBus.anim.value = AnimState.THINKING
                    addNoteBefore(replyId, "Executando: ${event.call.toolName.replace('_', ' ')}…")
                }
                is AgentEvent.ToolFinished -> {
                    newRound = true
                    // A rodada seguinte começa de novo com a etiqueta de emoção ([confuso]...): ela não pode virar texto.
                    parser = StreamingEmotionParser()
                    app.events.info("ferramentas", "${event.call.toolName}: ${describe(event.result).take(200)}")
                    addNoteBefore(replyId, "${event.call.toolName.replace('_', ' ')}: ${describe(event.result)}")
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
            "turno: cérebro=${providers.firstOrNull()?.id ?: "nenhum"}; prompt=${prompt.length} car.; histórico=${history.size}; " +
                "ferramentas no prompt=${if (prompt.contains("<tools>")) tools.size else 0}; " +
                "1ª palavra=${if (firstChunkAt == 0L) "nunca" else "${(firstChunkAt - startedAt) / 1_000_000} ms"}; total=${(System.nanoTime() - startedAt) / 1_000_000} ms",
        )
        val rest = parser.finish()
        if (rest.isNotEmpty()) {
            edit(replyId) { it.copy(text = it.text + rest) }
            if (speak) chunker.feed(rest).forEach(voice::speak)
        }
        if (speak) chunker.flush()?.let(voice::speak)

        edit(replyId) { it.copy(text = TextCleanup.clean(it.text).trim()) }
        val finalText = _entries.value.firstOrNull { it.id == replyId }?.text.orEmpty()
        if (failure == null && finalText.isBlank()) {
            // Antes ficava um balão vazio, sem aviso nem registro.
            app.events.error("conversa", "o cérebro terminou sem texto (1ª palavra=${if (firstChunkAt == 0L) "nunca" else "sim"}); pedido de ${request.length} caracteres")
            edit(replyId) { it.copy(text = "Não recebi resposta do cérebro desta vez. Pode repetir?", streaming = false) }
            OverlayBus.emotion.value = Emotion.CONFUSED
            if (speak) voice.speak("Não recebi resposta desta vez. Pode repetir?")
        } else if (failure != null && finalText.isBlank()) {
            edit(replyId) { it.copy(text = "Não consegui responder: $failure", streaming = false, brain = null) }
            OverlayBus.emotion.value = Emotion.CONCERNED
            if (speak) voice.speak("Não consegui responder agora.")
        } else {
            edit(replyId) { it.copy(streaming = false) }
            if (failure != null) add(ChatEntry(nextId++, Role.SYSTEM, "Resposta interrompida: $failure", note = true))
        }
    }

    /** O que está ligado agora, para o Euno não negar recursos que tem (ex.: dizia que não podia ser chamado pelo nome). */
    private fun conversationFeatures(): List<String> = buildList {
        if (settings.wakeWord) add("o usuário pode te chamar dizendo \"Oi ${settings.wakeName}\" com a tela ligada (você ouve o chamado, não a conversa toda)")
        else add("chamado por voz (\"Oi ${settings.wakeName}\") existe mas está desligado em Ajustes → Conversa")
        if (settings.captionMode) add("ao tocar em você, aparece só uma legenda no pé da tela, sem abrir o chat")
        if (settings.bargeIn) add("você ouve comandos como \"pera aí\" e \"tchau\" enquanto fala")
        add("mensagens enviadas enquanto você responde entram numa fila e são respondidas juntas")
        if (settings.phoneControl && com.joctaeng.jarvis.control.EunoAccessibilityService.isDeclared(app)) {
            add(if (com.joctaeng.jarvis.control.EunoAccessibilityService.instance != null) "controle do celular LIGADO: você pode ler a tela e tocar, digitar, rolar e navegar com as ferramentas tela_*"
            else "controle do celular permitido, mas o serviço de acessibilidade ainda não está ligado no Android")
        } else add("controle do celular por acessibilidade ainda não está disponível neste APK de teste")
        add("o botão Nova guarda um resumo da conversa e começa outra")
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

    private fun say(text: String, emotion: Emotion, speak: Boolean) {
        add(ChatEntry(nextId++, Role.ASSISTANT, text, brain = settings.displayName))
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

    private fun cloudProvider(): LlmProvider? {
        val preset = settings.cloudPreset
        if (preset == CloudPreset.NONE) return null
        val key = app.secrets.get(SecretStore.CLOUD_API_KEY)
        val base = settings.cloudBaseUrl.ifBlank { preset.baseUrl }
        if (base.isBlank() || settings.cloudModel.isBlank()) return null
        if (preset.keyRequired && key.isNullOrBlank()) return null
        return OpenAiCompatibleProvider(
            CloudConfig(
                id = "cloud",
                displayName = "${preset.label} · ${settings.cloudModel}",
                baseUrl = base,
                apiKey = key,
                model = settings.cloudModel,
                location = preset.location,
            ),
        )
    }

    /** Cérebro do celular configurado (para o botão de teste em Meu Euno). */
    fun localBrain(): LlmProvider? = localProvider()

    private fun localProvider(): LocalLlmProvider? {
        val path = settings.localModelPath
        if (path.isBlank() || !File(path).isFile) return null
        val key = "$path|${settings.localBackend}"
        if (key != localKey) {
            local?.let { old -> scope.launch { old.unload() } }
            val file = File(path)
            local = if (ModelStore.isGguf(file)) LlamaCppProvider(file) { app.events.info("llama", it) } else LiteRtLmProvider(file, settings.localBackend, app.cacheDir)
            localKey = key
        }
        return local
    }

    private fun add(entry: ChatEntry) = _entries.update { it + entry }

    private fun addNoteBefore(id: Long, text: String) = _entries.update { list ->
        val index = list.indexOfFirst { it.id == id }.let { if (it < 0) list.size else it }
        list.toMutableList().apply { add(index, ChatEntry(nextId++, Role.SYSTEM, text, note = true)) }
    }

    private fun edit(id: Long, change: (ChatEntry) -> ChatEntry) =
        _entries.update { list -> list.map { if (it.id == id) change(it) else it } }

    private companion object {
        const val HISTORY = 20
        /** Cérebro local: menos histórico e dossiê menor, para o prefill caber num celular comum. */
        const val LOCAL_HISTORY = 8
        const val LOCAL_BIO_CHARS = 800
        const val LOCAL_SUMMARY_CHARS = 800
    }
}

/** Histórico do cérebro do celular: por tamanho (≈3.000 caracteres), não só por número de mensagens. */
private fun localHistory(messages: List<ChatMessage>, keep: Int = 8): List<ChatMessage> =
    HistoryTrim.keepRecent(messages, { it.text.length }, 3_000, keep)

/** Troca prompt e histórico do pedido por versões enxutas quando o cérebro é o do celular (leitura rápida). */
private class CompactProvider(private val inner: LlmProvider, private val compactPrompt: String, private val keep: Int) : LlmProvider by inner {
    override fun generate(request: LlmRequest): Flow<LlmChunk> = inner.generate(
        LlmRequest(compactPrompt, localHistory(request.messages, keep), request.maxOutputTokens ?: 512),
    )
}
