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
    )

    private val cache = HashMap<Int, ImageBitmap>()

    fun hasArt(characterId: String): Boolean = characterId in frames

    /** Decodifica uma vez por processo; a troca de expressão (boca a cada ~110 ms) não volta a ler o arquivo. */
    @Synchronized
    fun load(resources: Resources, characterId: String): Map<Expression, ImageBitmap>? =
        frames[characterId]?.mapValues { (_, res) ->
            cache.getOrPut(res) { BitmapFactory.decodeResource(resources, res).asImageBitmap() }
        }
}
