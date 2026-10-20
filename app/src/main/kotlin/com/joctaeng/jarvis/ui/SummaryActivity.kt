package com.joctaeng.jarvis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Lê o resultado completo de uma tarefa programada (o resumo da manhã), aberto ao tocar na notificação. */
class SummaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Resumo" }
        val body = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Sem conteúdo." }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, 0, 0, pad)
            })
            addView(TextView(context).apply {
                text = body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextIsSelectable(true)
            })
        }
        setContentView(ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    companion object {
        private const val EXTRA_TITLE = "titulo"
        private const val EXTRA_TEXT = "texto"

        fun intent(context: Context, title: String, text: String): Intent =
            Intent(context, SummaryActivity::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text.take(60_000))
    }
}
