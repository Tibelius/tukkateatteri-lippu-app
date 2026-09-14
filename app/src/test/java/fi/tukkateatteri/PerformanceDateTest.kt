package fi.tukkateatteri

import fi.tukkateatteri.data.toPerformanceDateOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PerformanceDateTest {
    @Test
    fun validFinnishDatesAreParsed() {
        assertEquals(LocalDate.of(2026, 10, 24), "24.10.2026".toPerformanceDateOrNull())
        assertEquals(LocalDate.of(2027, 1, 1), "1.1.2027".toPerformanceDateOrNull())
        assertEquals(LocalDate.of(2024, 2, 29), " 29.2.2024 ".toPerformanceDateOrNull())
    }

    @Test
    fun trailingTimeOrSheetFormattingIsIgnoredAfterTheDate() {
        assertEquals(LocalDate.of(2026, 10, 24), "24.10.2026 18.30".toPerformanceDateOrNull())
        assertEquals(LocalDate.of(2026, 10, 24), "24.10.2026-extra".toPerformanceDateOrNull())
    }

    @Test
    fun invalidCalendarDatesAndFormatsAreRejected() {
        listOf(
            "",
            "31.2.2026",
            "29.2.2025",
            "0.10.2026",
            "24/10/2026",
            "2026-10-24",
            "24.10.26",
            "not a date"
        ).forEach { value -> assertNull("Expected '$value' to be rejected", value.toPerformanceDateOrNull()) }
    }
}
