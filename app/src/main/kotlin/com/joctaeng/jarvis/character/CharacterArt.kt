package com.joctaeng.jarvis.character

import android.content.res.Resources
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import com.joctaeng.jarvis.R
import com.joctaeng.jarvis.presence.expression.Expression

/**
 * Arte 2D de cada personagem, gerada por tools/arte/preparar_arte.py a partir de docs/arte/.
 * Só personagens com arte aparecem para escolha no app.
 */
object CharacterArt {
    /** Personagem usado quando o escolhido ainda não tem arte. */
    const val DEFAULT_ID = "jocta_casual"

    private val frames: Map<String, Map<Expression, Int>> = mapOf(
        "jocta_casual" to mapOf(
            Expression.NEUTRO to R.drawable.arte_jocta_casual_neutro,
            Expression.FELIZ to R.drawable.arte_jocta_casual_feliz,
            Expression.PENSATIVO to R.drawable.arte_jocta_casual_pensativo,
            Expression.FALANDO to R.drawable.arte_jocta_casual_falando,
            Expression.SURPRESO to R.drawable.arte_jocta_casual_surpreso,
            Expression.DORMINDO to R.drawable.arte_jocta_casual_dormindo,
        ),
        "luna" to mapOf(
            Expression.NEUTRO to R.drawable.arte_luna_neutro,
            Expression.FELIZ to R.drawable.arte_luna_feliz,
            Expression.PENSATIVO to R.drawable.arte_luna_pensativo,
            Expression.FALANDO to R.drawable.arte_luna_falando,
            Expression.OUVINDO to R.drawable.arte_luna_ouvindo,
            Expression.SURPRESO to R.drawable.arte_luna_surpreso,
            Expression.PREOCUPADO to R.drawable.arte_luna_preocupado,
            Expression.DORMINDO to R.drawable.arte_luna_dormindo,
        ),
        "thor" to mapOf(
            Expression.NEUTRO to R.drawable.arte_thor_neutro,
            Expression.FELIZ to R.drawable.arte_thor_feliz,
            Expression.PENSATIVO to R.drawable.arte_thor_pensativo,
            Expression.FALANDO to R.drawable.arte_thor_falando,
            Expression.OUVINDO to R.drawable.arte_thor_ouvindo,
            Expression.SURPRESO to R.drawable.arte_thor_surpreso,
            Expression.PREOCUPADO to R.drawable.arte_thor_preocupado,
            Expression.DORMINDO to R.drawable.arte_thor_dormindo,
        ),
    )

    /** Região da boca na arte (proporções 0..1 do quadro): centro, largura e altura. */
    data class MouthBox(val cx: Float, val cy: Float, val w: Float, val h: Float)

    /**
     * Boca medida comparando os quadros "neutro" e "falando" (mesma pose). Só personagens com arte alinhada têm boca
     * animada sobre a expressão; os outros trocam o quadro inteiro.
     */
    fun mouthBox(characterId: String): MouthBox? = when (characterId) {
        "jocta_casual" -> MouthBox(0.504f, 0.459f, 0.16f, 0.085f)
        else -> null
    }

    private val cache = HashMap<Int, ImageBitmap>()

    fun hasArt(characterId: String): Boolean = characterId in frames

    /**
     * Personagens cujos quadros passam em `tools/arte/verificar_alinhamento.py` (mesma pose e escala).
     * Só eles recebem fusão entre expressões. Luna e Thor ficam fora até a arte ser regerada.
     */
    fun isAligned(characterId: String): Boolean = characterId == "jocta_casual"

    /** Decodifica uma vez por processo; a troca de expressão (boca a cada ~110 ms) não volta a ler o arquivo. */
    @Synchronized
    fun load(resources: Resources, characterId: String): Map<Expression, ImageBitmap>? =
        frames[characterId]?.mapValues { (_, res) ->
            cache.getOrPut(res) { BitmapFactory.decodeResource(resources, res).asImageBitmap() }
        }
}
