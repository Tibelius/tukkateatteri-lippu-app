package fi.tukkateatteri.data

import androidx.annotation.StringRes
import fi.tukkateatteri.R

data class Reservation(
    val id: Long,
    val lastName: String,
    val firstName: String,
    val contact: String,
    val seatCount: Int,
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    val isPresent: Boolean = false,
    val paymentMethod: PaymentMethod? = null
) {
    init {
        require(id > 0) { "Reservation ID must be positive." }
        require(seatCount > 0) { "Seat count must be positive." }

        if (admissionType == AdmissionType.RESERVATION) {
            require(lastName.isNotBlank()) { "Last name must not be blank for reservations." }
            require(firstName.isNotBlank()) { "First name must not be blank for reservations." }
        } else {
            require(isPresent) { "Door sales must be marked present." }
            require(paymentMethod != null) { "Door sales must have a payment method." }
        }
    }

    val displayName: String
        get() = "$lastName $firstName"

    val isCompleted: Boolean
        get() = isPresent && paymentMethod != null
}

enum class AdmissionType(@param:StringRes val labelResId: Int) {
    RESERVATION(R.string.admission_type_reservation),
    DOOR_SALE(R.string.admission_type_door_sale)
}

enum class PaymentMethod(@param:StringRes val labelResId: Int) {
    CARD(R.string.payment_method_card),
    CASH(R.string.payment_method_cash),
    PREPAID(R.string.payment_method_prepaid),
    KAIKUKORTTI(R.string.payment_method_kaikukortti),
    FREE_TICKET(R.string.payment_method_free_ticket),
    OTHER(R.string.payment_method_other)
}
