package com.joctaeng.jarvis.poc

import java.util.Locale

/** Taxa de erro de palavras (WER) = (substituições + inserções + remoções) / palavras de referência. */
object WordErrorRate {
    private val ptBr = Locale.forLanguageTag("pt-BR")

    fun normalize(text: String): List<String> =
        text.lowercase(ptBr)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    fun compute(reference: String, hypothesis: String): Double {
        val ref = normalize(reference)
        val hyp = normalize(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        var previous = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val current = IntArray(hyp.size + 1)
            current[0] = i
            for (j in 1..hyp.size) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[hyp.size].toDouble() / ref.size
    }
}

/**
 * 20 frases de teste da PoC 0.4 — comandos do dia a dia, sem números (o
 * reconhecedor pode escrever "dez" como "10", o que distorceria a medida).
 */
val SpeechTestPhrases = listOf(
    "Bom dia, como está a minha agenda hoje",
    "Lembre de comprar pão quando eu sair do trabalho",
    "Abra o aplicativo de finanças",
    "Quais tarefas ainda estão pendentes",
    "Me explique o que é derivada de forma simples",
    "Crie uma rotina chamada fechar o dia",
    "Qual é o próximo compromisso da tarde",
    "Resuma o documento que eu acabei de abrir",
    "Coloque o celular em modo foco por meia hora",
    "Você pode diminuir de tamanho e ir para o canto",
    "Preciso preparar a aula de física de amanhã",
    "Quanto eu gastei com alimentação neste mês",
    "Procure os arquivos da prova no meu drive",
    "Mande uma mensagem dizendo que vou me atrasar",
    "Esqueça o que eu falei agora há pouco",
    "Ative o modo privado",
    "Qual é a previsão do tempo para o fim de semana",
    "Organize meu dia por prioridade",
    "Leia em voz alta a última notificação",
    "Obrigado, pode voltar a dormir",
)
