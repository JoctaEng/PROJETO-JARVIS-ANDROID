package com.joctaeng.jarvis

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.joctaeng.jarvis.conversation.ConversationController
import com.joctaeng.jarvis.diagnostics.Diagnostics
import com.joctaeng.jarvis.mind.memory.MemoryStore
import com.joctaeng.jarvis.poc.ModelDownload
import com.joctaeng.jarvis.tools.Toolbox
import com.joctaeng.jarvis.poc.ModelStore
import com.joctaeng.jarvis.settings.AppSettings
import com.joctaeng.jarvis.settings.SecretStore
import com.joctaeng.jarvis.voice.VoiceOutput
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import com.joctaeng.jarvis.system.resources.EventLog
import com.joctaeng.jarvis.system.resources.LogLevel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Composição manual de dependências (ver docs/ADR/0003-di-manual-na-fase-0.md).
 */
class JarvisApp : Application() {
    lateinit var diagnostics: Diagnostics
        private set
    lateinit var events: EventLog
        private set
    lateinit var modelStore: ModelStore
    lateinit var modelDownload: ModelDownload
        private set
    lateinit var embeddingDownload: com.joctaeng.jarvis.embed.EmbeddingDownload
        private set
    lateinit var embeddingRuntime: com.joctaeng.jarvis.embed.EmbeddingRuntime
        private set
    lateinit var embeddingIndex: com.joctaeng.jarvis.mind.embed.EmbeddingIndexStore
        private set
    lateinit var memoryIndexer: com.joctaeng.jarvis.mind.embed.MemoryIndexer
        private set
    lateinit var memoryRecall: com.joctaeng.jarvis.mind.embed.MemoryRecall
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var secrets: SecretStore
        private set
    val memory: MemoryStore by lazy {
        MemoryStore(File(filesDir, "memory/memory.json")).also { store ->
            store.listener = com.joctaeng.jarvis.mind.embed.IndexingMemoryListener(
                indexer = memoryIndexer,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                active = { settings.semanticMemory && embeddingRuntime.isAvailable },
            )
        }
    }
    val summaries: com.joctaeng.jarvis.mind.memory.SummaryStore by lazy { com.joctaeng.jarvis.mind.memory.SummaryStore(File(filesDir, "memory/resumos.json")) }
    val voice: VoiceOutput by lazy { VoiceOutput(this, settings, events) { brains.geminiKey() }.also { it.azureKey = { secrets.get(SecretStore.AZURE_SPEECH_KEY) }; it.start() } }
    /** Cérebros online cadastrados (vários, com ordem); na 1ª vez traz as chaves das versões anteriores. */
    val brains: com.joctaeng.jarvis.settings.BrainStore by lazy { com.joctaeng.jarvis.settings.BrainStore(settings, secrets).also { it.migrate() } }
    val conversation: ConversationController by lazy { ConversationController(this) }
    val transcripts: com.joctaeng.jarvis.system.resources.TranscriptStore by lazy { com.joctaeng.jarvis.system.resources.TranscriptStore(File(filesDir, "conversas")) }
    val voiceSession: com.joctaeng.jarvis.voice.VoiceSession by lazy { com.joctaeng.jarvis.voice.VoiceSession(this) }
    val toolbox: Toolbox by lazy { Toolbox(this) }
    /** Contagem de quem resolveu cada pedido: motor próprio, IA tradutora ou caminho antigo (v0.29). */
    val engineStats: com.joctaeng.jarvis.conversation.EngineStats by lazy { com.joctaeng.jarvis.conversation.EngineStats(this) }

    /** Caminhos que deram certo em cada app (memória de caminhos, v0.26). */
    val recipes: com.joctaeng.jarvis.mind.memory.RecipeBook by lazy { com.joctaeng.jarvis.mind.memory.RecipeBook(File(filesDir, "memory/caminhos.json")) }
    /** Tarefas programadas (v0.28). */
    val tasks: com.joctaeng.jarvis.mind.memory.TaskBook by lazy { com.joctaeng.jarvis.mind.memory.TaskBook(File(filesDir, "memory/tarefas.json")) }
    /** Skills (v0.27): as prontas + as que o usuário ensina. */
    val skills: com.joctaeng.jarvis.mind.memory.SkillBook by lazy { com.joctaeng.jarvis.mind.memory.SkillBook(File(filesDir, "memory/skills.json")) }
    /** Sincronização contínua: agenda, clima e e-mails lidos sozinhos (0.31). */
    val sync: com.joctaeng.jarvis.sync.EunoSync by lazy { com.joctaeng.jarvis.sync.EunoSync(this) }
    private val embedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Relatórios só da versão atual (pedido 86): o que a versão anterior gravou vai para o arquivo.
        val versionNow = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val versionPrefs = getSharedPreferences("euno_versao", MODE_PRIVATE)
        com.joctaeng.jarvis.system.resources.VersionArchive.archiveIfNewVersion(filesDir, versionPrefs.getString("ultima", null), versionNow)
        versionPrefs.edit().putString("ultima", versionNow).apply()
        diagnostics = Diagnostics(this)
        // O registro antigo (um arquivo só) vira o dia mais antigo, para não ficar fora de ordem no relatório.
        File(filesDir, "logs/euno-eventos.log").takeIf { it.isFile }?.renameTo(File(filesDir, "logs/euno-2000-01-01.log"))
        events = EventLog.daily(File(filesDir, "logs"))
        installCrashLogging()
        modelStore = ModelStore(this)
        modelDownload = ModelDownload(this, modelStore)
        settings = AppSettings(this)
        secrets = SecretStore(this)

