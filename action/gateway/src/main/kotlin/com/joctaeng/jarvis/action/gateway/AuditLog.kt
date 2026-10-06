package com.joctaeng.jarvis.action.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Registro de atividade visível ao usuário (item 48 da especificação). */
data class AuditEntry(
    val timestampMillis: Long,
    val toolName: String,
    val reason: String,
    val outcome: String,
)

/** Só permite acrescentar: entradas nunca são editadas nem removidas pelo app. */
interface AuditLog {
    val entries: StateFlow<List<AuditEntry>>
    fun append(entry: AuditEntry)
}

class InMemoryAuditLog : AuditLog {
    private val state = MutableStateFlow<List<AuditEntry>>(emptyList())
    override val entries: StateFlow<List<AuditEntry>> = state.asStateFlow()
    override fun append(entry: AuditEntry) = state.update { it + entry }
}
