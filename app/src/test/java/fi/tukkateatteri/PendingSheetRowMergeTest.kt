package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.mergePendingSheetRow
import fi.tukkateatteri.data.matches
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.Reservation
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

    @Test
    fun everyExportedFieldDetectsCompetingChanges() {
        val base = row()
        val changes = listOf<(ReservationSpreadsheetRow, String) -> ReservationSpreadsheetRow>(
            { value, suffix -> value.copy(lastName = "last-$suffix") },
            { value, suffix -> value.copy(firstName = "first-$suffix") },
            { value, suffix -> value.copy(contact = "contact-$suffix") },
            { value, suffix -> value.copy(reservedSeatCount = if (suffix == "app") 3 else 4) },
            { value, suffix -> value.copy(arrivalCount = if (suffix == "app") 1 else 2) },
            { value, suffix -> value.copy(reservedTicketCounts = mapOf(TicketType.DISCOUNT to if (suffix == "app") 1 else 2)) },
            { value, suffix -> value.copy(paymentTicketCounts = mapOf(PaymentMethod.CARD to if (suffix == "app") 1 else 2)) },
            { value, suffix -> value.copy(notes = "notes-$suffix") }
        )

        changes.forEach { change ->
            assertTrue(mergePendingSheetRow(base, change(base, "app"), change(base, "sheet")).hasConflict)
        }
    }

    @Test
    fun unchangedAppFieldsAlwaysRetainRemoteValues() {
        val base = row()
        val remote = base.copy(
            lastName = "Remote",
            firstName = "Person",
            contact = "remote@example.fi",
            reservedSeatCount = 3,
            arrivalCount = 1,
            reservedTicketCounts = mapOf(TicketType.DISCOUNT to 3),
            paymentTicketCounts = mapOf(PaymentMethod.CASH to 1),
            notes = "remote note"
        )

        val result = mergePendingSheetRow(base, base, remote)

        assertFalse(result.hasConflict)
        assertEquals(remote.copy(sourceIdentity = base.sourceIdentity, sheetRowId = base.sheetRowId), result.row)
    }

    @Test
    fun matchingPrefersStableIdentifiersThenFallsBackToCustomerIdentity() {
        val sheetRow = row()
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Different",
            firstName = "Person",
            contact = "different@example.fi",
            seatCount = 1,
            sourceIdentity = sheetRow.sourceIdentity,
            sheetRowId = "different-row-id"
        )

        assertTrue(sheetRow.matches(reservation))
        assertTrue(sheetRow.matches(sheetRow.copy(sourceIdentity = "different", sheetRowId = "row-id")))
        assertTrue(sheetRow.copy(sourceIdentity = "", sheetRowId = "").matches(
            reservation.copy(
                lastName = "KIPPArI",
                firstName = "kalle",
                contact = "OLD@EXAMPLE.FI",
                sourceIdentity = "",
                sheetRowId = ""
            )
        ))
    }

    @Test
    fun doorSalesNeverUseAmbiguousCustomerFieldFallback() {
        val doorRow = row().copy(lastName = "- Ovimyynti", sourceIdentity = "", sheetRowId = "")
        val doorReservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "- Ovimyynti",
            firstName = "",
            contact = "",
            seatCount = 1,
            admissionType = AdmissionType.DOOR_SALE
        )

        assertFalse(doorRow.matches(doorReservation))
        assertFalse(doorRow.matches(doorRow.copy()))
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
