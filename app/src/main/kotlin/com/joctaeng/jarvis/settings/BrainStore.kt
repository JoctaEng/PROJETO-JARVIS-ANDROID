package com.joctaeng.jarvis.settings

import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.mind.cloud.CloudConfig
import com.joctaeng.jarvis.mind.cloud.OpenAiCompatibleProvider
import com.joctaeng.jarvis.mind.orchestrator.BrainSlot
import com.joctaeng.jarvis.mind.orchestrator.BrainSlots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Os cérebros online do usuário: vários ao mesmo tempo (Gemini, Groq, Cerebras...), cada um com chave, modelo, teste e
 * posição na ordem. As duas telas (Meu Euno → Cérebro e Configurar IA e voz) usam esta mesma lista.
 */
class BrainStore(private val settings: AppSettings, private val secrets: SecretStore) {
    private val _slots = MutableStateFlow(BrainSlots.decode(settings.brainSlotsRaw))
    val slots: StateFlow<List<BrainSlot>> = _slots.asStateFlow()

    fun all(): List<BrainSlot> = _slots.value

    private fun save(list: List<BrainSlot>) {
        settings.brainSlotsRaw = BrainSlots.encode(list)
        _slots.value = list
    }

    fun preset(slot: BrainSlot): CloudPreset = runCatching { CloudPreset.valueOf(slot.preset) }.getOrDefault(CloudPreset.CUSTOM)

    fun key(id: String): String? = secrets.get(keyName(id))?.takeIf { it.isNotBlank() }
    fun hasKey(id: String): Boolean = secrets.has(keyName(id))
    fun setKey(id: String, key: String?) = secrets.put(keyName(id), key?.trim())

    fun add(preset: CloudPreset): BrainSlot {
        val slot = BrainSlot(BrainSlots.newId(all(), preset.name), preset.name, preset.baseUrl, preset.suggestedModel)
        save(all() + slot)
        return slot
    }

    fun update(slot: BrainSlot) = save(BrainSlots.upsert(all(), slot))
    fun move(id: String, delta: Int) = save(BrainSlots.move(all(), id, delta))

    fun remove(id: String) {
        setKey(id, null)
        save(BrainSlots.remove(all(), id))
    }

    /** O cérebro está pronto para uso (ligado, com endereço, modelo e chave quando exigida)? */
    fun ready(slot: BrainSlot): Boolean {
        val preset = preset(slot)
        return slot.enabled && slot.baseUrl.isNotBlank() && slot.model.isNotBlank() && (!preset.keyRequired || key(slot.id) != null)
    }

    fun label(slot: BrainSlot): String = "${preset(slot).label.substringBefore(" (")} · ${slot.model.ifBlank { "sem modelo" }}"

    fun provider(slot: BrainSlot, ignoreEnabled: Boolean = false): LlmProvider? {
        val usable = if (ignoreEnabled) ready(slot.copy(enabled = true)) else ready(slot)
        if (!usable) return null
        return OpenAiCompatibleProvider(
            CloudConfig(
                id = "brain_${slot.id}",
                displayName = label(slot),
                baseUrl = slot.baseUrl,
                apiKey = key(slot.id),
                model = slot.model,
                location = preset(slot).location,
            ),
        )
    }

    /** O primeiro Gemini da lista com chave (base da reserva automática). */
    private fun geminiSlot(): BrainSlot? =
        all().firstOrNull { it.enabled && it.preset == CloudPreset.GEMINI.name && it.baseUrl.isNotBlank() && it.model.isNotBlank() && key(it.id) != null }

    /** Até quando o modelo principal do Gemini está sem cota (429); nesse tempo visão e pesquisa usam a reserva. */
    @Volatile var geminiPrimaryRestUntil = 0L

    fun noteGeminiLimited(providerId: String, untilMillis: Long) {
        val slot = geminiSlot() ?: return
        if (providerId == "brain_${slot.id}") geminiPrimaryRestUntil = untilMillis
    }

    /**
     * Reserva automática (pedido 78): no plano grátis cada modelo do Gemini tem cota própria; outro modelo, com a mesma
     * chave, responde quando o principal bate o limite — mesmo nível, com visão e ferramentas.
     */
    fun geminiReserve(): LlmProvider? {
        val slot = geminiSlot() ?: return null
        val model = settings.geminiReserveModel.takeIf { it.isNotBlank() && it != slot.model } ?: return null
        return OpenAiCompatibleProvider(
            CloudConfig(
                id = "brain_${slot.id}_reserva",
                displayName = "Google Gemini · $model (reserva automática)",
                baseUrl = slot.baseUrl,
                apiKey = key(slot.id),
                model = model,
                location = preset(slot).location,
            ),
        )
    }

    /** Escolhe o modelo da reserva pela lista real da conta (uma vez; refaz se o principal mudar para o mesmo modelo). */
    suspend fun ensureGeminiReserve(): String? {
        val slot = geminiSlot() ?: return null
        val current = settings.geminiReserveModel
        if (current.isNotBlank() && current != slot.model) return current
        val primary = provider(slot) as? OpenAiCompatibleProvider ?: return null
        val models = runCatching { primary.listModels() }.getOrNull() ?: return null
        val picked = com.joctaeng.jarvis.mind.orchestrator.BrainReserve.pickGemini(models, slot.model) ?: return null
        settings.geminiReserveModel = picked
        return picked
    }

    /** Chave do primeiro Gemini da lista (a voz do Gemini usa a mesma chave). */
    fun geminiKey(): String? = all().filter { it.preset == CloudPreset.GEMINI.name }.firstNotNullOfOrNull { key(it.id) }

    /** O primeiro Gemini com chave (endereço, modelo, chave): ele enxerga imagens, então é quem olha o print da tela. */
    fun visionBrain(): Triple<String, String, String>? {
        val triple = all()
            .filter { it.preset == CloudPreset.GEMINI.name && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
            .firstNotNullOfOrNull { s -> key(s.id)?.let { Triple(s.baseUrl, s.model, it) } } ?: return null
        // Principal sem cota agora: olhar a tela e pesquisar usam a reserva automática (mesma chave, outro modelo).
        val reserve = settings.geminiReserveModel
        return if (System.currentTimeMillis() < geminiPrimaryRestUntil && reserve.isNotBlank() && reserve != triple.second) triple.copy(second = reserve) else triple
    }

    /**
     * Uma vez só: leva o cérebro principal e o reserva das versões anteriores (um campo de chave cada) para a lista,
     * sem perder as chaves. Depois disso os campos antigos não são mais usados.
     */
    fun migrate() {
        if (settings.brainsMigrated) return
        var list = all()
        fun bring(preset: CloudPreset, base: String, model: String, oldKey: String) {
            if (preset == CloudPreset.NONE) return
            val m = model.ifBlank { preset.suggestedModel }
            if (list.any { it.preset == preset.name && it.model == m }) return
            val slot = BrainSlot(BrainSlots.newId(list, preset.name), preset.name, base.ifBlank { preset.baseUrl }, m)
            secrets.get(oldKey)?.let { secrets.put(keyName(slot.id), it) }
            list = list + slot
        }
        bring(settings.cloudPreset, settings.cloudBaseUrl, settings.cloudModel, SecretStore.CLOUD_API_KEY)
        bring(settings.backupPreset, settings.backupBaseUrl, settings.backupModel, SecretStore.BACKUP_API_KEY)
        save(list)
        settings.brainsMigrated = true
    }

    private fun keyName(id: String) = "brain_key_$id"
}
