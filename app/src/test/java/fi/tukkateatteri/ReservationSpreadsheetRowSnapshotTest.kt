package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.hasSameSheetContentAs
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRowSnapshot
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationSpreadsheetRowSnapshotTest {
    @Test
    fun snapshotRoundTripPreservesEveryExportedValue() {
        val row = row()

        val restored = row.toSnapshotJson().toReservationSpreadsheetRowSnapshot()

        assertTrue(restored.hasSameSheetContentAs(row))
        assertTrue(restored.sheetRowId == row.sheetRowId)
        assertTrue(restored.sourceIdentity == row.sourceIdentity)
    }

    @Test
    fun sheetContentComparisonIgnoresOnlyTheLocalIdentityFields() {
        val remoteRow = row()
        val localRowWithAssignedId = remoteRow.copy(
            sourceIdentity = "",
            sheetRowId = "new-local-id"
        )

        assertTrue(localRowWithAssignedId.hasSameSheetContentAs(remoteRow))
        assertFalse(localRowWithAssignedId.copy(arrivalCount = 1).hasSameSheetContentAs(remoteRow))
        assertFalse(
            localRowWithAssignedId.copy(paymentTicketCounts = mapOf(PaymentMethod.CARD to 2))
                .hasSameSheetContentAs(remoteRow)
        )
    }

    private fun row() = ReservationSpreadsheetRow(
        lastName = "Kippari",
        firstName = "Kalle",
        contact = "kippari@example.com",
        reservedSeatCount = 2,
        arrivalCount = 0,
        reservedTicketCounts = mapOf(TicketType.BASIC to 2),
        paymentTicketCounts = mapOf(PaymentMethod.LIPPUAGENTTI to 1),
        notes = "Testihuomautus",
        sourceIdentity = "sheet:abc",
        sheetRowId = "abc"
    )
}
