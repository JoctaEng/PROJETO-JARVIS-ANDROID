package com.joctaeng.jarvis.mind.persona

/** Limpezas do texto da resposta antes de mostrar/guardar. */
object TextCleanup {
    // Só dígitos e separadores comuns de telefone/data/hora/valor entre $…$ (não $$…$$).
    private val wrappedNumber = Regex("(?<!\\$)\\$\\s*(\\+?\\(?\\d[\\d\\s().,/:\\-]*\\d|\\d)\\s*\\$(?!\\$)")
    private val emotionTag = Regex("\\[(neutro|feliz|pensativo|surpreso|preocupado|sonolento|comemorando|confuso|brincalh[aã]o)\\]\\s*", RegexOption.IGNORE_CASE)

    /** "$91912345678$" → "91912345678": número comum não é fórmula (os cifrões apareciam na tela). */
    fun unwrapPlainNumbers(text: String): String = text.replace(wrappedNumber) { it.groupValues[1] }

    /** Tira etiquetas de emoção que escaparam no meio do texto (ex.: depois de usar uma ferramenta). */
    fun stripEmotionTags(text: String): String = text.replace(emotionTag, "")

    fun clean(text: String): String = stripEmotionTags(unwrapPlainNumbers(text))
}
