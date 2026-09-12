package fi.tukkateatteri.data

import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow

internal fun ReservationSpreadsheetRow.matches(reservation: Reservation?): Boolean {
    reservation ?: return false
    return (sheetRowId.isNotBlank() && sheetRowId == reservation.sheetRowId) ||
        (sourceIdentity.isNotBlank() && sourceIdentity == reservation.sourceIdentity) ||
        (!isDoorSale && reservation.admissionType != AdmissionType.DOOR_SALE &&
            lastName.equals(reservation.lastName, ignoreCase = true) &&
            firstName.equals(reservation.firstName, ignoreCase = true) &&
            contact.equals(reservation.contact, ignoreCase = true))
}

internal fun ReservationSpreadsheetRow.matches(other: ReservationSpreadsheetRow?): Boolean {
    other ?: return false
    return (sheetRowId.isNotBlank() && sheetRowId == other.sheetRowId) ||
        (sourceIdentity.isNotBlank() && sourceIdentity == other.sourceIdentity) ||
        (!isDoorSale && !other.isDoorSale &&
            lastName.equals(other.lastName, ignoreCase = true) &&
            firstName.equals(other.firstName, ignoreCase = true) &&
            contact.equals(other.contact, ignoreCase = true))
}

internal data class PendingSheetRowMerge(
    val row: ReservationSpreadsheetRow,
    val hasConflict: Boolean
)

/**
 * Keeps direct Sheet edits to fields untouched by the app action. A conflict occurs only when
 * both sides changed the same exported field to different values.
 */
internal fun mergePendingSheetRow(
    base: ReservationSpreadsheetRow,
    desired: ReservationSpreadsheetRow,
    remote: ReservationSpreadsheetRow
): PendingSheetRowMerge {
    var hasConflict = false

    fun <T> merged(baseValue: T, desiredValue: T, remoteValue: T): T {
        val appChanged = desiredValue != baseValue
        val sheetChanged = remoteValue != baseValue
        if (appChanged && sheetChanged && desiredValue != remoteValue) hasConflict = true
        return if (appChanged) desiredValue else remoteValue
    }

    return PendingSheetRowMerge(
        row = desired.copy(
            lastName = merged(base.lastName, desired.lastName, remote.lastName),
            firstName = merged(base.firstName, desired.firstName, remote.firstName),
            contact = merged(base.contact, desired.contact, remote.contact),
            reservedSeatCount = merged(
                base.reservedSeatCount,
                desired.reservedSeatCount,
                remote.reservedSeatCount
            ),
            arrivalCount = merged(base.arrivalCount, desired.arrivalCount, remote.arrivalCount),
            reservedTicketCounts = merged(
                base.reservedTicketCounts,
                desired.reservedTicketCounts,
                remote.reservedTicketCounts
            ),
            paymentTicketCounts = merged(
                base.paymentTicketCounts,
                desired.paymentTicketCounts,
                remote.paymentTicketCounts
            ),
            realizedTickets = merged(
                base.realizedTickets,
                desired.realizedTickets,
                remote.realizedTickets
            ),
            notes = merged(base.notes, desired.notes, remote.notes),
            sourceIdentity = desired.sourceIdentity.ifBlank { remote.sourceIdentity },
            sheetRowId = desired.sheetRowId.ifBlank { remote.sheetRowId }
        ),
        hasConflict = hasConflict
    )
}
