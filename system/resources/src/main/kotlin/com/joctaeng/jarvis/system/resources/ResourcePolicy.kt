package com.joctaeng.jarvis.system.resources

import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ThermalLevel

/** Um modelo local que pode ser carregado (seção 7.1). */
data class LocalModelTier(
    val id: String,
    /** RAM estimada de pico ao rodar o modelo. Medida real vem da Fase 0. */
    val estimatedPeakRamMb: Long,
    /** RAM total mínima do aparelho para sequer considerar o modelo. */
    val minDeviceRamMb: Long,
)

sealed interface ModelChoice {
    data class Load(val tier: LocalModelTier, val reason: String) : ModelChoice
    data class None(val reason: String) : ModelChoice
}

/**
 * Regras das seções 3.2 e 3.3: um modelo pesado por vez, folga de RAM,
 * e recuo para modelos menores quando o aparelho está quente ou sem bateria.
 */
class ResourcePolicy(
    private val safetyMarginMb: Long = 512,
    private val lowBatteryPercent: Int = 15,
) {
    /** Teto de memória do processo JARVIS (tabela 3.2). */
    fun processBudgetMb(totalRamMb: Long): Long = if (totalRamMb < 10_000) 3_000 else 4_500

    fun chooseLocalModel(device: DeviceContext, tiers: List<LocalModelTier>): ModelChoice {
        if (device.thermal == ThermalLevel.CRITICAL) {
            return ModelChoice.None("Aparelho muito quente: IA local pausada.")
        }
        val eligible = tiers
            .filter { device.totalRamMb >= it.minDeviceRamMb }
            .filter { it.estimatedPeakRamMb <= processBudgetMb(device.totalRamMb) }
            .filter { device.availableRamMb >= it.estimatedPeakRamMb + safetyMarginMb }
            .sortedBy { it.estimatedPeakRamMb }
        if (eligible.isEmpty()) return ModelChoice.None("Memória livre insuficiente para qualquer modelo local.")

        val economy = device.thermal == ThermalLevel.HOT ||
            (device.batteryPercent < lowBatteryPercent && !device.charging)
        return if (economy) {
            ModelChoice.Load(eligible.first(), "Modo econômico (bateria baixa ou aparelho quente).")
        } else {
            ModelChoice.Load(eligible.last(), "Maior modelo que cabe com folga na memória.")
        }
    }
}
