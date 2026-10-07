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
) {
    NONE("Nenhum", "", ProviderLocation.EXTERNAL_CLOUD, false, "Sem cérebro online."),
    GEMINI(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        ProviderLocation.EXTERNAL_CLOUD, true, "Crie a chave em aistudio.google.com (Get API key).",
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

    /** Nome escolhido pelo usuário; vazio = nome padrão do personagem. */
    var characterName by string("characterName", "")
    var personaMode by enum("personaMode", PersonaMode.FRIENDLY)
    var brainPreference by enum("brainPreference", BrainPreference.ONLINE_FIRST)
    var cloudPreset by enum("cloudPreset", CloudPreset.NONE)
    var cloudBaseUrl by string("cloudBaseUrl", "")
    var cloudModel by string("cloudModel", "")
    var localModelPath by string("localModelPath", "")
    var localBackend by enum("localBackend", LocalBackend.GPU)
    var placementMode by enum("placementMode", PlacementMode.FREE)
    var characterSizeDp by int("characterSizeDp", 88)
    var ttsEngine by string("ttsEngine", "")
    var ttsVoice by string("ttsVoice", "")
    var ttsRate by float("ttsRate", 1.0f)
    var ttsPitch by float("ttsPitch", 1.0f)
    var listenOnOpen by boolean("listenOnOpen", true)
    var continuousVoice by boolean("continuousVoice", true)
    var speakReplies by boolean("speakReplies", true)
    var privateMode by boolean("privateMode", false)

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
