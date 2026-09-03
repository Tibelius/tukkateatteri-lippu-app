package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationTest {
    @Test
    fun reservation_progress_isDerivedFromTicketSales() {
        val reservation = Reservation(
            id = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            ticketSales = listOf(
                TicketSale(
                    id = 1,
                    reservationId = 1,
                    ticketType = TicketType.BASIC,
                    quantity = 1,
                    unitPriceCents = 2_200,
                    payments = listOf(PaymentAllocation(1, 1, PaymentMethod.CARD, 2_200))
                )
            )
        )

        assertEquals(1, reservation.redeemedSeatCount)
        assertEquals(1, reservation.remainingSeatCount)
        assertFalse(reservation.isCompleted)
    }

    @Test
    fun splitPayment_requiresTheWholeTicketPrice() {
        val ticketSale = TicketSale(
            id = 1,
            reservationId = 1,
            ticketType = TicketType.BASIC,
            quantity = 1,
            unitPriceCents = 2_200,
            payments = listOf(
                PaymentAllocation(1, 1, PaymentMethod.CASH, 1_000),
                PaymentAllocation(2, 1, PaymentMethod.CARD, 1_200)
            )
        )

        assertTrue(ticketSale.isPaid)
        assertTrue(ticketSale.isSplitPayment)
    }

    @Test(expected = IllegalArgumentException::class)
    fun spreadsheetRows_rejectAnInvalidSeatCount() {
        ReservationSpreadsheetRow(
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            reservedSeatCount = 0,
            redeemedSeatCount = 0,
            ticketCounts = emptyMap(),
            paymentTicketCounts = emptyMap(),
            notes = ""
        )
    }
}
