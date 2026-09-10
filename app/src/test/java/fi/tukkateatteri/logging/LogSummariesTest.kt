package fi.tukkateatteri.logging

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.ApplicationMutationMetadataState
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummariesTest {
    @Test
    fun reservationSummary_excludesPersonalAndFreeTextFields() {
        val reservation = Reservation(
            id = 42,
            performanceId = 7,
            lastName = "SalainenSukunimi",
            firstName = "SalainenEtunimi",
            contact = "salainen@example.com",
            seatCount = 2,
            notes = "Salainen huomautus",
            sheetRowId = "12345678-1234-1234-1234-123456789abc",
            syncState = ReservationSyncState.PENDING,
            admissionType = AdmissionType.RESERVATION,
            reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.BASIC, 2))
        )

        val summary = reservation.toLogSummary()

        assertTrue(summary.contains("reservationId=42"))
        assertTrue(summary.contains("BASIC=2"))
        assertTrue(summary.contains("sheetRowId=12345678"))
        assertFalse(summary.contains("Salainen"))
        assertFalse(summary.contains("salainen@example.com"))
    }

    @Test
    fun spreadsheetRowSummary_excludesPersonalAndFreeTextFields() {
        val row = ReservationSpreadsheetRow(
            lastName = "SalainenSukunimi",
            firstName = "SalainenEtunimi",
            contact = "salainen@example.com",
            reservedSeatCount = 1,
            arrivalCount = 0,
            reservedTicketCounts = mapOf(TicketType.DISCOUNT to 1),
            paymentTicketCounts = emptyMap(),
            notes = "Salainen huomautus",
            sheetRowId = "abcdefgh-1234-1234-1234-123456789abc",
            sourceRowNumber = 5,
            applicationMutationMetadataState = ApplicationMutationMetadataState.VALID
        )

        val summary = row.toLogSummary()

        assertTrue(summary.contains("sheetRow=5"))
        assertTrue(summary.contains("DISCOUNT=1"))
        assertFalse(summary.contains("Salainen"))
        assertFalse(summary.contains("salainen@example.com"))
    }

    @Test
    fun exceptionReason_redactsCommonAccessTokenForms() {
        val reason = IllegalStateException(
            "Authorization: Bearer secret-token access_token=another-secret&next=value " +
                "https://docs.google.com/spreadsheets/d/private-sheet-id/edit"
        ).toSanitizedLogReason()

        assertTrue(reason.contains("Bearer [redacted]"))
        assertTrue(reason.contains("access_token=[redacted]"))
        assertFalse(reason.contains("secret-token"))
        assertFalse(reason.contains("another-secret"))
        assertFalse(reason.contains("private-sheet-id"))
    }
}
