package fi.tukkateatteri

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservationWithTicketSales
import fi.tukkateatteri.data.local.ReservedTicketAllocationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.TicketSaleWithPayments
import fi.tukkateatteri.data.local.toReservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationDatabaseMappingTest {
    @Test
    fun databaseGraph_mapsAllReservationAndPaymentFieldsToTheDomainModel() {
        val reservation = ReservationWithTicketSales(
            reservation = ReservationEntity(
                id = 10,
                performanceId = 2,
                lastName = "Kippari",
                firstName = "Kalle",
                contact = "kippari@example.com",
                seatCount = 2,
                notes = "Soita tarvittaessa",
                sourceIdentity = "yön vuodenaika|24.10.2026|kippari|kalle",
                admissionType = AdmissionType.RESERVATION,
                arrivalCount = 1,
                isPresent = true
            ),
            reservedTicketAllocations = listOf(
                ReservedTicketAllocationEntity(10, TicketType.BASIC, 1),
                ReservedTicketAllocationEntity(10, TicketType.DISCOUNT, 1)
            ),
            ticketSales = listOf(
                TicketSaleWithPayments(
                    ticketSale = TicketSaleEntity(
                        id = 20,
                        reservationId = 10,
                        ticketType = TicketType.BASIC,
                        quantity = 1,
                        unitPriceCents = 2_200,
                        origin = TicketSaleOrigin.MANUAL,
                        countsAsArrival = true
                    ),
                    payments = listOf(
                        PaymentAllocationEntity(30, 20, PaymentMethod.CASH, 1_000),
                        PaymentAllocationEntity(31, 20, PaymentMethod.CARD, 1_200)
                    )
                ),
                TicketSaleWithPayments(
                    ticketSale = TicketSaleEntity(
                        id = 21,
                        reservationId = 10,
                        ticketType = TicketType.UNSPECIFIED,
                        quantity = 1,
                        unitPriceCents = 0,
                        origin = TicketSaleOrigin.IMPORTED,
                        countsAsArrival = false
                    ),
                    payments = listOf(
                        PaymentAllocationEntity(32, 21, PaymentMethod.LIPPUAGENTTI, 0)
                    )
                )
            )
        ).toReservation()

        assertEquals(10, reservation.id)
        assertEquals(2, reservation.performanceId)
        assertEquals("Kippari Kalle", reservation.displayName)
        assertEquals(2, reservation.reservedTicketCount)
        assertEquals(2, reservation.paidSeatCount)
        assertTrue(reservation.isFullyRedeemed)
        assertFalse(reservation.isCompleted)
        assertFalse(reservation.hasTicketValueMismatch)
        assertTrue(reservation.ticketSales.first().hasMultiplePayments)
        assertEquals(TicketSaleOrigin.IMPORTED, reservation.ticketSales.last().origin)
        assertFalse(reservation.ticketSales.last().countsAsArrival)
    }

    @Test
    fun doorSaleDatabaseGraph_mapsWithoutCustomerDetails() {
        val reservation = ReservationWithTicketSales(
            reservation = ReservationEntity(
                id = 1,
                performanceId = 1,
                lastName = "",
                firstName = "",
                contact = "",
                seatCount = 1,
                admissionType = AdmissionType.DOOR_SALE,
                arrivalCount = 1,
                isPresent = true
            ),
            ticketSales = emptyList(),
            reservedTicketAllocations = emptyList()
        ).toReservation()

        assertEquals(AdmissionType.DOOR_SALE, reservation.admissionType)
        assertTrue(reservation.displayName.isBlank())
        assertTrue(reservation.isPresent)
    }
}
