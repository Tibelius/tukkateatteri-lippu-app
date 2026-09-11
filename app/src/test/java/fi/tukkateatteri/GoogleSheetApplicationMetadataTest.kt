package fi.tukkateatteri

import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.sheetContentHash
import fi.tukkateatteri.data.spreadsheet.toApplicationRowStateTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSheetApplicationMetadataTest {
    @Test
    fun applicationRowStateTable_parsesValidAndInvalidRowsFromItsOwnColumnArea() {
        val header = MutableList(19) { "" }.apply {
            this[13] = "sheet_id"
            this[14] = "row_uuid"
            this[15] = "content_hash"
            this[16] = "modified_at"
            this[17] = "operation"
            this[18] = "mutation_id"
        }
        val valid = MutableList(19) { "" }.apply {
            this[13] = "42"
            this[14] = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
            this[15] = "a".repeat(64)
            this[16] = "2026-09-09T12:00:00Z"
            this[17] = "Muutos"
            this[18] = "4e97744e-ef31-4d40-84d7-28e821af23a9"
        }
        val invalid = valid.toMutableList().apply {
            this[14] = "4975807c-18a8-40af-b217-e5e856e65bf4"
            this[17] = "Tuntematon"
        }

        val table = listOf(header, valid, invalid).toApplicationRowStateTable()

        assertTrue(table.rows[0].isValid)
        assertFalse(table.rows[1].isValid)
    }

    @Test
    fun contentHash_ignoresLocalAndRemoteIdentityFields() {
        val row = ReservationSpreadsheetRow(
            lastName = "Testaaja",
            firstName = "Tiina",
            contact = "test@example.com",
            reservedSeatCount = 1,
            arrivalCount = 0,
            reservedTicketCounts = emptyMap(),
            paymentTicketCounts = emptyMap(),
            notes = ""
        )

        assertEquals(
            row.sheetContentHash(),
            row.copy(
                sourceIdentity = "different",
                sheetRowId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe",
                sourceRowNumber = 99
            ).sheetContentHash()
        )
    }
}
