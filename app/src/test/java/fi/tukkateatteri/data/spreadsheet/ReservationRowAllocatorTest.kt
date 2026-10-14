package fi.tukkateatteri.data.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservationRowAllocatorTest {
    @Test
    fun reusesGapsWithoutOverwritingRowsBetweenThem() {
        val allocator = allocator(
            listOf("", "", "FALSE"),
            listOf("Virtanen", "Maija", "TRUE"),
            listOf("", "", "FALSE"),
            listOf("Varauksia: 1")
        )

        assertEquals(ReservationRowPlacement(2, false), allocator.allocate())
        assertEquals(ReservationRowPlacement(4, false), allocator.allocate())
        assertEquals(ReservationRowPlacement(5, true), allocator.allocate())
        assertEquals(ReservationRowPlacement(6, true), allocator.allocate())
    }

    @Test
    fun preservesUnnamedRowsWithPaymentsOrNotes() {
        val allocator = allocator(
            listOf("", "", "22"),
            listOf("", "", "Asiakkaan huomautus"),
            listOf("Varaukset yhteensä:")
        )

        assertEquals(ReservationRowPlacement(4, true), allocator.allocate())
    }

    @Test
    fun preservesRowsWithApplicationIdentityEvenWhenCellsAreBlank() {
        val allocator = ReservationRowAllocator(
            rows = listOf(listOf("Sukunimi"), emptyList(), listOf("Varauksia: 1")),
            headerRowIndex = 0,
            rowIdsByRowNumber = mapOf(2 to "app-row")
        )

        assertEquals(ReservationRowPlacement(3, true), allocator.allocate())
    }

    @Test
    fun insertsBeforePerformanceMetadataWhenThereIsNoSummary() {
        val allocator = allocator(
            listOf("Virtanen", "Maija"),
            listOf("Esitys:", "Yön Vuodenaika"),
            listOf("Pvm:", "24.10.2026")
        )

        assertEquals(ReservationRowPlacement(3, true), allocator.allocate())
    }

    private fun allocator(vararg rows: List<String>) = ReservationRowAllocator(
        rows = listOf(listOf("Sukunimi", "Etunimi")) + rows,
        headerRowIndex = 0,
        rowIdsByRowNumber = emptyMap()
    )
}