        embeddingDownload = com.joctaeng.jarvis.embed.EmbeddingDownload(this, modelStore.directory)
        embeddingRuntime = com.joctaeng.jarvis.embed.EmbeddingRuntime(embeddingDownload.target, embedScope, dims = 256)
        embeddingIndex = com.joctaeng.jarvis.mind.embed.EmbeddingIndexStore(
            file = File(filesDir, "memory/embeddings.json"),
            modelId = embeddingRuntime.modelId,
            dims = 256,
        )
        memoryIndexer = com.joctaeng.jarvis.mind.embed.MemoryIndexer(
            index = embeddingIndex,
            embedder = embeddingRuntime,
            privateMode = { settings.privateMode },
        )
        memoryRecall = com.joctaeng.jarvis.mind.embed.MemoryRecall(
            memories = { memory.all() },
            index = embeddingIndex,
            enabled = { settings.semanticMemory },
            embedder = { if (embeddingRuntime.isAvailable) embeddingRuntime else null },
            // 0.30.0 mostrou medições zeradas sem dizer por quê: agora cada queda para a busca por palavras fica no relatório.
            onNote = { events.info("memoria", it) },
        )

        // Alarmes das tarefas programadas (o Android os apaga quando o app é atualizado).
        runCatching { com.joctaeng.jarvis.tasks.TaskScheduler.rescheduleAll(this) }
        // Sincronização contínua (0.31): agenda, clima e e-mails a cada ~30 min, até o usuário desligar.
        runCatching { com.joctaeng.jarvis.sync.EunoSync.schedule(this) }
        sync.refreshIfStale("app aberto", maxAgeMin = 10)
    }

    /** Registra quedas do app e, na próxima abertura, por que o sistema encerrou o processo (memória, travamento, falha nativa). */
    private fun installCrashLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            events.log(LogLevel.CRASH, "app", "queda na thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        events.info("app", "iniciado: versão $version, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
        runCatching {
            val prefs = getSharedPreferences("euno_log", MODE_PRIVATE)
            val lastSeen = prefs.getLong("lastExitTs", 0L)
            val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 8)
            exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }.forEach {
                events.warn("sistema", "processo anterior encerrado: ${exitReason(it.reason)} (${it.description.orEmpty()}), importância ${it.importance}, memória ${it.pss / 1024} MB")
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExitTs", it).apply() }
        }
    }

    private fun exitReason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_ANR -> "ANR (app travado)"
        ApplicationExitInfo.REASON_CRASH -> "queda (exceção)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "queda nativa (motor de IA/voz)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "pouca memória (sistema fechou o app)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha ao iniciar"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "mudança de permissão"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recursos"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "fechado pelo usuário"
        ApplicationExitInfo.REASON_USER_STOPPED -> "parado pelo usuário"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependência morreu"
        ApplicationExitInfo.REASON_SIGNALED -> "encerrado por sinal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "saiu sozinho"
        else -> "outro ($code)"
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW: ainda entregue em Android 13/14.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Regra 3.3: aviso de memória baixa descarrega o modelo local na hora.
        // Só descarrega com memória realmente crítica. Antes, qualquer nível >= RUNNING_LOW (inclusive UI_HIDDEN, ao fechar a
        // conversa) tirava o modelo da memória e a próxima resposta levava ~1 minuto para recarregar 2,4 GB.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            events.warn("sistema", "memória crítica (nível $level): liberando o modelo local e de embeddings")
            conversation.releaseLocalModel()
            embedScope.launch {
                runCatching { embeddingRuntime.close() }
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            events.info("sistema", "aviso de memória (nível $level); modelo local mantido")
        }
    }

    companion object {
        fun from(context: Context): JarvisApp = context.applicationContext as JarvisApp
    }
}
