package fi.tukkateatteri.data.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSheetFormatTest {
    @Test
    fun currentSourceIdentityReturnsCustomerNameInsteadOfContact() {
        assertEquals(
            SourceCustomerIdentity("kippari", "kalle", "kalle@example.com"),
            "show|date|kippari|kalle|kalle@example.com".toCustomerIdentityOrNull()
        )
    }

    @Test
    fun legacySourceIdentityStillReturnsCustomerName() {
        assertEquals(
            SourceCustomerIdentity("kippari", "kalle", null),
            "show|date|kippari|kalle".toCustomerIdentityOrNull()
        )
    }

    @Test
    fun doorSaleIdentityDoesNotRepresentACustomerName() {
        assertNull("show|date|ovelta|3".toCustomerIdentityOrNull())
    }

    @Test
    fun doorSaleSourcePositionMatchesOnlyAVerifiedDoorSaleRow() {
        val row = ReservationSpreadsheetRow(
            lastName = DOOR_SALE_SHEET_LABEL,
            firstName = "",
            contact = "",
            reservedSeatCount = 1,
            arrivalCount = 1,
            reservedTicketCounts = emptyMap(),
            paymentTicketCounts = emptyMap(),
            notes = "",
            sourceIdentity = "show|date|ovelta|0"
        )
        val headers = mapOf(HEADER_LAST_NAME to 0)

        assertEquals(
            2,
            row.sourceMatchedDoorSaleRowNumber(
                existingRows = listOf(listOf("Sukunimi"), listOf(DOOR_SALE_SHEET_LABEL)),
                headerRowIndex = 0,
                headers = headers
            )
        )
        assertNull(
            row.sourceMatchedDoorSaleRowNumber(
                existingRows = listOf(listOf("Sukunimi"), listOf("Virtanen")),
                headerRowIndex = 0,
                headers = headers
            )
        )
        assertTrue(row.isDoorSale)
    }
}
