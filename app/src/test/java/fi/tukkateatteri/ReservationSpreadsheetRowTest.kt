package fi.tukkateatteri

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.toPhysicalSheetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationSpreadsheetRowTest {
    @Test
    fun physicalRowsRepresentExactlyOneSeatEach() {
        val row = ReservationSpreadsheetRow.fromReservation(
            reservation(
                seatCount = 3,
                reservedTicketAllocations = listOf(
                    ReservedTicketAllocation(TicketType.BASIC, 1),
                    ReservedTicketAllocation(TicketType.DISCOUNT, 1)
                )
            )
        )

        val physicalRows = row.toPhysicalSheetRows()

        assertEquals(3, physicalRows.size)
        assertTrue(physicalRows.all { it.reservedSeatCount == 1 })
        assertEquals(1, physicalRows[0].reservedTicketCounts.values.single())
        assertEquals(1, physicalRows[1].reservedTicketCounts.values.single())
        assertTrue(physicalRows[2].reservedTicketCounts.isEmpty())
    }

    @Test
    fun exportRows_keepsReservationsSeparateAndPreservesAllTicketCategories() {
        val rows = ReservationSpreadsheetRow.fromReservations(
            listOf(
                reservation(
                    id = 1,
                    reservedTicketAllocations = listOf(
                        ReservedTicketAllocation(TicketType.BASIC, 1),
                        ReservedTicketAllocation(TicketType.FREE_TICKET, 1)
                    ),
                    ticketSales = listOf(
                        sale(1, TicketType.BASIC, 1, PaymentMethod.CARD),
                        sale(2, TicketType.FREE_TICKET, 1, null)
                    )
                ),
                reservation(
                    id = 2,
                    lastName = "Toinen",
                    firstName = "Testi",
                    reservedTicketAllocations = listOf(ReservedTicketAllocation(TicketType.KAIKUKORTTI, 2)),
                    ticketSales = listOf(sale(3, TicketType.KAIKUKORTTI, 2, null))
                )
            )
        )

        assertEquals(2, rows.size)
        assertEquals(1, rows[0].reservedTicketCounts[TicketType.BASIC])
        assertEquals(1, rows[0].reservedTicketCounts[TicketType.FREE_TICKET])
        assertEquals(PaymentMethod.CARD, rows[0].realizedTickets[0].payments.single().method)
        assertEquals(2, rows[1].reservedTicketCounts[TicketType.KAIKUKORTTI])
        assertEquals(2, rows[1].realizedTickets.size)
    }

    @Test
    fun exportRows_recordsSplitPaymentsInNotesWithoutAssigningThemToOnePaymentColumn() {
        val splitSale = TicketSale(
            id = 1,
            reservationId = 1,
            ticketType = TicketType.BASIC,
            quantity = 1,
            unitPriceCents = TicketType.BASIC.defaultPriceCents,
            payments = listOf(
                PaymentAllocation(1, 1, PaymentMethod.CASH, 1_000),
                PaymentAllocation(2, 1, PaymentMethod.CARD, 1_200)
            )
        )

        val row = ReservationSpreadsheetRow.fromReservation(reservation(ticketSales = listOf(splitSale)))

        assertTrue(row.paymentTicketCounts.isEmpty())
        val realized = row.realizedTickets.single()
        assertEquals(listOf(1_000, 1_200), realized.payments.map { it.amountCents })
        val physicalRow = row.toPhysicalSheetRows().first { it.notes.contains("Osamaksu") }
        assertEquals(1, physicalRow.paymentTicketCounts[PaymentMethod.CARD])
        assertTrue(physicalRow.notes.contains("Kortti 12,00 €; Käteinen 10,00 €"))
    }

    @Test
    fun exportRows_recordsPartialPaymentsAsNotesInsteadOfCompletedTicketCounts() {
        val partialSale = TicketSale(
            id = 1,
            reservationId = 1,
            ticketType = TicketType.BASIC,
            quantity = 1,
            unitPriceCents = TicketType.BASIC.defaultPriceCents,
            payments = listOf(
                PaymentAllocation(1, 1, PaymentMethod.CARD, 2_000, zettleSuccessful = true)
            )
        )

        val row = ReservationSpreadsheetRow.fromReservation(reservation(ticketSales = listOf(partialSale)))

        assertTrue(row.paymentTicketCounts.isEmpty())
        assertEquals(2_000, row.realizedTickets.single().paidAmountCents)
        assertTrue(row.toPhysicalSheetRows().any { it.notes.contains("Osamaksu") })
    }

    @Test
    fun exportRows_includesImportedLippuagenttiCounts() {
        val importedSale = TicketSale(
            id = 1,
            reservationId = 1,
            ticketType = TicketType.UNSPECIFIED,
            quantity = 2,
            unitPriceCents = 0,
            origin = TicketSaleOrigin.IMPORTED,
            countsAsArrival = false,
            payments = listOf(PaymentAllocation(1, 1, PaymentMethod.LIPPUAGENTTI, 0))
        )

        val row = ReservationSpreadsheetRow.fromReservation(reservation(seatCount = 2, ticketSales = listOf(importedSale)))

        assertEquals(2, row.realizedTickets.size)
        assertTrue(row.realizedTickets.all { it.payments.single().method == PaymentMethod.LIPPUAGENTTI })
        assertEquals(0, row.arrivalCount)
    }

    @Test
    fun doorSales_areExportedAsSeparateOvimyyntiRows() {
        val rows = ReservationSpreadsheetRow.fromReservations(
            listOf(
                reservation(
                    id = 1,
                    admissionType = AdmissionType.DOOR_SALE,
                    lastName = "",
                    firstName = "",
                    seatCount = 1,
                    ticketSales = listOf(sale(1, TicketType.BASIC, 1, PaymentMethod.CASH))
                ),
                reservation(
                    id = 2,
                    admissionType = AdmissionType.DOOR_SALE,
                    lastName = "",
                    firstName = "",
                    seatCount = 1,
                    ticketSales = listOf(sale(2, TicketType.DISCOUNT, 1, PaymentMethod.EPASSI))
                )
            )
        )

        assertEquals(2, rows.size)
        val physicalRows = rows.map { it.toPhysicalSheetRows().single() }
        assertTrue(physicalRows.all { row -> row.lastName == "- Ovimyynti" && row.reservedSeatCount == 1 })
        assertEquals(1, physicalRows[0].reservedTicketCounts[TicketType.BASIC])
        assertEquals(1, physicalRows[0].paymentTicketCounts[PaymentMethod.CASH])
        assertEquals(1, physicalRows[1].reservedTicketCounts[TicketType.DISCOUNT])
        assertEquals(1, physicalRows[1].paymentTicketCounts[PaymentMethod.EPASSI])
    }

    @Test
    fun directRowConversionUsesDoorSaleFormattingAndPreservesIdentity() {
        val reservation = reservation(
            admissionType = AdmissionType.DOOR_SALE,
            lastName = "",
            firstName = "",
            seatCount = 1,
            ticketSales = listOf(sale(1, TicketType.BASIC, 1, PaymentMethod.CARD))
        ).copy(
            sourceIdentity = "local-door-sale-1",
            sheetRowId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
        )

        val row = ReservationSpreadsheetRow.fromReservation(reservation)

        assertEquals("- Ovimyynti", row.lastName)
        assertEquals("local-door-sale-1", row.sourceIdentity)
        assertEquals(reservation.sheetRowId, row.sheetRowId)
        assertEquals(TicketType.BASIC, row.realizedTickets.single().ticketType)
        assertEquals(PaymentMethod.CARD, row.realizedTickets.single().payments.single().method)
        assertTrue(row.isDoorSale)
    }

    @Test
    fun freeTicketsAreFullyRedeemedWithoutARecordedPayment() {
        val freeTicket = sale(1, TicketType.FREE_TICKET, 1, null)

        assertTrue(freeTicket.isPaid)
        assertFalse(freeTicket.hasMultiplePayments)
        assertEquals(0, freeTicket.paidAmountCents)
    }

    private fun reservation(
        id: Long = 1,
        lastName: String = "Virtanen",
        firstName: String = "Maija",
        seatCount: Int = 2,
        admissionType: AdmissionType = AdmissionType.RESERVATION,
        reservedTicketAllocations: List<ReservedTicketAllocation> = emptyList(),
        ticketSales: List<TicketSale> = emptyList()
    ) = Reservation(
        id = id,
        performanceId = 1,
        lastName = lastName,
        firstName = firstName,
        contact = "",
        seatCount = seatCount,
        arrivalCount = if (admissionType == AdmissionType.DOOR_SALE) seatCount else 0,
        admissionType = admissionType,
        reservedTicketAllocations = reservedTicketAllocations,
        ticketSales = ticketSales
    )

    private fun sale(
        id: Long,
        ticketType: TicketType,
        quantity: Int,
        paymentMethod: PaymentMethod?
    ): TicketSale {
        val total = ticketType.defaultPriceCents * quantity
        return TicketSale(
            id = id,
            reservationId = if (id == 3L) 2 else 1,
            ticketType = ticketType,
            quantity = quantity,
            unitPriceCents = ticketType.defaultPriceCents,
            payments = paymentMethod?.let { method ->
                listOf(PaymentAllocation(id, if (id == 3L) 2 else 1, method, total))
            }.orEmpty()
        )
    }
}
