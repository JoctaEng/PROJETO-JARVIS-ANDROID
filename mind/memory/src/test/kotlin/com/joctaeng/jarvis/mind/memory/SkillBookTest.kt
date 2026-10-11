package com.joctaeng.jarvis.mind.memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBookTest {
    private fun book(dir: java.io.File = Files.createTempDirectory("skills").toFile()) = SkillBook(java.io.File(dir, "skills.json"), clock = { 42L })

    @Test fun builtInSkillsMatchWithoutAccents() {
        val b = book()
        assertEquals("modo-aula", b.match("Euno, entra no modo aula por favor")?.id)
        assertEquals("aula-lista", b.match("faz uma lista de exercícios de frações")?.id)
        assertEquals("financas-resumo", b.match("quanto tenho de saldo?")?.id)
        assertEquals("emails-importantes", b.match("chegou algum e-mail?")?.id)
    }

    @Test fun longestTriggerWins() {
        // "fim da aula" não pode cair no "modo aula"
        assertEquals("modo-aula-fim", book().match("sair do modo aula")?.id)
    }

    @Test fun noSkillForSmallTalk() {
        assertNull(book().match("e aí, tudo bem?"))
        assertNull(book().match("abre o Instagram"))
    }

    @Test fun teachUseAndPersist() {
        val dir = Files.createTempDirectory("skills").toFile()
        val taught = book(dir).teach("bom dia professor", "resumo_do_dia tipo=manha e depois clima de São Luís")
        assertFalse(taught.builtIn)
        val again = book(dir)
        assertEquals(taught.id, again.match("Euno, bom dia professor!")?.id)
        // reensinar o mesmo gatilho reescreve, não duplica
        again.teach("Bom dia, professor", "só o clima")
        assertEquals(1, book(dir).all().count { !it.builtIn })
        assertEquals("só o clima", book(dir).match("bom dia professor")?.steps)
    }

    @Test fun disableAndRemove() {
        val dir = Files.createTempDirectory("skills").toFile()
        book(dir).setEnabled("modo-aula", false)
        assertNull(book(dir).match("modo aula"))
        book(dir).teach("minha rotina", "agenda e notícias")
        assertNotNull(book(dir).remove("minha rotina"))
        assertNull(book(dir).match("minha rotina"))
        assertNull(book(dir).remove("modo-aula")) // pronta não se apaga
    }

    @Test fun catalogHasUniqueIdsAndOnlyKnownTools() {
        val ids = SkillCatalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SkillCatalog.builtIn.size >= 20)
    }
}

class SkillTeachingTest {
    @Test fun withComma() {
        val l = SkillTeaching.parse("Quando eu disser bom dia professor, abra a agenda e me diga o clima")
        assertEquals(SkillTeaching.Lesson("bom dia professor", "abra a agenda e me diga o clima"), l)
    }

    @Test fun withQuotesAndFaca() {
        val l = SkillTeaching.parse("aprenda: sempre que eu falar \"modo prova\" faça silêncio no celular e abra o EduMath")
        assertEquals("modo prova", l?.trigger)
        assertEquals("silêncio no celular e abra o EduMath", l?.steps)
    }

    @Test fun withQueroQueVoce() {
        val l = SkillTeaching.parse("toda vez que eu disser cheguei em casa quero que você leia minhas notificações")
        assertEquals("cheguei em casa", l?.trigger)
        assertEquals("leia minhas notificações", l?.steps)
    }

    @Test fun notALesson() {
        assertNull(SkillTeaching.parse("quando eu disser isso"))
        assertNull(SkillTeaching.parse("abre a agenda"))
    }
}
