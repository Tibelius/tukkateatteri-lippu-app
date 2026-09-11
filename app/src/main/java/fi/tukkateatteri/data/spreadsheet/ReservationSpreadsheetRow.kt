package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString

/** Describes whether the centralized app state matches the current visible Sheet row. */
enum class ApplicationMutationMetadataState {
    NONE,
    VALID,
    INVALID
}

data class ReservationSpreadsheetRow(
    val lastName: String,
    val firstName: String,
    val contact: String,
    val reservedSeatCount: Int,
    val arrivalCount: Int,
    val reservedTicketCounts: Map<TicketType, Int>,
    val paymentTicketCounts: Map<PaymentMethod, Int>,
    val notes: String,
    val sourceIdentity: String = "",
    val sheetRowId: String = "",
    val sourceRowNumber: Int? = null,
    val applicationMutationMetadataState: ApplicationMutationMetadataState = ApplicationMutationMetadataState.NONE
) {
    init {
        require(reservedSeatCount >= MINIMUM_SEAT_COUNT) { "Reserved seat count must be positive." }
        require(arrivalCount in 0..reservedSeatCount) { "Arrival count must be within the reserved seat count." }
        require(reservedTicketCounts.values.all { it >= 0 }) { "Reserved ticket counts must not be negative." }
        require(paymentTicketCounts.values.all { it >= 0 }) { "Payment ticket counts must not be negative." }
    }

    companion object {
        fun fromReservations(reservations: List<Reservation>): List<ReservationSpreadsheetRow> {
            return reservations.map { reservation ->
                if (reservation.admissionType == AdmissionType.DOOR_SALE) {
                    fromDoorSale(reservation)
                } else {
                    fromReservation(reservation)
                }
            }
        }

        fun fromReservation(reservation: Reservation): ReservationSpreadsheetRow {
            val reservedTicketCounts = reservation.reservedTicketAllocations.associate { allocation ->
                allocation.ticketType to allocation.quantity
            }
            val splitPaymentNotes = reservation.ticketSales.toSplitPaymentNotes()
            return ReservationSpreadsheetRow(
                lastName = reservation.lastName,
                firstName = reservation.firstName,
                contact = reservation.contact,
                reservedSeatCount = reservation.seatCount,
                arrivalCount = reservation.arrivalCount,
                reservedTicketCounts = reservedTicketCounts,
                paymentTicketCounts = reservation.ticketSales.paymentTicketCounts(),
                notes = listOf(reservation.notes, splitPaymentNotes).filter(String::isNotBlank).joinToString("; "),
                sourceIdentity = reservation.sourceIdentity,
                sheetRowId = reservation.sheetRowId
            )
        }

        private fun fromDoorSale(doorSale: Reservation): ReservationSpreadsheetRow {
            val splitPaymentNotes = doorSale.ticketSales.toSplitPaymentNotes()
            return ReservationSpreadsheetRow(
                lastName = DOOR_SALE_SHEET_LABEL,
                firstName = "",
                contact = "",
                reservedSeatCount = doorSale.seatCount,
                arrivalCount = doorSale.arrivalCount,
                reservedTicketCounts = doorSale.ticketSales.ticketTypeCounts(),
                paymentTicketCounts = doorSale.ticketSales.paymentTicketCounts(),
                notes = splitPaymentNotes,
                sheetRowId = doorSale.sheetRowId
            )
        }
    }
}

internal const val DOOR_SALE_SHEET_LABEL = "Ovimyynti"

private fun List<TicketSale>.ticketTypeCounts(): Map<TicketType, Int> =
    groupBy(TicketSale::ticketType)
        .mapValues { (_, sales) -> sales.sumOf(TicketSale::quantity) }

private fun List<TicketSale>.paymentTicketCounts(): Map<PaymentMethod, Int> =
    mapNotNull { sale -> sale.singlePaymentMethod?.let { method -> method to sale.quantity } }
        .groupingBy(Pair<PaymentMethod, Int>::first)
        .fold(0) { quantity, (_, saleQuantity) -> quantity + saleQuantity }

private fun List<TicketSale>.toSplitPaymentNotes(): String = filter(TicketSale::isSplitPayment)
    .joinToString(separator = "; ") { ticketSale ->
        val payments = ticketSale.payments.joinToString(separator = ", ") { payment ->
            "${payment.method.name.lowercase()} ${payment.amountCents.toEuroString()}"
        }
        "Sekamaksu: $payments"
    }
