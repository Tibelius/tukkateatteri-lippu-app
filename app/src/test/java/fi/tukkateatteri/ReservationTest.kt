package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationTest {
    @Test
    fun reservation_paymentProgress_isSeparateFromArrival() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            arrivalCount = 1,
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

        assertEquals(1, reservation.paidSeatCount)
        assertEquals(1, reservation.unpaidSeatCount)
        assertEquals(1, reservation.arrivalCount)
        assertFalse(reservation.isCompleted)
    }

    @Test
    fun reservedTicketTypes_doNotCountAsPayments() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            reservedTicketAllocations = listOf(
                fi.tukkateatteri.data.ReservedTicketAllocation(TicketType.BASIC, 2)
            )
        )

        assertEquals(0, reservation.paidSeatCount)
        assertEquals(0, reservation.arrivalCount)
        assertFalse(reservation.isCompleted)
    }

    @Test
    fun fullyRedeemedReservation_withDifferentTicketValue_isFlagged() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 1,
            reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.BASIC, 1)),
            ticketSales = listOf(
                TicketSale(
                    id = 1,
                    reservationId = 1,
                    ticketType = TicketType.DISCOUNT,
                    quantity = 1,
                    unitPriceCents = TicketType.DISCOUNT.defaultPriceCents,
                    payments = listOf(
                        PaymentAllocation(1, 1, PaymentMethod.CARD, TicketType.DISCOUNT.defaultPriceCents)
                    )
                )
            )
        )

        assertTrue(reservation.hasTicketValueMismatch)
    }

    @Test
    fun incompleteReservedTicketTypes_areNotFlaggedAsMismatch() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.BASIC, 1)),
            ticketSales = listOf(
                TicketSale(
                    id = 1,
                    reservationId = 1,
                    ticketType = TicketType.DISCOUNT,
                    quantity = 2,
                    unitPriceCents = TicketType.DISCOUNT.defaultPriceCents,
                    payments = listOf(
                        PaymentAllocation(1, 1, PaymentMethod.CARD, TicketType.DISCOUNT.defaultPriceCents * 2)
                    )
                )
            )
        )

        assertFalse(reservation.hasTicketValueMismatch)
    }

    @Test
    fun importedTicketSales_areNotFlaggedAsValueMismatch() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 1,
            reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.BASIC, 1)),
            ticketSales = listOf(
                TicketSale(
                    id = 1,
                    reservationId = 1,
                    ticketType = TicketType.UNSPECIFIED,
                    quantity = 1,
                    unitPriceCents = 0,
                    origin = TicketSaleOrigin.IMPORTED,
                    countsAsArrival = false,
                    payments = listOf(PaymentAllocation(1, 1, PaymentMethod.LIPPUAGENTTI, 0))
                )
            )
        )

        assertFalse(reservation.hasTicketValueMismatch)
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

    @Test
    fun doorSales_areExportedAsOneOveltaRow() {
        val doorSales = listOf(
            Reservation(
                id = 1,
                performanceId = 1,
                lastName = "",
                firstName = "",
                contact = "",
                seatCount = 1,
                arrivalCount = 1,
                admissionType = AdmissionType.DOOR_SALE,
                ticketSales = listOf(
                    TicketSale(
                        id = 1,
                        reservationId = 1,
                        ticketType = TicketType.BASIC,
                        quantity = 1,
                        unitPriceCents = 2_200,
                        payments = listOf(PaymentAllocation(1, 1, PaymentMethod.CASH, 2_200))
                    )
                )
            ),
            Reservation(
                id = 2,
                performanceId = 1,
                lastName = "",
                firstName = "",
                contact = "",
                seatCount = 1,
                arrivalCount = 1,
                admissionType = AdmissionType.DOOR_SALE,
                ticketSales = listOf(
                    TicketSale(
                        id = 2,
                        reservationId = 2,
                        ticketType = TicketType.DISCOUNT,
                        quantity = 1,
                        unitPriceCents = 1_300,
                        payments = listOf(PaymentAllocation(2, 2, PaymentMethod.CARD, 1_300))
                    )
                )
            )
        )

        val rows = ReservationSpreadsheetRow.fromReservations(doorSales)

        assertEquals(1, rows.size)
        assertEquals("Ovelta", rows.single().lastName)
        assertEquals(2, rows.single().arrivalCount)
        assertEquals(1, rows.single().reservedTicketCounts[TicketType.BASIC])
        assertEquals(1, rows.single().paymentTicketCounts[PaymentMethod.CARD])
    }

    @Test(expected = IllegalArgumentException::class)
    fun spreadsheetRows_rejectAnInvalidSeatCount() {
        ReservationSpreadsheetRow(
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            reservedSeatCount = 0,
            arrivalCount = 0,
            reservedTicketCounts = emptyMap(),
            paymentTicketCounts = emptyMap(),
            notes = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservations_rejectMoreArrivalsThanSeats() {
        Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 1,
            arrivalCount = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservations_rejectDuplicateReservedTicketTypes() {
        Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 2,
            reservedTicketAllocations = listOf(
                ReservedTicketAllocation(TicketType.BASIC, 1),
                ReservedTicketAllocation(TicketType.BASIC, 1)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun ticketSales_rejectPaymentsGreaterThanTheTicketPrice() {
        TicketSale(
            id = 1,
            reservationId = 1,
            ticketType = TicketType.BASIC,
            quantity = 1,
            unitPriceCents = 2_200,
            payments = listOf(
                PaymentAllocation(1, 1, PaymentMethod.CARD, 2_201)
            )
        )
    }

    @Test
    fun euroAmounts_useTheFinnishDecimalSeparator() {
        assertEquals("22,00 €", 2_200.toEuroString())
    }

    @Test
    fun performanceDisplayName_includesItsDateWhenAvailable() {
        val performance = Performance(
            id = 1,
            actName = "Yön Vuodenaika",
            date = "24.10.2026",
            isActive = true
        )

        assertEquals("Yön Vuodenaika 24.10.2026", performance.displayName)
    }

    @Test
    fun importedPerformance_canBeSyncedFromItsOriginalSheetTab() {
        val performance = Performance(
            id = 1,
            actName = "Yön Vuodenaika",
            date = "24.10.2026",
            isActive = true,
            sourceSheetTitle = "24.10"
        )

        assertTrue(performance.canSyncFromGoogleSheets)
    }

    @Test
    fun prepaidReservation_isNotCompletedBeforeArrival() {
        val reservation = Reservation(
            id = 1,
            performanceId = 1,
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            seatCount = 1,
            ticketSales = listOf(
                TicketSale(
                    id = 1,
                    reservationId = 1,
                    ticketType = TicketType.UNSPECIFIED,
                    quantity = 1,
                    unitPriceCents = 0,
                    origin = fi.tukkateatteri.data.TicketSaleOrigin.IMPORTED,
                    countsAsArrival = false,
                    payments = listOf(
                        PaymentAllocation(1, 1, PaymentMethod.LIPPUAGENTTI, 0)
                    )
                )
            )
        )

        assertTrue(reservation.isFullyRedeemed)
        assertFalse(reservation.isCompleted)
    }
}
