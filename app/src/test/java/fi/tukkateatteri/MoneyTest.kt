package fi.tukkateatteri

import fi.tukkateatteri.data.toDecimalInput
import fi.tukkateatteri.data.toEuroCentsOrNull
import fi.tukkateatteri.data.toEuroString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun centsAreFormattedForFinnishDisplayAndInput() {
        assertEquals("0,00 €", 0.toEuroString())
        assertEquals("22,00 €", 2_200.toEuroString())
        assertEquals("12,34", 1_234.toDecimalInput())
    }

    @Test
    fun decimalInputAcceptsCommaAndPeriod() {
        assertEquals(2_200, "22".toEuroCentsOrNull())
        assertEquals(1_350, "13,50".toEuroCentsOrNull())
        assertEquals(1_350, "13.50".toEuroCentsOrNull())
    }

    @Test
    fun invalidOrOverPreciseDecimalInputIsRejected() {
        assertNull("".toEuroCentsOrNull())
        assertNull("not money".toEuroCentsOrNull())
        assertNull("1,234".toEuroCentsOrNull())
    }
}
