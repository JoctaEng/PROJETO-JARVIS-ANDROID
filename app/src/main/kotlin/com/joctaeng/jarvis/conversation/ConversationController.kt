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
import com.joctaeng.jarvis.mind.persona.DismissCommands
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.mind.persona.MemoryCommand
import com.joctaeng.jarvis.mind.persona.MemoryCommands
import com.joctaeng.jarvis.mind.persona.PersonaEngine
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

    fun send(text: String, speak: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty() || _busy.value) return
        voice.stop()
        job = scope.launch {
            _busy.value = true
            add(ChatEntry(nextId++, Role.USER, clean))
            try {
                if (DismissCommands.matches(clean)) {
                    say("Até logo!", Emotion.HAPPY, speak)
                    if (speak) voice.awaitIdle()
                    OverlayBus.requestDismiss()
                    return@launch
                }
                val command = MemoryCommands.parse(clean)
                if (command != null) handleMemory(command, speak) else respond(speak)
                if (speak) voice.awaitIdle()
            } finally {
                _busy.value = false
                relax()
                _turnFinished.tryEmit(Unit)
            }
        }
    }

    /** Interrompe a resposta e a fala. */
    fun cancel() {
        job?.cancel()
        voice.stop()
        _entries.update { list -> list.map { if (it.streaming) it.copy(streaming = false) else it } }
        OverlayBus.anim.value = AnimState.IDLE
    }

    fun clearConversation() {
        cancel()
        _entries.value = emptyList()
    }

    /** Libera o modelo local da memória (onTrimMemory). */
    fun releaseLocalModel() {
        val provider = local ?: return
        scope.launch { provider.unload() }
    }

    fun configuredProviders(): List<LlmProvider> = listOfNotNull(cloudProvider(), localProvider())

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
        val memories = app.memory.all().map { it.text }
        val lastUser = _entries.value.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val tools = if (settings.autonomy == AutonomyLevel.OBSERVER) emptyList() else app.toolbox.enabledTools()
        val prompt = PersonaEngine.systemPrompt(
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
            compact = onlyLocal,
            // No cérebro local, o prompt precisa caber numa leitura rápida: ferramentas só quando o pedido sugere ação.
            toolsSection = if (tools.isEmpty() || (onlyLocal && !ToolIntent.likely(lastUser))) "" else ToolProtocol.systemSection(tools),
            userBio = if (onlyLocal) settings.userBio.take(LOCAL_BIO_CHARS) else settings.userBio,
            selfSection = SelfKnowledge.section(
                SelfInfo(
                    versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?",
                    brainNames = providers.map { it.displayName },
                    voiceName = settings.voiceEngine.name.lowercase(),
                    autonomyLabel = settings.autonomy.name.lowercase(),
                    toolNames = tools.map { it.name },
                    memoryCount = memories.size,
                    privateMode = settings.privateMode,
                ),
                compact = onlyLocal,
            ),
        )
        val history = _entries.value.filter { !it.note && !it.streaming }.takeLast(if (onlyLocal) LOCAL_HISTORY else HISTORY).map { ChatMessage(it.role, it.text) }
        val maxTokens = if (onlyLocal) 512 else null

        OverlayBus.anim.value = AnimState.THINKING
        OverlayBus.emotion.value = Emotion.THINKING
        val replyId = nextId++
        add(ChatEntry(replyId, Role.ASSISTANT, "", streaming = true))
        val parser = StreamingEmotionParser()
        val chunker = SentenceChunker()
        val names = providers.associate { it.id to it.displayName }
        var lastNote: String? = null
        var failure: String? = null
        var newRound = false

        val generate: (List<ChatMessage>) -> Flow<LlmChunk> = { messages ->
            flow {
                Orchestrator(providers).respond(LlmRequest(prompt, messages, maxTokens), device, RoutingHints(preference = settings.brainPreference)).collect { event ->
                    when (event) {
                        is OrchestratorEvent.Notice -> if (event.text != lastNote) {
                            lastNote = event.text
                            addNoteBefore(replyId, event.text)
                        }
                        is OrchestratorEvent.RoutedTo -> edit(replyId) { it.copy(brain = names[event.providerId]) }
                        is OrchestratorEvent.FellBack -> addNoteBefore(replyId, "${names[event.fromProviderId]} falhou (${event.reason}). Tentando outro cérebro…")
                        is OrchestratorEvent.Failed -> emit(LlmChunk.Error(event.reason))
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
                        edit(replyId) { it.copy(text = it.text + visible) }
                        if (speak) chunker.feed(visible).forEach(voice::speak)
                    }
                }
                is AgentEvent.ToolStarted -> {
                    OverlayBus.anim.value = AnimState.THINKING
                    addNoteBefore(replyId, "Executando: ${event.call.toolName.replace('_', ' ')}…")
                }
                is AgentEvent.ToolFinished -> {
                    newRound = true
                    addNoteBefore(replyId, "${event.call.toolName.replace('_', ' ')}: ${describe(event.result)}")
                }
                is AgentEvent.Error -> failure = event.message
                AgentEvent.Done -> Unit
            }
        }
        val rest = parser.finish()
        // Medição para diagnosticar a lentidão: tamanho do prompt, tempo até a 1ª palavra e até o fim.
        app.diagnostics.append(
            Poc.LOCAL_LLM, "event" to "turn", "provider" to (providers.firstOrNull()?.id ?: "none"),
            "prompt_chars" to prompt.length, "history_msgs" to history.size,
            "tools_in_prompt" to (if (tools.isNotEmpty() && prompt.contains("<tools>")) tools.size else 0),
            "first_ms" to if (firstChunkAt == 0L) -1 else (firstChunkAt - startedAt) / 1_000_000,
            "total_ms" to (System.nanoTime() - startedAt) / 1_000_000,
        )
        val rest = parser.finish()
        if (rest.isNotEmpty()) {
            edit(replyId) { it.copy(text = it.text + rest) }
            if (speak) chunker.feed(rest).forEach(voice::speak)
        }
        if (speak) chunker.flush()?.let(voice::speak)

        val finalText = _entries.value.firstOrNull { it.id == replyId }?.text.orEmpty()
        if (failure != null && finalText.isBlank()) {
            edit(replyId) { it.copy(text = "Não consegui responder: $failure", streaming = false, brain = null) }
            OverlayBus.emotion.value = Emotion.CONCERNED
            if (speak) voice.speak("Não consegui responder agora.")
        } else {
            edit(replyId) { it.copy(streaming = false) }
            if (failure != null) add(ChatEntry(nextId++, Role.SYSTEM, "Resposta interrompida: $failure", note = true))
        }
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
            local = if (ModelStore.isGguf(file)) LlamaCppProvider(file) else LiteRtLmProvider(file, settings.localBackend, app.cacheDir)
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
    }
}
