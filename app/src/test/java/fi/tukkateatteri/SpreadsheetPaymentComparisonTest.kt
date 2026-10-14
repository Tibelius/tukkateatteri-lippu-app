package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.mergePendingSheetRow
import fi.tukkateatteri.data.spreadsheet.RealizedTicketSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.ApplicationMutationMetadataState
import fi.tukkateatteri.data.spreadsheet.ApplicationRowState
import fi.tukkateatteri.data.spreadsheet.GoogleSheetTab
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.SpreadsheetPaymentAllocation
import fi.tukkateatteri.data.spreadsheet.canonicalHeaderIndexes
import fi.tukkateatteri.data.spreadsheet.hasSameSheetContentAs
import fi.tukkateatteri.data.spreadsheet.sheetContentHash
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRowSnapshot
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import fi.tukkateatteri.data.spreadsheet.toImportCandidateOrNull
import fi.tukkateatteri.data.spreadsheet.toPhysicalSheetRows
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRows
import fi.tukkateatteri.data.spreadsheet.toSheetCellValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SpreadsheetPaymentComparisonTest {
    @Test
    fun realExportAndImportKeepSplitPaymentsAndValidApplicationMetadata() {
        val local = row(cash(1_000), card(1_000), card(200))
        val headers = listOf(
            "Sukunimi", "Etunimi", "Yhteystiedot", "SAAPUNUT", "Perus 22 €", "KORTTI", "KÄTEINEN", "HUOM!"
        )
        val values = local.toPhysicalSheetRows().single()
            .toSheetCellValues("24.10", 2, headers.canonicalHeaderIndexes())
            .map { it.value.toString() }
        val rowId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(headers, values, listOf("Esitys:", "Testi"), listOf("Pvm:", "24.10.2026")),
            sheetId = 42,
            rowIdsByRowNumber = mapOf(2 to rowId),
            applicationRowStates = mapOf(rowId to ApplicationRowState(
                sheetId = 42, rowId = rowId, contentHash = local.sheetContentHash(),
                modifiedAt = Instant.parse("2026-10-14T12:00:00Z"), operation = "Muutos",
                mutationId = "4e97744e-ef31-4d40-84d7-28e821af23a9", rowNumber = 2
            ))
        )

        val imported = tab.toReservationSpreadsheetRows(requireNotNull(tab.toImportCandidateOrNull())).single()

        assertTrue(local.hasSameSheetContentAs(imported))
        assertEquals(ApplicationMutationMetadataState.VALID, imported.applicationMutationMetadataState)
        assertEquals(2, imported.realizedTickets.single().payments.size)
    }

    @Test
    fun paymentOrderDoesNotChangeSheetContentOrHash() {
        val row = row(cash(1_000), card(1_200))
        val reordered = row(card(1_200), cash(1_000))

        assertTrue(row.hasSameSheetContentAs(reordered))
        assertEquals(row.sheetContentHash(), reordered.sheetContentHash())
    }

    @Test
    fun repeatedPaymentsCompareToOneSheetAmountWithoutLosingLocalHistory() {
        val row = row(card(2_000), card(200))
        val imported = row(card(2_200))

        assertTrue(row.hasSameSheetContentAs(imported))
        assertEquals(row.sheetContentHash(), imported.sheetContentHash())
        assertEquals(2, row.toSnapshotJson().toReservationSpreadsheetRowSnapshot().realizedTickets.single().payments.size)
    }

    @Test
    fun movingColumnsDoesNotChangeOperationalContent() {
        val row = row(card(2_200))
        val imported = row.copy(
            reservedTicketCounts = mapOf(TicketType.BASIC.copy(sortOrder = 9) to 1),
            realizedTickets = listOf(
                row.realizedTickets.single().copy(
                    ticketType = TicketType.BASIC.copy(sortOrder = 9),
                    payments = listOf(SpreadsheetPaymentAllocation(PaymentMethod.CARD.copy(sortOrder = 13), 2_200))
                )
            )
        )

        assertTrue(row.hasSameSheetContentAs(imported))
        assertEquals(row.sheetContentHash(), imported.sheetContentHash())
    }

    @Test
    fun monetaryAndPaymentMethodChangesStillAffectContent() {
        val row = row(card(2_200))

        assertFalse(row.hasSameSheetContentAs(row(card(2_100))))
        assertFalse(row.hasSameSheetContentAs(row(cash(2_200))))
        assertFalse(row.sheetContentHash() == row(cash(2_200)).sheetContentHash())
    }

    @Test
    fun equivalentRemotePaymentOrderDoesNotConflictWithARealLocalEdit() {
        val base = row(cash(1_000), card(1_000))
        val desired = row(cash(1_000), card(1_200))
        val remote = row(card(1_000), cash(1_000))

        val result = mergePendingSheetRow(base, desired, remote)

        assertFalse(result.hasConflict)
        assertEquals(desired.realizedTickets, result.row.realizedTickets)
    }

    @Test
    fun competingPaymentEditsStillConflict() {
        val base = row(cash(1_000), card(1_000))
        val desired = row(cash(1_000), card(1_200))
        val remote = row(card(900), cash(1_000))

        assertTrue(mergePendingSheetRow(base, desired, remote).hasConflict)
    }

    private fun row(vararg payments: SpreadsheetPaymentAllocation) = ReservationSpreadsheetRow(
        lastName = "Virtanen", firstName = "Maija", contact = "",
        reservedSeatCount = 1, arrivalCount = 1,
        reservedTicketCounts = mapOf(TicketType.BASIC to 1),
        paymentAmountsCents = emptyMap(), notes = "",
        realizedTickets = listOf(RealizedTicketSpreadsheetRow(TicketType.BASIC, payments.toList(), arrived = true))
    )

    private fun card(amount: Int) = SpreadsheetPaymentAllocation(PaymentMethod.CARD, amount)
    private fun cash(amount: Int) = SpreadsheetPaymentAllocation(PaymentMethod.CASH, amount)
}
