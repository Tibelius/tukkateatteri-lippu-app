package fi.tukkateatteri.logging

import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow

/** Log-safe representation that deliberately excludes customer names, contact data, and notes. */
internal fun Reservation.toLogSummary(): String =
    "reservationId=$id, performanceId=$performanceId, admission=$admissionType, " +
        "seats=$seatCount, arrived=$arrivalCount, redeemed=$paidSeatCount, " +
        "reservedTypes=${reservedTicketAllocations.associate { it.ticketType to it.quantity }.toCountSummary()}, " +
        "sales=${ticketSales.groupBy { it.ticketType }.mapValues { (_, sales) -> sales.sumOf { it.quantity } }.toCountSummary()}, " +
        "syncState=$syncState, sheetRowId=${sheetRowId.toAbbreviatedId()}"

/** Log-safe representation that deliberately excludes customer names, contact data, and notes. */
internal fun ReservationSpreadsheetRow.toLogSummary(): String =
    "sheetRow=${sourceRowNumber ?: "new"}, sheetRowId=${sheetRowId.toAbbreviatedId()}, " +
        "seats=$reservedSeatCount, arrived=$arrivalCount, " +
        "reservedTypes=${reservedTicketCounts.toCountSummary()}, " +
        "payments=${paymentTicketCounts.toCountSummary()}, metadata=$applicationMutationMetadataState"

internal fun List<PendingPaymentAllocation>.toPaymentLogSummary(): String =
    groupBy(PendingPaymentAllocation::method)
        .mapValues { (_, payments) -> payments.sumOf(PendingPaymentAllocation::amountCents) }
        .toCountSummary()

internal fun String.toAbbreviatedId(): String = when {
    isBlank() -> "none"
    length <= ABBREVIATED_ID_LENGTH -> this
    else -> take(ABBREVIATED_ID_LENGTH)
}

private fun Map<*, Int>.toCountSummary(): String = entries
    .filter { (_, count) -> count > 0 }
    .sortedBy { (key, _) -> key.toString() }
    .joinToString(prefix = "[", postfix = "]") { (key, count) -> "$key=$count" }

private const val ABBREVIATED_ID_LENGTH = 8
