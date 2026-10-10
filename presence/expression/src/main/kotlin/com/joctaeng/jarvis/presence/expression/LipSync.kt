package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.Emotion
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Formatos de boca (visemas) no padrão do Rhubarb Lip Sync (inspirado nos desenhos clássicos da Hanna-Barbera e de
 * Preston Blair). [open] = quanto a boca abre (0..1); [width] = largura relativa (1 = normal; <1 arredondada; >1 esticada).
 */
enum class Viseme(val open: Float, val width: Float) {
    /** Repouso (pausa). */
    X(0f, 1f),

    /** M, B, P: lábios fechados. */
    A(0f, 1f),

    /** Maioria das consoantes e o "i": dentes quase juntos, boca esticada. */
    B(0.3f, 1.12f),

    /** "é", "ê": aberta. */
    C(0.72f, 1.05f),

    /** "a": bem aberta. */
    D(1f, 1f),

    /** "ó", "ô": arredondada. */
    E(0.68f, 0.82f),

    /** "u", "w": lábios em bico. */
    F(0.42f, 0.66f),

    /** F, V: dentes no lábio de baixo. */
    G(0.22f, 1.02f),

    /** L: língua atrás dos dentes, meio aberta. */
    H(0.6f, 1f),
}

/** Momento em que um visema começa (ms desde o início do áudio). */
data class VisemeKey(val atMs: Long, val viseme: Viseme)

/** A boca num instante: formato, abertura final (já com o volume do áudio) e largura. */
data class MouthPose(val viseme: Viseme, val open: Float, val width: Float) {
    companion object {
        val REST = MouthPose(Viseme.X, 0f, 1f)
    }
}

/**
 * Português do Brasil → visemas a partir do texto (sem reconhecer o áudio): cada letra vira um formato de boca com um
 * peso de duração (vogal mais longa que consoante, pausa na pontuação). O tempo real vem do áudio ([LipTimeline]).
 */
object PtBrVisemes {
    data class Piece(val viseme: Viseme, val weight: Float)

    private val vowelA = "aáàâã"
    private val vowelE = "eéê"
    private val vowelO = "oóôõ"

    fun units(text: String): List<Piece> {
        val s = text.lowercase()
        val out = ArrayList<Piece>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
                c == 'l' && next == 'h' -> { out += Piece(Viseme.H, 0.6f); i++ }
                c == 'n' && next == 'h' -> { out += Piece(Viseme.B, 0.55f); i++ }
                c == 'c' && next == 'h' -> { out += Piece(Viseme.B, 0.6f); i++ }
                c in vowelA -> out += Piece(Viseme.D, if (c == 'á' || c == 'â') 1.2f else 1f)
                c in vowelE -> out += Piece(Viseme.C, if (c == 'é' || c == 'ê') 1.15f else 0.9f)
                c == 'i' || c == 'í' || c == 'y' -> out += Piece(Viseme.B, if (c == 'í') 1.1f else 0.85f)
                c in vowelO -> out += Piece(Viseme.E, if (c == 'ó' || c == 'ô') 1.15f else 0.95f)
                c == 'u' || c == 'ú' || c == 'w' -> out += Piece(Viseme.F, 0.9f)
                c == 'm' || c == 'b' || c == 'p' -> out += Piece(Viseme.A, 0.55f)
                c == 'f' || c == 'v' -> out += Piece(Viseme.G, 0.6f)
                c == 'l' -> out += Piece(Viseme.H, 0.55f)
                c == 'h' -> {} // "h" é mudo
                c.isDigit() -> { out += Piece(Viseme.C, 0.8f); out += Piece(Viseme.B, 0.5f); out += Piece(Viseme.D, 0.8f) }
                c.isLetter() -> out += Piece(Viseme.B, 0.5f)
                c == ',' || c == ';' || c == ':' -> out += Piece(Viseme.X, 1.4f)
                c == '.' || c == '!' || c == '?' || c == '…' -> out += Piece(Viseme.X, 2f)
                c.isWhitespace() -> if (out.isNotEmpty() && out.last().viseme != Viseme.X) out += Piece(Viseme.X, 0.15f)
            }
            i++
        }
        while (out.isNotEmpty() && out.last().viseme == Viseme.X) out.removeAt(out.lastIndex)
        return out
    }

    /** Espalha os visemas do texto entre [startMs] e [endMs] (o trecho do áudio que tem voz). */
    fun track(text: String, startMs: Long, endMs: Long): List<VisemeKey> {
        val units = units(text)
        if (units.isEmpty() || endMs <= startMs) return emptyList()
        val total = units.sumOf { it.weight.toDouble() }
        var t = startMs.toDouble()
        val span = (endMs - startMs).toDouble()
        val keys = ArrayList<VisemeKey>(units.size + 1)
        for (u in units) {
            keys += VisemeKey(t.toLong(), u.viseme)
            t += span * u.weight / total
        }
        keys += VisemeKey(endMs, Viseme.X)
        return keys
    }
}

/** Volume do áudio quadro a quadro (RMS de PCM 16 bits mono), normalizado de 0 a 1. */
object AudioEnvelope {
    const val FRAME_MS = 20

