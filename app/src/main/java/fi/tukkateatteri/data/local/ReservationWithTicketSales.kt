package fi.tukkateatteri.data.local

import androidx.room.Embedded
import androidx.room.Relation
import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale

data class ReservationWithTicketSales(
    @Embedded val reservation: ReservationEntity,
    @Relation(
        entity = TicketSaleEntity::class,
        parentColumn = "id",
        entityColumn = "reservation_id"
    )
    val ticketSales: List<TicketSaleWithPayments>,
    @Relation(
        parentColumn = "id",
        entityColumn = "reservation_id"
    )
    val reservedTicketAllocations: List<ReservedTicketAllocationEntity>
)

data class TicketSaleWithPayments(
    @Embedded val ticketSale: TicketSaleEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "ticket_sale_id"
    )
    val payments: List<PaymentAllocationEntity>
)

fun ReservationWithTicketSales.toReservation() = Reservation(
    id = reservation.id,
    performanceId = reservation.performanceId,
    lastName = reservation.lastName,
    firstName = reservation.firstName,
    contact = reservation.contact,
    seatCount = reservation.seatCount,
    notes = reservation.notes,
    sourceIdentity = reservation.sourceIdentity,
    sheetRowId = reservation.sheetRowId,
    syncState = reservation.syncState,
    admissionType = reservation.admissionType,
    arrivalCount = reservation.arrivalCount,
    reservedTicketAllocations = reservedTicketAllocations
        .sortedBy { allocation -> allocation.ticketType.sortOrder }
        .map { allocation ->
            ReservedTicketAllocation(
                ticketType = allocation.ticketType,
                quantity = allocation.quantity
            )
        },
    ticketSales = ticketSales
        .sortedBy { sale -> sale.ticketSale.id }
        .map(TicketSaleWithPayments::toTicketSale)
)

private fun TicketSaleWithPayments.toTicketSale() = TicketSale(
    id = ticketSale.id,
    reservationId = ticketSale.reservationId,
    ticketType = ticketSale.ticketType,
    quantity = ticketSale.quantity,
    unitPriceCents = ticketSale.unitPriceCents,
    origin = ticketSale.origin,
    countsAsArrival = ticketSale.countsAsArrival,
    payments = payments.map { payment ->
        PaymentAllocation(
            id = payment.id,
            ticketSaleId = payment.ticketSaleId,
            method = payment.paymentMethod,
            amountCents = payment.amountCents,
            zettleSuccessful = payment.zettleSuccessful
        )
    }
)
