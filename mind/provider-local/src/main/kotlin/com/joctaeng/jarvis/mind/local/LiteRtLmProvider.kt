package com.joctaeng.jarvis.mind.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.joctaeng.jarvis.core.contracts.GenerationStats
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LocalLlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.AttachmentKind
import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.mind.models.NativeTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class LocalBackend { CPU, GPU }

/** O que o próprio arquivo `.litertlm` diz que sabe fazer (API Capabilities do LiteRT-LM). */
data class ModelProbe(val vision: Boolean, val audio: Boolean, val functionCalling: Boolean, val error: String? = null)

/**
 * Cérebro local via LiteRT-LM (seção 7.1). Carrega o modelo sob demanda e
 * permite descarregar a qualquer momento (regra "um modelo pesado por vez").
 *
 * Desde a 0.33: lê as capacidades do arquivo (visão, áudio, chamada de função); com visão/áudio abre os
 * codificadores de imagem/áudio e aceita anexos; com chamada de função registra as ferramentas no formato NATIVO
 * (a execução continua pelo ToolGateway: a chamada vira [LlmChunk.ToolRequest]). Se algo disso falhar, volta ao
 * jeito antigo (só texto, ferramentas pelo protocolo `<tool_call>`), sem ficar sem cérebro.
 *
 * @param modelFile arquivo `.litertlm` já presente no aparelho.
 * @param label nome bonito do modelo (do catálogo) para mostrar na conversa.
 */