    fun fromPcm16(pcm: ByteArray, sampleRate: Int, frameMs: Int = FRAME_MS): FloatArray {
        val samplesPerFrame = (sampleRate * frameMs / 1000).coerceAtLeast(1)
        val totalSamples = pcm.size / 2
        val frames = (totalSamples + samplesPerFrame - 1) / samplesPerFrame
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val from = f * samplesPerFrame
            val to = minOf(totalSamples, from + samplesPerFrame)
            for (s in from until to) {
                val v = ((pcm[2 * s + 1].toInt() shl 8) or (pcm[2 * s].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
            }
            out[f] = sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
        }
        // Normaliza pelo percentil 90 (ignora estalos) e suaviza (abre rápido, fecha um pouco mais devagar).
        val ref = out.sortedArray().let { if (it.isEmpty()) 0f else it[(it.size * 9 / 10).coerceAtMost(it.size - 1)] }
        if (ref <= 1f) return FloatArray(frames)
        var prev = 0f
        for (i in out.indices) {
            val v = (out[i] / ref).coerceIn(0f, 1f)
            prev = if (v > prev) prev + (v - prev) * 0.7f else prev + (v - prev) * 0.35f
            out[i] = prev
        }
        return out
    }

    /** Início e fim da voz no áudio (ms), ignorando o silêncio das pontas. */
    fun speechBounds(envelope: FloatArray, frameMs: Int = FRAME_MS, threshold: Float = SILENCE): Pair<Long, Long> {
        val first = envelope.indexOfFirst { it > threshold }
        val last = envelope.indexOfLast { it > threshold }
        if (first < 0) return 0L to envelope.size.toLong() * frameMs
        return first.toLong() * frameMs to (last + 1).toLong() * frameMs
    }

    const val SILENCE = 0.08f
}

/**
 * Boca de uma frase: formato pelo texto ([PtBrVisemes]) e abertura pelo volume real do áudio ([AudioEnvelope]).
 * Sem áudio (voz do Android), usa só o texto com abertura média.
 */
class LipTimeline(private val keys: List<VisemeKey>, private val envelope: FloatArray?, private val frameMs: Int = AudioEnvelope.FRAME_MS) {
    val durationMs: Long = keys.lastOrNull()?.atMs ?: 0L

    fun at(ms: Long): MouthPose {
        if (keys.isEmpty() || ms < 0) return MouthPose.REST
        var lo = 0
        var hi = keys.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (keys[mid].atMs <= ms) lo = mid else hi = mid - 1
        }
        val v = keys[lo].viseme
        val level = envelope?.let { e ->
            val i = (ms / frameMs).toInt()
            if (i !in e.indices) 0f else e[i]
        } ?: 0.75f
        if (v == Viseme.X || level < AudioEnvelope.SILENCE) return MouthPose(Viseme.X, 0f, 1f)
        val open = v.open * (0.55f + 0.45f * (level / 0.7f).coerceAtMost(1f))
        return MouthPose(v, open.coerceIn(0f, 1f), v.width)
    }

    companion object {
        fun forClip(text: String, pcm: ByteArray, sampleRate: Int): LipTimeline {
            val env = AudioEnvelope.fromPcm16(pcm, sampleRate)
            val (start, end) = AudioEnvelope.speechBounds(env)
            return LipTimeline(PtBrVisemes.track(text, start, end), env)
        }

        /** Sem áudio: ~65 ms por letra (ritmo médio de fala), para palavras anunciadas pela voz do Android. */
        fun forText(text: String, msPerChar: Long = 65L): LipTimeline =
            LipTimeline(PtBrVisemes.track(text, 0L, (text.length * msPerChar).coerceAtLeast(120L)), null)
    }
}

/**
 * Emoção de uma frase pelo que ela diz (quando o cérebro não marcou): a expressão muda junto com a frase falada.
 * Devolve null quando a frase é neutra (fica a emoção da resposta).
 */
object SentenceMood {
    private val happy = listOf("que bom", "ótimo", "otimo", "legal", "perfeito", "parabéns", "parabens", "adorei", "maravilh", "show", "massa", "feliz", "boa!", "beleza", "haha", "kkk", "obrigad", "fechado", "pronto")
    private val sad = listOf("infelizmente", "desculp", "sinto muito", "não consegui", "nao consegui", "falhou", "erro", "problema", "cuidado", "atenção", "atencao", "preocup", "triste", "puxa")
    private val surprise = listOf("uau", "nossa", "caramba", "sério?", "serio?", "eita", "que incrível", "que incrivel", "olha só", "olha so")
    private val thinking = listOf("deixa eu ver", "vou ver", "hmm", "humm", "talvez", "acho que", "vamos ver", "deixa eu pensar", "pensando")

    fun detect(sentence: String): Emotion? {
        val s = Normalizer.normalize(sentence.lowercase(), Normalizer.Form.NFC)
        return when {
            surprise.any { it in s } -> Emotion.SURPRISED
            sad.any { it in s } -> Emotion.CONCERNED
            thinking.any { it in s } -> Emotion.THINKING
            happy.any { it in s } || s.trimEnd().endsWith("!") -> Emotion.HAPPY
            else -> null
        }
    }
}
