package fi.tukkateatteri.ui.screens

import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationFilterTest {
    @Test
    fun allIncludesCompletedAndUnfinishedReservations() {
        assertTrue(ReservationFilter.ALL.includes(reservation()))
        assertTrue(ReservationFilter.ALL.includes(reservation(paid = false, arrived = false)))
    }

    @Test
    fun incompleteExcludesCompletedAndSynchronizedReservations() {
        assertFalse(ReservationFilter.INCOMPLETE.includes(reservation()))
    }

    @Test
    fun prepaidCustomerStillNeedsArrivalRecorded() {
        assertTrue(ReservationFilter.INCOMPLETE.includes(reservation(arrived = false)))
    }

    @Test
    fun presentCustomerStillNeedsPaymentRecorded() {
        assertTrue(ReservationFilter.INCOMPLETE.includes(reservation(paid = false)))
    }

    @Test
    fun completedReservationsRemainVisibleUntilUploadAndConflictsAreResolved() {
        listOf(
            ReservationSyncState.PENDING,
            ReservationSyncState.CONFLICT,
            ReservationSyncState.PENDING_DELETION
        ).forEach { syncState ->
            assertTrue(ReservationFilter.INCOMPLETE.includes(reservation().copy(syncState = syncState)))
        }
    }

    @Test
    fun unexpectedTicketValueRemainsVisibleForReview() {
        val reservation = reservation().copy(
            reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.DISCOUNT, 1))
        )

        assertTrue(reservation.isCompleted)
        assertTrue(ReservationFilter.INCOMPLETE.includes(reservation))
    }

    private fun reservation(paid: Boolean = true, arrived: Boolean = true) = Reservation(
        id = 1,
        performanceId = 1,
        lastName = "Virtanen",
        firstName = "Maija",
        contact = "",
        seatCount = 1,
        arrivalCount = if (arrived) 1 else 0,
        ticketSales = if (paid) {
            listOf(TicketSale(
                id = 1,
                reservationId = 1,
                ticketType = TicketType.BASIC,
                quantity = 1,
                unitPriceCents = 2_200,
                payments = listOf(PaymentAllocation(1, 1, PaymentMethod.LIPPUAGENTTI, 2_200))
            ))
        } else {
            emptyList()
        }
    )
}
