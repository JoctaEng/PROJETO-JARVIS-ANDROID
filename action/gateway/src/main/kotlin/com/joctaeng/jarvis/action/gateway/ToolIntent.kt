package com.joctaeng.jarvis.action.gateway

import java.util.Locale

/**
 * Decide se o pedido parece precisar de ferramentas (abrir app, lanterna, alarme...).
 * Usado no cérebro local: a lista de ferramentas é cara de ler num celular, então só vai ao
 * prompt quando há indício de ação. Erro para o lado de incluir: é melhor ler ferramentas à toa
 * do que o modelo não conseguir agir quando o usuário pede.
 */
object ToolIntent {
    private val pistas = listOf(
        "abr", "fech", "lanterna", "alarme", "timer", "cronômetro", "cronometro", "compartilh",
        "mapa", "rota", "navega", "pesquis", "procur", "app", "aplicativo", "celular", "bateria",
        "wifi", "wi-fi", "bluetooth", "tela", "ligar", "desligar", "mensagem", "ferramenta",
        "mcp", "edumath", "configur", "volume", "brilho", "notifica", "instal", "toque", "tocar",
        "agenda", "arquivo", "pasta", "foto", "câmera", "camera",
    )

    fun likely(text: String): Boolean {
        val lower = text.lowercase(Locale.forLanguageTag("pt-BR"))
        return pistas.any { lower.contains(it) }
    }
}
