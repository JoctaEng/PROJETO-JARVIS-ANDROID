package com.joctaeng.jarvis.mind.orchestrator

import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Sensitivity
import com.joctaeng.jarvis.core.model.TaskComplexity
import com.joctaeng.jarvis.core.model.ThermalLevel

/** Preferência do usuário entre cérebros (configurável em "Meu JARVIS"). */
enum class BrainPreference { AUTO, ONLINE_FIRST, LOCAL_FIRST, LOCAL_ONLY }

/** O que o orquestrador sabe sobre um pedido antes de escolher o cérebro. */
data class RoutingHints(
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val complexity: TaskComplexity = TaskComplexity.SIMPLE,
    val needsVision: Boolean = false,
    /** Permissão explícita do usuário para mandar dado sensível à nuvem externa. */
    val allowExternalCloudForSensitive: Boolean = false,
    val preference: BrainPreference = BrainPreference.AUTO,
)

/** Resumo de um provedor para a decisão (sem depender da implementação). */
data class ProviderInfo(
    val id: String,
    val location: ProviderLocation,
    val capabilities: Set<Capability>,
    val available: Boolean,
)

/** Resultado: provedores em ordem de tentativa + avisos que o personagem deve dizer. */
data class RoutingPlan(
    val orderedProviderIds: List<String>,
    val notices: List<String>,
) {
    val isEmpty: Boolean get() = orderedProviderIds.isEmpty()
}

/**
 * Regras da seção 7.2 do roteiro, em ordem. Lógica pura e determinística:
 * mesma entrada, mesma saída — por isso é testável sem aparelho.
 *
 * @param userPreference ordem de preferência do usuário entre provedores (desempate).
 */
class RoutingPolicy(
    private val userPreference: List<String> = emptyList(),
    private val lowBatteryPercent: Int = 20,
) {
    fun plan(providers: List<ProviderInfo>, device: DeviceContext, hints: RoutingHints): RoutingPlan {
        val notices = mutableListOf<String>()
        var candidates = providers.filter { it.available }

        // Regra 1 — Modo Privado: somente no aparelho.
        if (device.privateMode) {
            candidates = candidates.filter { it.location == ProviderLocation.ON_DEVICE }
            notices += "Modo Privado ativo: usando apenas a IA do aparelho."
        }

        // Regra 2 — Dado sensível não vai para nuvem externa sem permissão explícita.
        if (hints.sensitivity == Sensitivity.SENSITIVE && !hints.allowExternalCloudForSensitive) {
            candidates = candidates.filter { it.location != ProviderLocation.EXTERNAL_CLOUD }
        }

        // Regra 3 — Sem internet: só local (servidor próprio também exige rede).
        if (!device.online) {
            candidates = candidates.filter { it.location == ProviderLocation.ON_DEVICE }
            notices += "Estou offline: usando a IA do aparelho, que é mais limitada."
        }

        if (hints.preference == BrainPreference.LOCAL_ONLY) {
            candidates = candidates.filter { it.location == ProviderLocation.ON_DEVICE }
        }

        // Visão exige provedor com capacidade de visão.
        if (hints.needsVision) {
            candidates = candidates.filter { Capability.VISION in it.capabilities }
        }

        // Regras 4, 5 e 6 — ordem de preferência por local de execução.
        val deviceStressed = device.thermal >= ThermalLevel.HOT ||
            (device.batteryPercent < lowBatteryPercent && !device.charging)
        val offDeviceFirst = listOf(ProviderLocation.OWN_SERVER, ProviderLocation.EXTERNAL_CLOUD, ProviderLocation.ON_DEVICE)
        val onDeviceFirst = listOf(ProviderLocation.ON_DEVICE, ProviderLocation.OWN_SERVER, ProviderLocation.EXTERNAL_CLOUD)
        val locationOrder = when {
            deviceStressed -> offDeviceFirst
            hints.preference == BrainPreference.ONLINE_FIRST -> offDeviceFirst
            hints.preference == BrainPreference.LOCAL_FIRST -> onDeviceFirst
            hints.complexity == TaskComplexity.SIMPLE -> onDeviceFirst
            else -> offDeviceFirst
        }
        if (deviceStressed && device.online) {
            notices += "Bateria baixa ou aparelho quente: priorizando processamento fora do celular."
        }

        val ordered = candidates.sortedWith(
            compareBy<ProviderInfo> { locationOrder.indexOf(it.location) }
                .thenBy { preferenceIndex(it.id) },
        )

        if (ordered.isEmpty()) {
            notices += "Nenhum cérebro disponível para este pedido."
        }
        // Regra 7 (fallback) é aplicada pelo Orchestrator percorrendo esta lista.
        return RoutingPlan(ordered.map { it.id }, notices)
    }

    private fun preferenceIndex(id: String): Int =
        userPreference.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
}
