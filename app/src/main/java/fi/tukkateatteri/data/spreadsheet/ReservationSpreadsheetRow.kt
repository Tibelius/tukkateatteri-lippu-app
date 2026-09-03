package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketType

data class ReservationSpreadsheetRow(
    val lastName: String,
    val firstName: String,
    val contact: String,
    val reservedSeatCount: Int,
    val redeemedSeatCount: Int,
    val ticketCounts: Map<TicketType, Int>,
    val paymentTicketCounts: Map<PaymentMethod, Int>,
    val notes: String,
    val sourceIdentity: String = ""
) {
    init {
        require(reservedSeatCount > 0) { "Reserved seat count must be positive." }
        require(redeemedSeatCount >= 0) { "Redeemed seat count must not be negative." }
        require(ticketCounts.values.all { it >= 0 }) { "Ticket counts must not be negative." }
        require(paymentTicketCounts.values.all { it >= 0 }) { "Payment ticket counts must not be negative." }
    }

    companion object {
        fun fromReservation(reservation: Reservation): ReservationSpreadsheetRow {
            val ticketCounts = TicketType.entries.associateWith { ticketType ->
                reservation.ticketSales.filter { it.ticketType == ticketType }.sumOf { it.quantity }
            }.filterValues { it > 0 }
            val paymentTicketCounts = PaymentMethod.entries.associateWith { paymentMethod ->
                reservation.ticketSales.filter { it.payments.size == 1 && it.payments.single().method == paymentMethod }.sumOf { it.quantity }
            }.filterValues { it > 0 }
            val splitPaymentNotes = reservation.ticketSales.filter { it.isSplitPayment }.joinToString(separator = "; ") { ticketSale ->
                val payments = ticketSale.payments.joinToString(separator = ", ") { payment ->
                    "${payment.method.name.lowercase()} ${payment.amountCents / 100},${(payment.amountCents % 100).toString().padStart(2, '0')} €"
                }
                "Sekamaksu: $payments"
            }
            return ReservationSpreadsheetRow(
                lastName = reservation.lastName,
                firstName = reservation.firstName,
                contact = reservation.contact,
                reservedSeatCount = reservation.seatCount,
                redeemedSeatCount = reservation.redeemedSeatCount,
                ticketCounts = ticketCounts,
                paymentTicketCounts = paymentTicketCounts,
                notes = listOf(reservation.notes, splitPaymentNotes).filter(String::isNotBlank).joinToString("; ")
            )
        }
    }
}
