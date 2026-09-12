package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString

enum class ApplicationMutationMetadataState {
    NONE,
    VALID,
    INVALID
}

data class RealizedTicketSpreadsheetRow(
    val ticketType: TicketType,
    val payments: List<SpreadsheetPaymentAllocation>,
    val arrived: Boolean
) {
    val totalPriceCents: Int
        get() = ticketType.defaultPriceCents

    val paidAmountCents: Int
        get() = payments.sumOf(SpreadsheetPaymentAllocation::amountCents)

    val isPaid: Boolean
        get() = paidAmountCents == totalPriceCents

    fun paymentCounts(): Map<PaymentMethod, Int> = payments
        .takeIf(List<SpreadsheetPaymentAllocation>::isNotEmpty)
        ?.minByOrNull { PaymentMethod.displaySortOrder(it.method) }
        ?.let { mapOf(it.method to 1) }
        .orEmpty()

    fun paymentNote(): String {
        if (payments.isEmpty() || (isPaid && payments.size == 1)) return ""
        val values = payments.sortedBy { PaymentMethod.displaySortOrder(it.method) }
            .joinToString(PAYMENT_NOTE_SEPARATOR) { payment ->
            "${payment.method.label} ${payment.amountCents.toEuroString()}"
        }
        return "$PARTIAL_PAYMENT_LABEL: $values"
    }
}

data class SpreadsheetPaymentAllocation(
    val method: PaymentMethod,
    val amountCents: Int
)

data class ReservationSpreadsheetRow(
    val lastName: String,
    val firstName: String,
    val contact: String,
    val reservedSeatCount: Int,
    val arrivalCount: Int,
    val reservedTicketCounts: Map<TicketType, Int>,
    val paymentTicketCounts: Map<PaymentMethod, Int>,
    val notes: String,
    val realizedTickets: List<RealizedTicketSpreadsheetRow> = emptyList(),
    val sourceIdentity: String = "",
    val sheetRowId: String = "",
    val sourceRowNumber: Int? = null,
    val applicationMutationMetadataState: ApplicationMutationMetadataState = ApplicationMutationMetadataState.NONE
) {
    init {
        require(reservedSeatCount >= 0) { "Reserved seat count must not be negative." }
        require(arrivalCount in 0..reservedSeatCount.coerceAtLeast(1)) {
            "Arrival count must be valid for the row."
        }
        require(reservedTicketCounts.values.all { it >= 0 }) { "Reserved ticket counts must not be negative." }
        require(paymentTicketCounts.values.all { it >= 0 }) { "Payment ticket counts must not be negative." }
    }

    val isDoorSale: Boolean
        get() = lastName.isDoorSaleSheetLabel() ||
            (lastName.isBlank() && firstName.isBlank() && sheetRowId.isUuid())

    val isMalformedAppOwnedDoorSaleRow: Boolean
        get() = lastName.isBlank() && firstName.isBlank() && sheetRowId.isUuid() &&
            applicationMutationMetadataState == ApplicationMutationMetadataState.VALID

    companion object {
        fun fromReservations(reservations: List<Reservation>): List<ReservationSpreadsheetRow> =
            reservations.map(::fromReservation)

        fun fromReservation(reservation: Reservation): ReservationSpreadsheetRow = ReservationSpreadsheetRow(
            lastName = if (reservation.admissionType == AdmissionType.DOOR_SALE) {
                DOOR_SALE_SHEET_LABEL
            } else {
                reservation.lastName
            },
            firstName = if (reservation.admissionType == AdmissionType.DOOR_SALE) "" else reservation.firstName,
            contact = if (reservation.admissionType == AdmissionType.DOOR_SALE) "" else reservation.contact,
            reservedSeatCount = reservation.seatCount,
            arrivalCount = reservation.arrivalCount,
            reservedTicketCounts = if (reservation.admissionType == AdmissionType.DOOR_SALE) {
                emptyMap()
            } else {
                reservation.reservedTicketAllocations.associate { it.ticketType to it.quantity }
            },
            paymentTicketCounts = emptyMap(),
            notes = reservation.notes,
            realizedTickets = reservation.ticketSales.toRealizedTicketRows(),
            sourceIdentity = reservation.sourceIdentity,
            sheetRowId = reservation.sheetRowId
        )
    }
}

