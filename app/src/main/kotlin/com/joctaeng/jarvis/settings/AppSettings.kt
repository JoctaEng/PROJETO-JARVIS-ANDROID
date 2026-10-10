package com.joctaeng.jarvis.settings

import android.content.Context
import android.content.SharedPreferences
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.mind.local.LocalBackend
import com.joctaeng.jarvis.mind.orchestrator.BrainPreference
import com.joctaeng.jarvis.character.CharacterArt
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.mind.persona.CharacterCatalog
import com.joctaeng.jarvis.mind.persona.PersonaMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Onde o personagem fica ao ser solto (item 3 da especificação). */
enum class PlacementMode(val label: String) {
    FREE("Livre — fica onde eu soltar"),
    EDGES("Grudar nas bordas"),
}

/**
 * Provedores online predefinidos. Todos usam o formato compatível com OpenAI.
 * O endereço pode ser editado; o modelo é escolhido pela lista do próprio provedor.
 */
enum class CloudPreset(
    val label: String,
    val baseUrl: String,
    val location: ProviderLocation,
    val keyRequired: Boolean,
    val help: String,
    /** Modelo sugerido ao escolher o provedor (o usuário pode trocar; "Listar modelos" mostra os disponíveis). */
    val suggestedModel: String = "",
) {
    NONE("Nenhum", "", ProviderLocation.EXTERNAL_CLOUD, false, "Sem cérebro online."),
    GEMINI(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        ProviderLocation.EXTERNAL_CLOUD, true, "Crie a chave em aistudio.google.com (Get API key).",
    ),
    GROQ(
        "Groq (grátis, muito rápido)", "https://api.groq.com/openai/v1", ProviderLocation.EXTERNAL_CLOUD, true,
        "Chave grátis em console.groq.com → API Keys.", "openai/gpt-oss-20b",
    ),
    CEREBRAS(
        "Cerebras (grátis, muito rápido)", "https://api.cerebras.ai/v1", ProviderLocation.EXTERNAL_CLOUD, true,
        "Chave grátis em cloud.cerebras.ai → API Keys.", "gpt-oss-120b",
    ),
    OPENAI("OpenAI", "https://api.openai.com/v1", ProviderLocation.EXTERNAL_CLOUD, true, "Chave em platform.openai.com."),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", ProviderLocation.EXTERNAL_CLOUD, true,
        "Uma chave dá acesso a vários modelos (openrouter.ai).",
    ),
    OWN_SERVER(
        "Meu servidor (Ollama/vLLM)", "http://192.168.0.10:11434/v1", ProviderLocation.OWN_SERVER, false,
        "Troque pelo IP do seu PC. Fora de casa, use uma VPN como Tailscale.",
    ),
    CUSTOM("Outro compatível com OpenAI", "", ProviderLocation.EXTERNAL_CLOUD, false, "Informe o endereço base (termina em /v1)."),
}

/** Configurações do usuário ("Meu Euno"). Segredos ficam no [SecretStore]. */
class AppSettings(context: Context) {
    val character get() = CharacterCatalog.byId(characterId.takeIf(CharacterArt::hasArt) ?: CharacterArt.DEFAULT_ID)
    val displayName get() = characterName.ifBlank { character.defaultName }

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Muda a cada alteração, para overlay e voz se atualizarem. */
    val version: StateFlow<Int> = _version.asStateFlow()

    var userName by string("userName", "Joctã")
    var characterId by string("characterId", CharacterArt.DEFAULT_ID)

    /** Dossiê e perfil configurado pelo professor ("Sobre Mim / README"). */
    var userBio by string("userBio", "")

    /** Nome escolhido pelo usuário; vazio = nome padrão do personagem. */
    var characterName by string("characterName", "")
    var personaMode by enum("personaMode", PersonaMode.FRIENDLY)
    var brainPreference by enum("brainPreference", BrainPreference.ONLINE_FIRST)

    /** Lista de cérebros online (v0.18): um por linha, na ordem de uso; as chaves ficam no SecretStore (BrainStore). */
    var brainSlotsRaw by string("brainSlots", "")
    var brainsMigrated by boolean("brainsMigrated", false)
    var cloudPreset by enum("cloudPreset", CloudPreset.NONE)
    var cloudBaseUrl by string("cloudBaseUrl", "")
    var cloudModel by string("cloudModel", "")

    /** Cérebro reserva: usado sozinho quando o principal falha ou bate o limite (chave no SecretStore). */
    var backupPreset by enum("backupPreset", CloudPreset.NONE)
    var backupBaseUrl by string("backupBaseUrl", "")
    var backupModel by string("backupModel", "")
    var localModelPath by string("localModelPath", "")
    var localBackend by enum("localBackend", LocalBackend.GPU)
    var placementMode by enum("placementMode", PlacementMode.FREE)
    var characterSizeDp by int("characterSizeDp", 88)
    /** Recolher automaticamente para o portal dimensional após período sem interação. */
    var autoPortalDismiss by boolean("autoPortalDismiss", true)
    var ttsEngine by string("ttsEngine", "")
    var ttsVoice by string("ttsVoice", "")
    var ttsRate by float("ttsRate", 1.0f)
    var ttsPitch by float("ttsPitch", 1.0f)
    var listenOnOpen by boolean("listenOnOpen", true)

    /** Ao tocar nele, conversar só com uma legenda (balão) em vez de abrir a janela de chat. */
    var captionMode by boolean("captionMode", true)

