package com.joctaeng.jarvis.system.resources

/** Texto para a voz do Azure (SSML) e controle da cota mensal gratuita. Puro, para testar. */
object AzureSsml {
    /** Cota grátis do plano F0 (caracteres/mês, segundo as fontes de 2026; conferir no portal do Azure). */
    const val FREE_MONTHLY_CHARS = 500_000

    /** Margem: o Automático para de usar o Azure um pouco antes do limite, para não cair em cobrança/erro. */
    const val AUTO_STOP_AT = 480_000

    fun build(text: String, voice: String, rate: Float = 1.0f): String {
        val percent = Math.round((rate - 1f) * 100).coerceIn(-50, 100)
        val r = if (percent >= 0) "+$percent%" else "$percent%"
        val lang = voice.split('-').take(2).joinToString("-").ifBlank { "pt-BR" }
        return "<speak version='1.0' xml:lang='$lang'><voice name='${escape(voice)}'><prosody rate='$r'>${escape(text)}</prosody></voice></speak>"
    }

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** Caracteres cobrados: o Azure conta o texto falado (as marcas SSML não contam, exceto o conteúdo). */
    fun billedChars(text: String): Int = text.length

    /** Mês corrente no formato AAAA-MM, para zerar o contador quando o mês muda. */
    fun monthKey(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.ROOT).format(java.util.Date(epochMillis))
}