internal fun ReservationSpreadsheetRow.toPhysicalSheetRows(): List<ReservationSpreadsheetRow> {
    val seatCount = maxOf(reservedSeatCount, realizedTickets.size)
    if (seatCount == 0) return listOf(copy(realizedTickets = emptyList()))
    val reservedTypes = reservedTicketCounts.entries.flatMap { (type, quantity) -> List(quantity) { type } }
        .take(seatCount)
        .toMutableList<TicketType?>()
        .apply { repeat(seatCount - size) { add(null) } }
    val realizedBySeat = MutableList<RealizedTicketSpreadsheetRow?>(seatCount) { null }
    realizedTickets.forEach { ticket ->
        val matchingIndex = reservedTypes.indices.firstOrNull { index ->
            realizedBySeat[index] == null && reservedTypes[index]?.name == ticket.ticketType.name
        }
        val targetIndex = matchingIndex ?: realizedBySeat.indexOfFirst { it == null }
        if (targetIndex >= 0) realizedBySeat[targetIndex] = ticket
    }
    return List(seatCount) { index ->
        val reservedType = reservedTypes[index]
        val realized = realizedBySeat[index]
        val displayedType = realized?.ticketType ?: reservedType
        val generatedNotes = buildList {
            realized?.paymentNote()?.takeIf(String::isNotBlank)?.let(::add)
            if (reservedType != null && realized != null && reservedType.name != realized.ticketType.name) {
                add("$RESERVED_TICKET_LABEL: ${reservedType.label}")
            }
            if (index == 0) notes.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(SHEET_NOTE_LINE_SEPARATOR)
        copy(
            reservedSeatCount = 1,
            arrivalCount = if (realized?.arrived == true) 1 else 0,
            reservedTicketCounts = displayedType?.let { mapOf(it to 1) }.orEmpty(),
            paymentTicketCounts = realized?.paymentCounts().orEmpty(),
            notes = generatedNotes,
            realizedTickets = emptyList(),
            sourceRowNumber = null
        )
    }
}

private fun List<TicketSale>.toRealizedTicketRows(): List<RealizedTicketSpreadsheetRow> = flatMap { sale ->
    if (sale.unitPriceCents == 0) {
        return@flatMap List(sale.quantity) {
            RealizedTicketSpreadsheetRow(
                ticketType = sale.ticketType,
                payments = sale.payments.firstOrNull()?.let { payment ->
                    listOf(SpreadsheetPaymentAllocation(payment.method, 0))
                }.orEmpty(),
                arrived = sale.countsAsArrival
            )
        }
    }
    val remainingPayments = sale.payments.map { payment ->
        MutableSpreadsheetPayment(payment.method, payment.amountCents)
    }.toMutableList()
    List(sale.quantity) {
        var amountNeeded = sale.unitPriceCents
        val allocations = buildList {
            while (amountNeeded > 0 && remainingPayments.isNotEmpty()) {
                val payment = remainingPayments.first()
                val allocated = minOf(amountNeeded, payment.amountCents)
                if (allocated > 0) add(SpreadsheetPaymentAllocation(payment.method, allocated))
                amountNeeded -= allocated
                payment.amountCents -= allocated
                if (payment.amountCents == 0) remainingPayments.removeAt(0)
            }
        }
        RealizedTicketSpreadsheetRow(
            ticketType = sale.ticketType,
            payments = allocations,
            arrived = sale.countsAsArrival
        )
    }
}

private data class MutableSpreadsheetPayment(
    val method: PaymentMethod,
    var amountCents: Int
)

internal const val DOOR_SALE_SHEET_LABEL = "- Ovimyynti"
internal const val PARTIAL_PAYMENT_LABEL = "Osamaksu"
internal const val RESERVED_TICKET_LABEL = "Varattu lipputyyppi"
internal const val PAYMENT_NOTE_SEPARATOR = "; "
internal const val SHEET_NOTE_LINE_SEPARATOR = "\n"
