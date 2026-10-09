package com.joctaeng.jarvis.system.resources

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessagingTest {
    @Test
    fun brazilianNumbersGetCountryCode() {
        assertEquals("5598987177598", PhoneNumber.forWhatsApp("98987177598"))
        assertEquals("5598987177598", PhoneNumber.forWhatsApp("(98) 98717-7598"))
        assertEquals("5598987177598", PhoneNumber.forWhatsApp("+55 98 98717-7598"))
        assertEquals("559832215566", PhoneNumber.forWhatsApp("098 3221-5566"))
        assertNull(PhoneNumber.forWhatsApp("123"))
    }

    @Test
    fun eventTimeAcceptsCommonForms() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val a = EventTime.parseMillis("2026-10-09 15:30", zone)
        assertNotNull(a)
        assertEquals(a, EventTime.parseMillis("2026-10-09T15:30", zone))
        assertEquals(a, EventTime.parseMillis("09/10/2026 15:30", zone))
        assertNull(EventTime.parseMillis("amanhã às 3", zone))
    }
}
