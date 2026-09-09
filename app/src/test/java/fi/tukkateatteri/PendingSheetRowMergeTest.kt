package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.mergePendingSheetRow
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSheetRowMergeTest {
    @Test
    fun independentAppAndSheetChangesAreMerged() {
        val base = row()
        val desired = base.copy(arrivalCount = 1)
        val remote = base.copy(contact = "new@example.fi")

        val result = mergePendingSheetRow(base, desired, remote)

        assertFalse(result.hasConflict)
        assertEquals(1, result.row.arrivalCount)
        assertEquals("new@example.fi", result.row.contact)
    }

    @Test
    fun competingChangesToTheSameFieldCauseAConflict() {
        val base = row()
        val desired = base.copy(contact = "app@example.fi")
        val remote = base.copy(contact = "sheet@example.fi")

        val result = mergePendingSheetRow(base, desired, remote)

        assertTrue(result.hasConflict)
    }

    @Test
    fun matchingChangesToTheSameFieldDoNotConflict() {
        val base = row()
        val desired = base.copy(arrivalCount = 1)
        val remote = base.copy(arrivalCount = 1)

        val result = mergePendingSheetRow(base, desired, remote)

        assertFalse(result.hasConflict)
        assertEquals(1, result.row.arrivalCount)
    }

    private fun row() = ReservationSpreadsheetRow(
        lastName = "Kippari",
        firstName = "Kalle",
        contact = "old@example.fi",
        reservedSeatCount = 2,
        arrivalCount = 0,
        reservedTicketCounts = mapOf(TicketType.BASIC to 2),
        paymentTicketCounts = emptyMap<PaymentMethod, Int>(),
        notes = "",
        sourceIdentity = "show|date|kippari|kalle",
        sheetRowId = "row-id"
    )
}
