package com.joctaeng.jarvis.mind.intent

/** Todas as intenções que o Euno entende sozinho, por assunto. */
object Catalog {
    val all: List<IntentDef> by lazy { CatalogTime.defs + CatalogPhone.defs }
}
