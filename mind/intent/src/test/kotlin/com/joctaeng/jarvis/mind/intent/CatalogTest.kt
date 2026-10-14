package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cada modelo de frase do catálogo é expandido em muitas frases de verdade; cada uma passa pelo motor completo
 * (como se o usuário tivesse falado) e tem de cair numa intenção — a certa, na grande maioria.
 */
class CatalogTest {
    private val samples = mapOf(
        "xdatax" to listOf("hoje", "amanhã", "dia 15", "na segunda", "semana que vem", "20/10"),
        "xtimex" to listOf("às 15h", "às 8h30", "ao meio-dia", "às 7 da manhã", "às 19h"),
        "xdurx" to listOf("10 minutos", "2 horas", "30 segundos", "uma hora e meia"),
        "xmoneyx" to listOf("50 reais", "R\$ 152,30", "1200 reais", "35 reais e 50 centavos"),
        "xrecx" to listOf("todo dia", "de segunda a sexta", "nos fins de semana"),
        "titulo" to listOf("dentista", "reunião de pais", "aula de física", "mercado", "internet"),
        "rotulo" to listOf("remédio", "aula"),
        "app" to listOf("youtube", "instagram", "whatsapp", "gmail", "edumath"),
        "cat" to listOf("mercado", "farmácia", "combustível"),
        "cartao" to listOf("nubank", "itaú"),
        "contato" to listOf("Maria", "João Silva", "Pedro"),
        "mensagem" to listOf("já estou chegando", "vou me atrasar um pouco", "a aula foi cancelada"),
        "quem" to listOf("Maria", "banco", "escola"),
        "assunto" to listOf("fatura", "matrícula", "reunião de pais"),
        "consulta" to listOf("receita de bolo", "funções do segundo grau", "notícias de hoje"),
        "pergunta" to listOf("quando é a reposição da turma de cálculo", "como estão as notas da turma B"),
        "qual" to listOf("google", "celular"),
        "cidade" to listOf("Belém", "São Paulo"),
        "secao" to listOf("mensagens", "reels", "perfil", "explorar"),
        "conta" to listOf("12 vezes 15", "7 mais 3", "100 dividido por 4", "15 por cento de 80", "raiz quadrada de 144"),
        "evento" to listOf("natal", "pascoa", "carnaval"),
        "h" to listOf("amanhã"),
    )

    private val engine = Fixtures.engine()
    private val ctx = Fixtures.ctx()

    @Test fun everyTemplateWorksAsSpoken() {
        var phrases = 0
        var sameIntent = 0
        var total = 0L
        val noMatch = mutableListOf<String>()
        val other = mutableListOf<String>()
        // sem dados suficientes nas frases de exemplo (nota/aluno/avaliação); têm teste próprio
        val own = setOf("edu.grade", "edu.ask_topic")
        for (def in engine.all.filter { it.id !in own }) {
            for (tpl in def.templates) {
                total += Template(tpl).count(samples)
                for (phrase in Template(tpl).expand(samples, 20)) {
                    // sobras de sorteio que ninguém fala: "a o Pedro", "dia dia 15", "as ao meio-dia"
                    if (Regex("""\b(a o|a a|o a|dia dia|as ao|as às|as as|um uma|uma um|para às|a ao|ao às)\b""").containsMatchIn(phrase) || Regex("""\b(do|da|de) (o|a) """).containsMatchIn(phrase)) continue
                    phrases++
                    val out = Fixtures.freshEngine().handle(phrase, ctx)
                    val id = when (out) {
                        is Say -> out.intentId; is Run -> out.intentId; is Ask -> out.intentId; NoMatch -> null
                    }
                    when {
                        id == null -> noMatch += "[${def.id}] $phrase"
                        id == def.id -> sameIntent++
                        else -> other += "[${def.id}→$id] $phrase"
                    }
                }
            }
        }
        println("CATÁLOGO: ${engine.all.size} intenções, ${engine.all.sumOf { it.templates.size }} modelos de frase, $total frases possíveis; testadas $phrases; mesma intenção $sameIntent; outra intenção ${other.size}; sem resposta ${noMatch.size}")
        java.io.File("build/catalog-report.txt").apply { parentFile.mkdirs() }.writeText(
            "SEM RESPOSTA\n" + noMatch.joinToString("\n") + "\n\nOUTRA INTENÇÃO\n" + other.joinToString("\n"),
        )
        assertTrue(noMatch.size <= phrases * 0.03, "frases sem resposta demais: ${noMatch.size} de $phrases")
        assertTrue(sameIntent >= phrases * 0.90, "intenção certa em ${sameIntent} de $phrases")
    }
}