    /** Chamar pelo nome ("Oi Joca"): o microfone fica atento com o serviço do personagem ativo (gasta bateria). */
    var wakeWord by boolean("wakeWord", false)
    var wakeName by string("wakeName", "Joca")

    /** Controle do celular por acessibilidade (ler a tela, tocar, digitar). Além desta chave, o usuário liga o serviço no Android. */
    var phoneControl by boolean("phoneControl", false)

    /** Como estavam as chaves antes de "Habilitar tudo para teste completo" (para restaurar). Vazio = nada guardado. */
    var testSnapshot by string("testSnapshot", "")
    var continuousVoice by boolean("continuousVoice", true)
    var speakReplies by boolean("speakReplies", true)

    /** Quanto silêncio (ms) ele espera antes de considerar que você terminou de falar. */
    var listenPatienceMs by int("listenPatienceMs", 2800)

    /** Ouvir comandos (e, com fone, mensagens) enquanto ele fala. Experimental: o microfone pode competir com o áudio no alto-falante. */
    var bargeIn by boolean("bargeIn", false)

    /** Contador diário de pedidos à voz do Gemini (a conta gratuita tem limite por dia). */
    var geminiTtsDay by string("geminiTtsDay", "")
    var geminiTtsCount by int("geminiTtsCount", 0)
    /** Até quando a voz do Gemini descansa (cota esgotada): guardado para valer também depois de reabrir o app. */
    var geminiTtsSkipUntil by long("geminiTtsSkipUntil", 0L)
    /** Última falha da voz do Gemini (texto do erro, sem a chave), para o relatório mostrar a causa. */
    var geminiTtsLastError by string("geminiTtsLastError", "")
    /** Inclui o texto da conversa no relatório de erros (desligado por padrão: privacidade). */
    var reportIncludeChat by boolean("reportIncludeChat", false)
    /** Silencia por instantes o "bip" do reconhecimento de voz (volumes de sistema/notificação; não toca na música). */
    var muteMicBeep by boolean("muteMicBeep", true)
    var privateMode by boolean("privateMode", false)
    /** Marcado sozinho quando o Kokoro sintetiza bem mais devagar que o tempo real neste aparelho (modo Automático o evita). */
    var kokoroTooSlow by boolean("kokoroTooSlow", false)

    /** Voz: Automático = natural do Gemini quando houver internet e chave do Gemini; senão a do Android. */
    var voiceEngine by enum("voiceEngine", VoiceEngine.AUTO)
    /** Vazio = voz padrão do personagem. */
    var geminiVoice by string("geminiVoice", "")
    var geminiTtsModel by string("geminiTtsModel", "")
    /** -1 = voz Kokoro padrão do personagem. */
    var kokoroSpeaker by int("kokoroSpeaker", -1)

    /** Voz do Azure: região do recurso (ex.: brazilsouth) e nome da voz (vazio = padrão do personagem). A chave fica no SecretStore. */
    var azureRegion by string("azureRegion", "brazilsouth")
    var azureVoice by string("azureVoice", "")
    /** Contador do mês (AAAA-MM) de caracteres enviados ao Azure: o plano grátis tem limite mensal. */
    var azureMonth by string("azureMonth", "")
    var azureChars by int("azureChars", 0)
    /** Voz Piper offline escolhida (faber, cadu, jeff). */
    var piperVoice by string("piperVoice", "faber")

    /** Nível de autonomia (seção 10.2). Operador: consultas livres, ações pedem confirmação. */
    var autonomy by enum("autonomy", AutonomyLevel.OPERATOR)
    private var disabledToolsRaw by string("disabledTools", "")
    private var disabledMcpRaw by string("disabledMcpServers", "")

    /** Servidores MCP na rede, uma linha "nome|url" cada (PC, Tailscale...). */
    var networkMcpServersRaw by string("networkMcpServers", "")

    fun toolEnabled(name: String): Boolean = name !in disabledToolsRaw.split(',')
    fun setToolEnabled(name: String, enabled: Boolean) {
        val set = disabledToolsRaw.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (enabled) set -= name else set += name
        disabledToolsRaw = set.joinToString(",")
    }

    fun mcpServerEnabled(id: String): Boolean = id !in disabledMcpRaw.split(',')
    fun setMcpServerEnabled(id: String, enabled: Boolean) {
        val set = disabledMcpRaw.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (enabled) set -= id else set += id
        disabledMcpRaw = set.joinToString(",")
    }

    private fun string(key: String, default: String) = pref({ prefs.getString(key, default) ?: default }) { putString(key, it) }
    private fun long(key: String, default: Long) = pref({ prefs.getLong(key, default) }) { putLong(key, it) }
    private fun int(key: String, default: Int) = pref({ prefs.getInt(key, default) }) { putInt(key, it) }
    private fun float(key: String, default: Float) = pref({ prefs.getFloat(key, default) }) { putFloat(key, it) }
    private fun boolean(key: String, default: Boolean) = pref({ prefs.getBoolean(key, default) }) { putBoolean(key, it) }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) = pref(
        { prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default },
    ) { putString(key, it.name) }

    private fun <T> pref(read: () -> T, write: SharedPreferences.Editor.(T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                prefs.edit().apply { write(value) }.apply()
                _version.value++
            }
        }
}

enum class VoiceEngine(val label: String) {
    AUTO("Automático: Azure → Gemini → voz offline → Android"),
    AZURE("Azure (online, vozes brasileiras)"),
    GEMINI("Gemini (online)"),
    PIPER("Piper (offline, leve)"),
    KOKORO("Kokoro (offline, no celular)"),
    ANDROID("Voz do Android"),
}
