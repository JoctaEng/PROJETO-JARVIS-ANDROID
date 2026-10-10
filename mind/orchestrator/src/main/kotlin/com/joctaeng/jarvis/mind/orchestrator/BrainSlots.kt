package com.joctaeng.jarvis.mind.orchestrator

/**
 * Um cérebro online cadastrado pelo usuário (Gemini, Groq, Cerebras...). Vários podem existir ao mesmo tempo,
 * cada um com a sua chave (guardada fora daqui, no cofre do aparelho, pelo [id]). A posição na lista é a ordem de uso.
 */
data class BrainSlot(
    val id: String,
    val preset: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
)

/** Guarda a lista numa linha de texto por cérebro (sem chave) e faz as operações da tela (subir, descer, remover). */
object BrainSlots {
    private const val SEP = '\t'

    fun encode(slots: List<BrainSlot>): String = slots.joinToString("\n") { s ->
        listOf(s.id, s.preset, if (s.enabled) "1" else "0", clean(s.baseUrl), clean(s.model)).joinToString(SEP.toString())
    }

    fun decode(text: String): List<BrainSlot> = text.lineSequence().mapNotNull { line ->
        val p = line.split(SEP)
        if (p.size < 5 || p[0].isBlank()) null else BrainSlot(p[0], p[1], p[3], p[4], p[2] != "0")
    }.distinctBy { it.id }.toList()

    fun move(slots: List<BrainSlot>, id: String, delta: Int): List<BrainSlot> {
        val i = slots.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in slots.indices) return slots
        return slots.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun upsert(slots: List<BrainSlot>, slot: BrainSlot): List<BrainSlot> {
        val i = slots.indexOfFirst { it.id == slot.id }
        return if (i < 0) slots + slot else slots.toMutableList().apply { set(i, slot) }
    }

    fun remove(slots: List<BrainSlot>, id: String): List<BrainSlot> = slots.filterNot { it.id == id }

    /** Id novo e estável para um provedor ("gemini", "groq", "groq2"...). */
    fun newId(slots: List<BrainSlot>, preset: String): String {
        val base = preset.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "cerebro" }
        if (slots.none { it.id == base }) return base
        var n = 2
        while (slots.any { it.id == "$base$n" }) n++
        return "$base$n"
    }

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').trim()
}

/**
 * Descanso de cérebros que bateram limite (HTTP 429): enquanto descansa, ele sai da lista e o próximo responde.
 * Sem dica de tempo na mensagem, descansa 60 s.
 */
class ProviderCooldown(private val defaultMillis: Long = 60_000L) {
    private val until = mutableMapOf<String, Long>()

    @Synchronized
    fun note(id: String, reason: String, nowMillis: Long, hintMillis: Long?) {
        if (!reason.contains("(429)")) return
        val wait = (hintMillis ?: defaultMillis).coerceIn(5_000L, 6 * 3_600_000L)
        until[id] = nowMillis + wait + 1_000L
    }

    @Synchronized
    fun resting(id: String, nowMillis: Long): Boolean = (until[id] ?: 0L) > nowMillis

    @Synchronized
    fun clear(id: String) { until.remove(id) }
}
