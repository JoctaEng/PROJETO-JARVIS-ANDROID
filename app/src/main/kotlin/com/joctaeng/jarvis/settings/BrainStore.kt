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

    /** Chave do primeiro Gemini da lista (a voz do Gemini usa a mesma chave). */
    fun geminiKey(): String? = all().filter { it.preset == CloudPreset.GEMINI.name }.firstNotNullOfOrNull { key(it.id) }

    /** O primeiro Gemini com chave (endereço, modelo, chave): ele enxerga imagens, então é quem olha o print da tela. */
    fun visionBrain(): Triple<String, String, String>? = all()
        .filter { it.preset == CloudPreset.GEMINI.name && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
        .firstNotNullOfOrNull { s -> key(s.id)?.let { Triple(s.baseUrl, s.model, it) } }

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
