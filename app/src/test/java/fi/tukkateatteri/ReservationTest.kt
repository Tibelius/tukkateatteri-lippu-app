package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationTest {
    @Test
    fun completedReservation_requiresPresenceAndPaymentMethod() {
        val reservation = Reservation(
            id = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            isPresent = true,
            paymentMethod = PaymentMethod.CARD
        )

        assertTrue(reservation.isCompleted)
        assertFalse(reservation.copy(paymentMethod = null).isCompleted)
        assertFalse(reservation.copy(isPresent = false).isCompleted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun spreadsheetRows_rejectAnInvalidSeatCount() {
        ReservationSpreadsheetRow(
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 0,
            admissionType = AdmissionType.RESERVATION,
            isPresent = false,
            paymentMethod = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun doorSale_requiresAPaymentMethod() {
        Reservation(
            id = 1,
            lastName = "",
            firstName = "",
            contact = "",
            seatCount = 1,
            admissionType = AdmissionType.DOOR_SALE,
            isPresent = true,
            paymentMethod = null
        )
    }
}
