package fi.tukkateatteri.data

import androidx.annotation.StringRes
import fi.tukkateatteri.R

data class Reservation(
    val id: Long,
    val lastName: String,
    val firstName: String,
    val contact: String,
    val seatCount: Int,
    val notes: String = "",
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    val ticketSales: List<TicketSale> = emptyList()
) {
    init {
        require(id > 0) { "Reservation ID must be positive." }
        require(seatCount > 0) { "Seat count must be positive." }

        if (admissionType == AdmissionType.RESERVATION) {
            require(lastName.isNotBlank()) { "Last name must not be blank for reservations." }
            require(firstName.isNotBlank()) { "First name must not be blank for reservations." }
        }
    }

    val displayName: String
        get() = "$lastName $firstName"

    val redeemedSeatCount: Int
        get() = ticketSales.sumOf(TicketSale::quantity)

    val remainingSeatCount: Int
        get() = (seatCount - redeemedSeatCount).coerceAtLeast(0)

    val isPresent: Boolean
        get() = redeemedSeatCount > 0

    val isCompleted: Boolean
        get() = redeemedSeatCount >= seatCount && ticketSales.all(TicketSale::isPaid)
}

enum class AdmissionType(@param:StringRes val labelResId: Int) {
    RESERVATION(R.string.admission_type_reservation),
    DOOR_SALE(R.string.admission_type_door_sale)
}

enum class PaymentMethod(@param:StringRes val labelResId: Int) {
    CARD(R.string.payment_method_card),
    CASH(R.string.payment_method_cash),
    EPASSI(R.string.payment_method_epassi),
    LIPPUAGENTTI(R.string.payment_method_lippuagentti)
}
