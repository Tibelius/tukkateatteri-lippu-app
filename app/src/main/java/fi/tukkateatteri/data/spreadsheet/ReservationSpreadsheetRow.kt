package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod

data class ReservationSpreadsheetRow(
    val lastName: String,
    val firstName: String,
    val contact: String,
    val seatCount: Int,
    val admissionType: AdmissionType,
    val isPresent: Boolean,
    val paymentMethod: PaymentMethod?
) {
    init {
        require(seatCount > 0) { "Seat count must be positive." }

        if (admissionType == AdmissionType.RESERVATION) {
            require(lastName.isNotBlank()) { "Last name must not be blank for reservations." }
            require(firstName.isNotBlank()) { "First name must not be blank for reservations." }
        } else {
            require(isPresent) { "Door sales must be marked present." }
            require(paymentMethod != null) { "Door sales must have a payment method." }
        }
    }
}
