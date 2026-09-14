package fi.tukkateatteri

import fi.tukkateatteri.data.toDecimalInput
import fi.tukkateatteri.data.toEuroCentsOrNull
import fi.tukkateatteri.data.toEuroString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun decimalInputAcceptsSupportedPrecisionAndWhitespace() {
        val cases = mapOf(
            " 0 " to 0,
            "0,0" to 0,
            "0,01" to 1,
            "1,2" to 120,
            "001.20" to 120,
            "+2,50" to 250,
            "21474836,47" to Int.MAX_VALUE
        )

        cases.forEach { (input, cents) -> assertEquals(cents, input.toEuroCentsOrNull()) }
    }

    @Test
    fun decimalInputRejectsOverflowAndUnsupportedNotation() {
        listOf(
            "21474836,48",
            "1 000,00",
            "1,2,3",
            "NaN",
            "Infinity",
            "--1",
            "€22"
        ).forEach { input -> assertNull("Expected '$input' to be rejected", input.toEuroCentsOrNull()) }
    }

    @Test
    fun euroDisplayRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException::class.java) { (-1).toEuroString() }
    }
}
