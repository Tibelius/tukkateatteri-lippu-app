package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString

data class ReservationSpreadsheetRow(
    val lastName: String,
    val firstName: String,
    val contact: String,
    val reservedSeatCount: Int,
    val arrivalCount: Int,
    val reservedTicketCounts: Map<TicketType, Int>,
    val paymentTicketCounts: Map<PaymentMethod, Int>,
    val notes: String,
    val sourceIdentity: String = ""
) {
    init {
        require(reservedSeatCount > 0) { "Reserved seat count must be positive." }
        require(arrivalCount in 0..reservedSeatCount) { "Arrival count must be within the reserved seat count." }
        require(reservedTicketCounts.values.all { it >= 0 }) { "Reserved ticket counts must not be negative." }
        require(paymentTicketCounts.values.all { it >= 0 }) { "Payment ticket counts must not be negative." }
    }

    companion object {
        fun fromReservations(reservations: List<Reservation>): List<ReservationSpreadsheetRow> {
            val reservationRows = reservations
                .filter { reservation -> reservation.admissionType == AdmissionType.RESERVATION }
                .map(::fromReservation)
            val doorSales = reservations.filter { reservation -> reservation.admissionType == AdmissionType.DOOR_SALE }
            return if (doorSales.isEmpty()) reservationRows else reservationRows + fromDoorSales(doorSales)
        }

        fun fromReservation(reservation: Reservation): ReservationSpreadsheetRow {
            val reservedTicketCounts = TicketType.entries.associateWith { ticketType ->
                reservation.reservedTicketAllocations
                    .filter { it.ticketType == ticketType }
                    .sumOf { it.quantity }
            }.filterValues { it > 0 }
            val paymentTicketCounts = PaymentMethod.entries.associateWith { paymentMethod ->
                reservation.ticketSales
                    .filter { ticketSale -> ticketSale.singlePaymentMethod == paymentMethod }
                    .sumOf { ticketSale -> ticketSale.quantity }
            }.filterValues { it > 0 }
            val splitPaymentNotes = reservation.ticketSales.toSplitPaymentNotes()
            return ReservationSpreadsheetRow(
                lastName = reservation.lastName,
                firstName = reservation.firstName,
                contact = reservation.contact,
                reservedSeatCount = reservation.seatCount,
                arrivalCount = reservation.arrivalCount,
                reservedTicketCounts = reservedTicketCounts,
                paymentTicketCounts = paymentTicketCounts,
                notes = listOf(reservation.notes, splitPaymentNotes).filter(String::isNotBlank).joinToString("; ")
            )
        }

        private fun fromDoorSales(doorSales: List<Reservation>): ReservationSpreadsheetRow {
            val ticketTypeCounts = TicketType.entries.associateWith { ticketType ->
                doorSales.sumOf { reservation ->
                    reservation.ticketSales
                        .filter { ticketSale -> ticketSale.ticketType == ticketType }
                        .sumOf { ticketSale -> ticketSale.quantity }
                }
            }.filterValues { it > 0 }
            val paymentTicketCounts = PaymentMethod.entries.associateWith { paymentMethod ->
                doorSales.sumOf { reservation ->
                    reservation.ticketSales
                        .filter { ticketSale -> ticketSale.singlePaymentMethod == paymentMethod }
                        .sumOf { ticketSale -> ticketSale.quantity }
                }
            }.filterValues { it > 0 }
            val splitPaymentNotes = doorSales.flatMap(Reservation::ticketSales).toSplitPaymentNotes()
            return ReservationSpreadsheetRow(
                lastName = DOOR_SALE_SHEET_LABEL,
                firstName = "",
                contact = "",
                reservedSeatCount = doorSales.sumOf(Reservation::seatCount),
                arrivalCount = doorSales.sumOf(Reservation::arrivalCount),
                reservedTicketCounts = ticketTypeCounts,
                paymentTicketCounts = paymentTicketCounts,
                notes = splitPaymentNotes
            )
        }

        private const val DOOR_SALE_SHEET_LABEL = "Ovelta"
    }
}

private fun List<TicketSale>.toSplitPaymentNotes(): String = filter(TicketSale::isSplitPayment)
    .joinToString(separator = "; ") { ticketSale ->
        val payments = ticketSale.payments.joinToString(separator = ", ") { payment ->
            "${payment.method.name.lowercase()} ${payment.amountCents.toEuroString()}"
        }
        "Sekamaksu: $payments"
    }
