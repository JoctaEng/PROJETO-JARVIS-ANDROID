package com.joctaeng.jarvis.system.resources

/** Uma janela da tela, como o serviço de acessibilidade a enxerga (sem tipos do Android, para testar aqui). */
data class WindowInfo(
    val packageName: String,
    /** Janela de aplicativo (não barra de status, teclado, etc.). */
    val isApp: Boolean,
    /** Ordem de empilhamento: maior = mais à frente. */
    val layer: Int,
    val active: Boolean = false,
    val focused: Boolean = false,
)

/** Escolhe qual janela ler: nunca a do próprio Euno (senão ele lê a própria conversa). */
object WindowPick {
    /**
     * Devolve o índice da janela de app mais à frente que não seja do [ownPackage] (a ativa, se houver, tem preferência),
     * ou null se só existirem janelas do Euno.
     */
    fun pick(windows: List<WindowInfo>, ownPackage: String): Int? {
        val others = windows.withIndex().filter { it.value.isApp && it.value.packageName != ownPackage && it.value.packageName.isNotBlank() }
        if (others.isEmpty()) return null
        return (others.firstOrNull { it.value.active } ?: others.firstOrNull { it.value.focused } ?: others.maxByOrNull { it.value.layer })?.index
    }
}
