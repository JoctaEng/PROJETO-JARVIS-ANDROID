package com.joctaeng.jarvis.core.model

/** Estado térmico simplificado, mapeado a partir do PowerManager do Android. */
enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }

/**
 * Fotografia do aparelho no momento de uma decisão (seção 3.3 e 7.2).
 * Montada pela camada Android; consumida por lógica pura.
 */
data class DeviceContext(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowMemory: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermal: ThermalLevel,
    val online: Boolean,
    val privateMode: Boolean = false,
)
