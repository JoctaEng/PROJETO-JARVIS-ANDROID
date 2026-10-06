package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.RiskLevel

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data object RequireConfirmation : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

/** Situação de permissões de uma ferramenta específica no momento do pedido. */
data class PermissionState(
    /** Permissão do Android (ex.: READ_CALENDAR) concedida. */
    val androidGranted: Boolean,
    /** Permissão concedida na central "O que JARVIS pode fazer?". */
    val jarvisGranted: Boolean,
)

/**
 * Policy Engine (seção 10.1 e matriz 10.2). Ordem de verificação:
 * permissão Android → permissão JARVIS → autonomia × risco.
 * Ações CRÍTICAS sempre exigem confirmação, em qualquer nível.
 */
object PolicyEngine {
    fun evaluate(
        risk: RiskLevel,
        autonomy: AutonomyLevel,
        permissions: PermissionState,
        partOfApprovedSkill: Boolean = false,
    ): PolicyDecision {
        if (!permissions.androidGranted) return PolicyDecision.Deny("Permissão do Android não concedida.")
        if (!permissions.jarvisGranted) return PolicyDecision.Deny("Você ainda não autorizou o JARVIS a fazer isso.")

        return when (autonomy) {
            AutonomyLevel.OBSERVER -> PolicyDecision.Deny("Modo Observador: eu apenas respondo, não executo ações.")
            AutonomyLevel.ASSISTANT -> PolicyDecision.RequireConfirmation
            AutonomyLevel.OPERATOR -> when (risk) {
                RiskLevel.READ -> PolicyDecision.Allow
                else -> PolicyDecision.RequireConfirmation
            }
            AutonomyLevel.CONTROLLED_AUTONOMOUS -> when (risk) {
                RiskLevel.READ -> PolicyDecision.Allow
                RiskLevel.WRITE_REVERSIBLE ->
                    if (partOfApprovedSkill) PolicyDecision.Allow else PolicyDecision.RequireConfirmation
                RiskLevel.CRITICAL -> PolicyDecision.RequireConfirmation
            }
            AutonomyLevel.PERSONAL_AGENT -> when (risk) {
                RiskLevel.CRITICAL -> PolicyDecision.RequireConfirmation
                else -> PolicyDecision.Allow
            }
        }
    }
}
