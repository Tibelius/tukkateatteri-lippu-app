package fi.tukkateatteri

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.calculateSalesStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesStatisticsTest {
    @Test
    fun calculateSalesStatistics_separatesReservationAndDoorRevenue() {
        val statistics = calculateSalesStatistics(
            listOf(
                reservation(
                    id = 1,
                    seatCount = 2,
                    arrivalCount = 1,
                    reservedTickets = listOf(ReservedTicketAllocation(TicketType.BASIC, 2)),
                    sales = listOf(sale(1, 1, TicketType.BASIC, PaymentMethod.CARD))
                ),
                reservation(
                    id = 2,
                    seatCount = 1,
                    arrivalCount = 1,
                    admissionType = AdmissionType.DOOR_SALE,
                    sales = listOf(sale(2, 2, TicketType.DISCOUNT, PaymentMethod.CASH))
                )
            )
        )

        assertEquals(3, statistics.seatCount)
        assertEquals(1, statistics.reservationCount)
        assertEquals(1, statistics.doorSaleCount)
        assertEquals(2, statistics.redeemedSeatCount)
        assertEquals(2, statistics.arrivalCount)
        assertEquals(3_500, statistics.revenueCents)
        assertEquals(2_200, statistics.reservationRevenueCents)
        assertEquals(1_300, statistics.doorSaleRevenueCents)
        assertEquals(4_400, statistics.expectedReservationRevenueCents)
        assertEquals(-2_200, statistics.reservationRevenueDifferenceCents)
        assertTrue(statistics.hasCompleteRevenueExpectation)
        assertEquals(listOf("BASIC", "DISCOUNT"), statistics.ticketTypes.map { it.name })
        assertEquals(listOf("CARD", "CASH"), statistics.paymentMethods.map { it.name })
    }

    @Test
    fun calculateSalesStatistics_excludesPendingDeletionsAndReportsUnknownPrices() {
        val importedSale = TicketSale(
            id = 3,
            reservationId = 3,
            ticketType = TicketType.UNSPECIFIED,
            quantity = 2,
            unitPriceCents = 0,
            origin = TicketSaleOrigin.IMPORTED,
            countsAsArrival = false,
            payments = listOf(PaymentAllocation(3, 3, PaymentMethod.LIPPUAGENTTI, 0))
        )
        val statistics = calculateSalesStatistics(
            listOf(
                reservation(id = 3, seatCount = 2, sales = listOf(importedSale)),
                reservation(
                    id = 4,
                    syncState = ReservationSyncState.PENDING_DELETION,
                    sales = listOf(sale(4, 4, TicketType.BASIC, PaymentMethod.CARD))
                )
            )
        )

        assertEquals(1, statistics.reservationCount)
        assertEquals(2, statistics.redeemedSeatCount)
        assertEquals(2, statistics.unknownRevenueSeatCount)
        assertEquals(0, statistics.revenueCents)
        assertFalse(statistics.canCompareReservationRevenue)
    }

    @Test
    fun calculateSalesStatistics_usesCanonicalPaymentOrderAndCountsTickets() {
        val importedCard = PaymentMethod("CARD", "Kortti", sortOrder = 99)
        val importedCash = PaymentMethod("CASH", "Käteinen", sortOrder = 0)
        val statistics = calculateSalesStatistics(
            listOf(
                reservation(
                    id = 5,
                    seatCount = 2,
                    sales = listOf(sale(5, 5, TicketType.BASIC, importedCard, quantity = 2))
                ),
                reservation(
                    id = 6,
                    seatCount = 3,
                    sales = listOf(sale(6, 6, TicketType.BASIC, importedCash, quantity = 3))
                )
            )
        )

        assertEquals(listOf("CARD", "CASH"), statistics.paymentMethods.map { it.name })
        assertEquals(listOf(2, 3), statistics.paymentMethods.map { it.ticketCount })
        assertEquals(
            listOf("CARD", "CASH"),
            statistics.ticketTypes.single().paymentMethods.map { it.name }
        )
        assertEquals(
            listOf(2, 3),
            statistics.ticketTypes.single().paymentMethods.map { it.ticketCount }
        )
    }

    @Test
    fun calculateSalesStatistics_reportsSplitPaymentsWithoutDoubleCountingTickets() {
        val splitSale = TicketSale(
            id = 7,
            reservationId = 7,
            ticketType = TicketType.BASIC,
            quantity = 2,
            unitPriceCents = TicketType.BASIC.defaultPriceCents,
            payments = listOf(
                PaymentAllocation(7, 7, PaymentMethod.CASH, 1_000),
                PaymentAllocation(8, 7, PaymentMethod.CARD, 3_400)
            )
        )

        val methods = calculateSalesStatistics(
            listOf(reservation(id = 7, seatCount = 2, sales = listOf(splitSale)))
        ).paymentMethods

        assertEquals(listOf("CARD", "CASH"), methods.map { it.name })
        assertTrue(methods.all { it.ticketCount == 0 })
        assertTrue(methods.all { it.splitPaymentCount == 1 })
    }

    private fun reservation(
        id: Long,
        seatCount: Int = 1,
        arrivalCount: Int = 0,
        admissionType: AdmissionType = AdmissionType.RESERVATION,
        syncState: ReservationSyncState = ReservationSyncState.SYNCED,
        reservedTickets: List<ReservedTicketAllocation> = emptyList(),
        sales: List<TicketSale> = emptyList()
    ) = Reservation(
        id = id,
        performanceId = 1,
        lastName = if (admissionType == AdmissionType.RESERVATION) "Testi" else "",
        firstName = if (admissionType == AdmissionType.RESERVATION) "Asiakas" else "",
        contact = "",
        seatCount = seatCount,
        syncState = syncState,
        admissionType = admissionType,
        arrivalCount = arrivalCount,
        reservedTicketAllocations = reservedTickets,
        ticketSales = sales
    )

    private fun sale(
        id: Long,
        reservationId: Long,
        ticketType: TicketType,
        paymentMethod: PaymentMethod,
        quantity: Int = 1
    ) = TicketSale(
        id = id,
        reservationId = reservationId,
        ticketType = ticketType,
        quantity = quantity,
        unitPriceCents = ticketType.defaultPriceCents,
        payments = listOf(
            PaymentAllocation(id, id, paymentMethod, ticketType.defaultPriceCents * quantity)
        )
    )
}
