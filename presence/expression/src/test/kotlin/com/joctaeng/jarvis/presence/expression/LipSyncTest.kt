package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.Emotion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LipSyncTest {
    @Test fun portugueseLettersBecomeMouthShapes() {
        val v = PtBrVisemes.units("Mamãe olhou").map { it.viseme }
        // m-a-m-ã-e  (pausa)  o-lh-o-u
        assertEquals(listOf(Viseme.A, Viseme.D, Viseme.A, Viseme.D, Viseme.C, Viseme.X, Viseme.E, Viseme.H, Viseme.E, Viseme.F), v)
        assertEquals(Viseme.G, PtBrVisemes.units("vá").first().viseme)
    }

    @Test fun trackSpreadsOverSpeechAndEndsAtRest() {
        val keys = PtBrVisemes.track("ola, tudo bem?", 100, 1100)
        assertEquals(100, keys.first().atMs)
        assertEquals(VisemeKey(1100, Viseme.X), keys.last())
        assertTrue(keys.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })
    }

    @Test fun envelopeFollowsVolumeAndFindsSilence() {
        val rate = 16_000
        // 200 ms de silêncio + 400 ms de tom + 200 ms de silêncio
        val samples = ShortArray(rate * 8 / 10) { i ->
            if (i in rate / 5 until rate * 6 / 10) (8000 * sin(2 * PI * 220 * i / rate)).toInt().toShort() else 0
        }
        val pcm = ByteArray(samples.size * 2).also { b -> samples.forEachIndexed { i, s -> b[2 * i] = (s.toInt() and 0xff).toByte(); b[2 * i + 1] = (s.toInt() shr 8).toByte() } }
        val env = AudioEnvelope.fromPcm16(pcm, rate)
        val (start, end) = AudioEnvelope.speechBounds(env)
        assertTrue(start in 180L..260L, "início $start")
        assertTrue(end in 560L..700L, "fim $end")
        val tl = LipTimeline.forClip("aaa", pcm, rate)
        assertEquals(Viseme.X, tl.at(50).viseme)
        assertTrue(tl.at(400).open > 0.5f)
    }

    @Test fun textOnlyTimeline() {
        val tl = LipTimeline.forText("pai")
        assertEquals(Viseme.A, tl.at(0).viseme)
        assertEquals(0f, tl.at(0).open)
        assertTrue(tl.at(tl.durationMs / 2).open > 0f)
        assertEquals(MouthPose.REST.viseme, tl.at(tl.durationMs + 10).viseme)
    }

    @Test fun sentenceMood() {
        assertEquals(Emotion.HAPPY, SentenceMood.detect("Que bom que funcionou!"))
        assertEquals(Emotion.CONCERNED, SentenceMood.detect("Infelizmente não consegui salvar."))
        assertEquals(Emotion.SURPRISED, SentenceMood.detect("Nossa, que rápido."))
        assertEquals(Emotion.THINKING, SentenceMood.detect("Deixa eu ver aqui."))
        assertNull(SentenceMood.detect("A reunião é às 15 horas."))
    }
}
