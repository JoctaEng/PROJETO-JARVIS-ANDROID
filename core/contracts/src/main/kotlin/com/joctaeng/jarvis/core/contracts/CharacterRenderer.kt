package com.joctaeng.jarvis.core.contracts

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion

/**
 * Contrato do personagem visual (seção 5.3). Implementações: placeholder em
 * Compose (Fase 0), Rive 2.5D (MVP 1), Filament 3D (MVP 2/3).
 */
interface CharacterRenderer {
    fun setEmotion(emotion: Emotion, intensity: Float)
    fun play(state: AnimState)

    /** Direção do olhar em coordenadas normalizadas (-1..1). */
    fun lookAt(x: Float, y: Float)

    /** Abertura da boca 0..1 — sincronia labial com o áudio. */
    fun setMouthOpen(level: Float)
}
