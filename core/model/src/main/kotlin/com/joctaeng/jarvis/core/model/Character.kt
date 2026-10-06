package com.joctaeng.jarvis.core.model

/**
 * Emoções *simuladas* do personagem (seção 6.3). Não representam emoções reais:
 * existem para comunicar estado de forma visual.
 */
enum class Emotion { NEUTRAL, HAPPY, THINKING, SURPRISED, CONCERNED, SLEEPY, CELEBRATING, CONFUSED, PLAYFUL }

/** Estados de animação mínimos do MVP (seção 6.2). */
enum class AnimState {
    IDLE, SLEEPING, WAKING, LISTENING, THINKING, SPEAKING,
    HAPPY, SURPRISED, CONCERNED, CELEBRATING, CONFUSED, ERROR, DRAGGED, SHY,
}