class LiteRtLmProvider(
    private val modelFile: File,
    private val backend: LocalBackend,
    private val cacheDir: File,
    private val label: String? = null,
    private val nativeTools: Boolean = true,
    private val log: (String) -> Unit = {},
) : LocalLlmProvider {

    override val id: String = "local-litertlm-${backend.name.lowercase()}"
    override val displayName: String get() = "${label ?: modelFile.nameWithoutExtension} (no celular, ${backendLabel()})"
    override val location = ProviderLocation.ON_DEVICE
    override val capabilities: Set<Capability>
        get() = buildSet {
            add(Capability.TEXT)
            add(Capability.STREAMING)
            probe?.let {
                if (it.vision && visionOn) add(Capability.VISION)
                if (it.audio && audioOn) add(Capability.AUDIO)
                if (it.functionCalling) add(Capability.TOOLS)
            }
        }

    private val lock = Mutex()
    private var engine: Engine? = null
    @Volatile private var probe: ModelProbe? = null
    @Volatile private var visionOn = false
    @Volatile private var audioOn = false
    /** A chamada nativa falhou neste modelo: até descarregar, usa o protocolo de texto. */
    @Volatile private var nativeBroken = false

    val isLoaded: Boolean get() = engine != null
    val fileName: String get() = modelFile.name

    /** A GPU falhou ao carregar e o modelo está no processador (aparece no relatório). */
    @Volatile var fellBackToCpu = false
        private set

    /** Imagens/áudio desligados porque o codificador não abriu (aparece na confirmação da troca). */
    @Volatile var mediaNote: String? = null
        private set

    fun backendLabel(): String = when {
        backend == LocalBackend.CPU -> "CPU"
        fellBackToCpu -> "CPU, a GPU recusou"
        else -> "GPU"
    }

    val visionReady: Boolean get() = probe?.vision == true && visionOn
    val audioReady: Boolean get() = probe?.audio == true && audioOn
    val nativeToolsReady: Boolean get() = nativeTools && !nativeBroken && probe?.functionCalling == true

    override suspend fun isAvailable(context: DeviceContext): Boolean = modelFile.isFile

    /** Lê as capacidades do arquivo sem carregar o modelo inteiro (uma vez por provedor). */
    suspend fun probe(): ModelProbe = probe ?: withContext(Dispatchers.IO) {
        val p = runCatching {
            Capabilities(modelFile.absolutePath).use { c ->
                val m = c.inputModalities()
                ModelProbe(vision = m.vision, audio = m.audio, functionCalling = c.supportsFunctionCalling())
            }
        }.getOrElse { ModelProbe(false, false, false, error = (it.message ?: it.javaClass.simpleName).take(200)) }
        probe = p
        p
    }

    /** Carrega o modelo e devolve o tempo gasto em ms (0 se já estava carregado). */
    suspend fun load(): Long {
        val p = probe()
        return lock.withLock {
            if (engine != null) return@withLock 0L
            withContext(Dispatchers.IO) {
                val start = System.nanoTime()
                fun open(useGpu: Boolean, media: Boolean): Engine {
                    val config = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                        // Codificadores de imagem e áudio só quando o arquivo os tem (como no AI Edge Gallery: imagem na GPU, áudio na CPU).
                        visionBackend = if (media && p.vision) (if (useGpu) Backend.GPU() else Backend.CPU()) else null,
                        audioBackend = if (media && p.audio) Backend.CPU() else null,
                        cacheDir = cacheDir.absolutePath,
                    )
                    val created = Engine(config)
                    try {
                        created.initialize()
                    } catch (e: Exception) {
                        // close() num motor que não subiu lança "Engine is not initialized" e escondia o erro verdadeiro
                        // (teste da 0.28.1: o arquivo do Gemma 4 estava danificado e a tela só mostrava essa frase).
                        runCatching { created.close() }
                        throw e
                    }
                    visionOn = media && p.vision
                    audioOn = media && p.audio
                    return created
                }
                fun reason(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(300)
                // Com imagem/áudio primeiro; se o celular recusar os codificadores, abre só o texto (avisa em mediaNote).
                fun openBest(useGpu: Boolean): Engine {
                    if (!p.vision && !p.audio) return open(useGpu, media = false)
                    return runCatching { open(useGpu, media = true) }.getOrElse { mediaError ->
                        mediaNote = "imagem/áudio desligados: ${reason(mediaError)}"
                        log("LiteRT-LM: $mediaNote")
                        open(useGpu, media = false)
                    }
                }
                // Placa de vídeo primeiro (bem mais rápida); se o celular recusar, cai para o processador em vez de ficar sem cérebro.
                engine = if (backend == LocalBackend.GPU) {
                    runCatching { openBest(true) }.getOrElse { gpuError ->
                        runCatching { openBest(false) }.getOrElse { cpuError ->
                            throw IllegalStateException("GPU: ${reason(gpuError)} | CPU: ${reason(cpuError)}", cpuError)
                        }.also { fellBackToCpu = true }
                    }
                } else {
                    runCatching { openBest(false) }.getOrElse { throw IllegalStateException("CPU: ${reason(it)}", it) }
                }
                (System.nanoTime() - start) / 1_000_000
            }
        }
    }

    /** Libera a memória do modelo (chamado também em onTrimMemory e ao trocar de modelo). */
    override suspend fun unload() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
        nativeBroken = false
    }

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        load()
        val loaded = checkNotNull(engine) { "Modelo não carregado" }
        val lastUser = request.messages.lastOrNull { it.role == Role.USER }
            ?: error("Nenhuma mensagem do usuário no pedido")
        val history = request.messages.takeWhile { it !== lastUser }.mapNotNull { it.toLiteRt() }

        // Ferramentas nativas: o bloco <tools> sai do texto e as mesmas ferramentas entram no motor.
        val split = if (nativeToolsReady) NativeTools.split(request.systemPrompt) else null
        val nativeSpecs = split?.tools.orEmpty()
        fun config(native: Boolean) = ConversationConfig(
            systemInstruction = (if (native && split != null) split.prompt else request.systemPrompt).takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            initialMessages = history,
            tools = if (native) nativeSpecs.map { spec ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = spec.openApiJson()
                    // Nunca roda aqui: com automaticToolCalling=false o pedido volta para o Euno (ToolGateway).
                    override fun execute(paramsJsonString: String): String = "{\"erro\":\"executado pelo Euno\"}"
                })
            } else emptyList(),
            automaticToolCalling = false,
            maxOutputToken = request.maxOutputTokens,
        )
        val conversation = if (nativeSpecs.isNotEmpty()) {
            runCatching { loaded.createConversation(config(true)) }.getOrElse {
                nativeBroken = true
                log("LiteRT-LM: ferramentas nativas recusadas (${it.message?.take(160)}); usando <tool_call>")
                loaded.createConversation(config(false))
            }
        } else {
            loaded.createConversation(config(false))
        }

        val start = System.nanoTime()
        var firstChunkAt = -1L
        var chunks = 0
        var chars = 0
        var callIndex = 0
        val seenCalls = HashSet<String>()
        conversation.use { conv ->
            conv.sendMessageAsync(userContents(lastUser)).collect { message ->
                message.toolCalls.forEach { call ->
                    val args = NativeTools.argumentsJson(call.arguments)
                    if (seenCalls.add(call.name + args)) {
                        if (firstChunkAt < 0) firstChunkAt = System.nanoTime()
                        emit(LlmChunk.ToolRequest(ToolCall("nativa-${++callIndex}", call.name, args)))
                    }
                }
                val text = message.toString()
                if (text.isEmpty()) return@collect
                if (firstChunkAt < 0) firstChunkAt = System.nanoTime()
                chunks++
                chars += text.length
                emit(LlmChunk.Text(text))
            }
        }
        val end = System.nanoTime()
        emit(
            LlmChunk.Done(
                GenerationStats(
                    timeToFirstChunkMillis = if (firstChunkAt < 0) -1 else (firstChunkAt - start) / 1_000_000,
                    totalMillis = (end - start) / 1_000_000,
                    chunkCount = chunks,
                    charCount = chars,
                ),
            ),
        )
    }.catch { e ->
        emit(LlmChunk.Error(e.message ?: e::class.simpleName ?: "erro no modelo local", e))
    }.flowOn(Dispatchers.IO)

    /** Texto do usuário + imagens/áudios anexados (só os que este modelo lê; os outros já foram avisados antes). */
    private fun userContents(m: ChatMessage): Contents {
        val media: List<Content> = m.attachments.mapNotNull { a ->
            val path = a.path?.takeIf { File(it).isFile } ?: return@mapNotNull null
            when (a.kind) {
                AttachmentKind.IMAGE -> if (visionReady) Content.ImageFile(path) else null
                AttachmentKind.AUDIO -> if (audioReady) Content.AudioFile(path) else null
                AttachmentKind.DOCUMENT -> null
            }
        }
        if (media.isEmpty()) return Contents.of(m.text)
        return Contents.of(media + Content.Text(m.text))
    }

    private fun ChatMessage.toLiteRt(): Message? = when (role) {
        Role.USER -> Message.user(text)
        Role.ASSISTANT -> Message.model(text)
        Role.SYSTEM, Role.TOOL -> null
    }

    companion object {
        /** Versão da biblioteca no app (gradle/libs.versions.toml, `litertlm`): o catálogo confere a mínima de cada modelo. */
        const val RUNTIME_VERSION = "0.17.1"
    }
}
